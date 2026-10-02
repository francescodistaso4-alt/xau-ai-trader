# XAU AI Trader V1.0

Applicazione Android signal-only per XAUUSD con analisi ICT/SMC, Entry/SL/TP, backtest, diario e notifiche.

**Importante:** l'app non apre, modifica o chiude operazioni.

## Struttura
- `app/` — Android Kotlin/Jetpack Compose
- `backend/` — FastAPI + SQLite + WebSocket
- `IctSmcEngine.kt` — motore deterministico
- `MultiTimeframeEngine.kt` — confluenza H4/H1/M15/M5
- `Backtester.kt` — backtest
- `SignalMonitorService.kt` — monitoraggio foreground e alert

## Avvio backend
```bash
cd backend
python -m venv .venv
# attivare venv
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

Il feed upstream deve essere adattato al contratto:
`symbol,time,open,high,low,close,volume`.

## Android
I default `10.0.2.2` funzionano con l'emulatore Android quando il backend gira sulla macchina host. Su un telefono reale usare l'indirizzo HTTPS/WSS pubblico del server.

Per Android 13+ le notifiche richiedono `POST_NOTIFICATIONS`; l'app lo richiede all'avvio.
