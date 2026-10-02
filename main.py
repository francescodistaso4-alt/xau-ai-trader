import asyncio, json, os, sqlite3, time
from contextlib import asynccontextmanager
from typing import Optional
import websockets
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query
from pydantic import BaseModel, Field

DB_PATH=os.getenv('DB_PATH','./xau.db')
UPSTREAM_WS_URL=os.getenv('UPSTREAM_WS_URL','')
UPSTREAM_API_KEY=os.getenv('UPSTREAM_API_KEY','')
UPSTREAM_SYMBOL=os.getenv('UPSTREAM_SYMBOL','XAUUSD')
TIMEFRAMES={'M1':60,'M5':300,'M15':900,'H1':3600,'H4':14400}

class Bar(BaseModel):
    symbol:str='XAUUSD'; timeframe:str='M1'; time:int
    open:float; high:float; low:float; close:float; volume:float=0.0

class Hub:
    def __init__(self): self.clients:set[WebSocket]=set()
    async def add(self,ws): self.clients.add(ws)
    def remove(self,ws): self.clients.discard(ws)
    async def broadcast(self,bar:Bar):
        msg=bar.model_dump_json()
        dead=[]
        for ws in list(self.clients):
            try: await ws.send_text(msg)
            except Exception: dead.append(ws)
        for ws in dead: self.remove(ws)

hub=Hub()

def init_db():
    con=sqlite3.connect(DB_PATH)
    con.execute('''CREATE TABLE IF NOT EXISTS bars(symbol TEXT,timeframe TEXT,time INTEGER,open REAL,high REAL,low REAL,close REAL,volume REAL,PRIMARY KEY(symbol,timeframe,time))''')
    con.commit(); con.close()

def save_bar(b:Bar):
    con=sqlite3.connect(DB_PATH)
    con.execute('INSERT OR REPLACE INTO bars VALUES (?,?,?,?,?,?,?,?)',(b.symbol,b.timeframe,b.time,b.open,b.high,b.low,b.close,b.volume))
    con.commit(); con.close()

def load_bars(symbol,tf,start,end,limit=2000):
    con=sqlite3.connect(DB_PATH)
    rows=con.execute('SELECT symbol,timeframe,time,open,high,low,close,volume FROM bars WHERE symbol=? AND timeframe=? AND time BETWEEN ? AND ? ORDER BY time ASC LIMIT ?', (symbol,tf,start,end,limit)).fetchall()
    con.close(); return [dict(zip(['symbol','timeframe','time','open','high','low','close','volume'],r)) for r in rows]

def aggregate_ohlc(m1:Bar, tf:str)->Bar:
    sec=TIMEFRAMES[tf]; bucket=(m1.time//sec)*sec
    return Bar(symbol=m1.symbol,timeframe=tf,time=bucket,open=m1.open,high=m1.high,low=m1.low,close=m1.close,volume=m1.volume)

# In-memory current aggregate bars; historical bars are persisted in SQLite.
current:dict[str,Bar]={}

def update_aggregate(m1:Bar,tf:str)->Bar:
    sec=TIMEFRAMES[tf]; bucket=(m1.time//sec)*sec; old=current.get(tf)
    if old is None or old.time!=bucket:
        b=aggregate_ohlc(m1,tf); current[tf]=b; return b
    b=old.model_copy(update={'high':max(old.high,m1.high),'low':min(old.low,m1.low),'close':m1.close,'volume':old.volume+m1.volume})
    current[tf]=b; return b

async def ingest(bar:Bar):
    # Upstream is normalized to one-minute bars. We create M5/M15/H1/H4 locally.
    save_bar(bar)
    await hub.broadcast(bar)
    for tf in ('M5','M15','H1','H4'):
        agg=update_aggregate(bar,tf); save_bar(agg); await hub.broadcast(agg)

async def upstream_loop():
    if not UPSTREAM_WS_URL:
        return
    while True:
        try:
            headers={'Authorization':f'Bearer {UPSTREAM_API_KEY}'} if UPSTREAM_API_KEY else None
            async with websockets.connect(UPSTREAM_WS_URL, additional_headers=headers, ping_interval=20, ping_timeout=20) as ws:
                await ws.send(json.dumps({'action':'subscribe','symbol':UPSTREAM_SYMBOL,'timeframe':'1m'}))
                async for raw in ws:
                    try:
                        x=json.loads(raw)
                        # Provider adapter contract: map provider payload to these fields.
                        b=Bar(symbol=x.get('symbol',UPSTREAM_SYMBOL), timeframe='M1', time=int(x['time']), open=float(x['open']), high=float(x['high']), low=float(x['low']), close=float(x['close']), volume=float(x.get('volume',0)))
                        await ingest(b)
                    except Exception:
                        continue
        except Exception:
            await asyncio.sleep(5)

@asynccontextmanager
async def lifespan(app):
    init_db(); task=asyncio.create_task(upstream_loop()); yield; task.cancel()

app=FastAPI(title='XAU AI Trader Backend',version='0.5.0',lifespan=lifespan)

@app.get('/health')
async def health(): return {'status':'ok','upstream_configured':bool(UPSTREAM_WS_URL),'symbol':UPSTREAM_SYMBOL}

@app.get('/bars')
async def bars(symbol:str='XAUUSD',timeframe:str=Query('M5',pattern='^(M1|M5|M15|H1|H4)$'),start:int=0,end:int=2147483647,limit:int=2000):
    return {'bars':load_bars(symbol,timeframe,start,end,min(limit,5000))}

@app.websocket('/ws/market')
async def market(ws:WebSocket):
    await ws.accept(); await hub.add(ws)
    try:
        await ws.send_text(json.dumps({'type':'connected','symbol':UPSTREAM_SYMBOL,'timeframes':list(TIMEFRAMES)}))
        while True:
            # Client messages are optional subscription filters; no trading commands exist.
            await ws.receive_text()
    except WebSocketDisconnect: hub.remove(ws)
    except Exception: hub.remove(ws)
