# XAU AI Trader Backend V0.5

Backend signal-only: riceve un feed XAUUSD autorizzato, persiste OHLC, aggrega M1 in M5/M15/H1/H4 e inoltra le barre via WebSocket all'app.

## Avvio
```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# compilare URL/API key del provider autorizzato
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

## Contratto upstream
Il provider adapter deve produrre JSON con:
`symbol,time,open,high,low,close,volume` dove `time` è Unix seconds.

Il backend NON esegue ordini e non contiene credenziali broker.
