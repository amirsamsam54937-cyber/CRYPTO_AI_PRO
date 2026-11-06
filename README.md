# CRYPTO AI PRO

A modular crypto-market research and paper-trading platform. This foundation prioritizes public market data, transparent signals, portfolio risk controls, and simulation. **Live order execution is disabled** until the system has comprehensive tests and a separate explicit activation process.

## Included
- FastAPI service and interactive Persian dashboard
- CCXT public market-data adapter (no API key needed for public data)
- RSI, MACD, EMA and ATR indicators
- Conservative position sizing and portfolio risk limits
- Unit tests and Docker support

## Run locally
```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```
Open http://127.0.0.1:8000 and API docs at /docs.

Copy `.env.example` to `.env`. Never commit exchange secrets, wallet keys, or seed phrases.

## Safety
Signals are research outputs, not guarantees or financial advice. Begin with simulation. This repository currently has no endpoint that places real orders.

## Roadmap
1. Validate market-data quality and indicator calculations.
2. Add persistent paper portfolio, trade journal, replay and backtesting.
3. Add multi-timeframe confirmation and strategy scoring.
4. Build Android packaging and secure settings.
5. Consider live execution only after independent testing and explicit risk review.

Crypto derivatives are highly risky; leverage can cause rapid losses. This software is experimental.
