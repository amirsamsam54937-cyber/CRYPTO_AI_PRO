# CRYPTO AI PRO

CRYPTO AI PRO is a monitoring-first crypto futures research project. It is designed for observation, market analysis, and paper-trading development. **It does not place trades, connect to user accounts, or require exchange API keys.**

## Android monitoring app
The native Android app reads public Binance USDⓈ-M perpetual market data and:
- Finds active USDT-margined perpetual contracts
- Ranks markets by 24-hour quote volume and analyzes the top 30
- Calculates EMA 20/50, RSI 14, and MACD
- Confirms direction across 15-minute, 1-hour, and 4-hour candles
- Refreshes automatically every three minutes while the app is open
- Separates bullish bias, bearish bias, and neutral readings

These are technical screening signals, not trade instructions or probability forecasts. The app is read-only and has no order-placement code.

### Build APK
Every push to the Android source triggers the GitHub Actions workflow **Build Android APK**.
1. Open the repository's Actions tab.
2. Select the latest successful **Build Android APK** run.
3. Download the `CRYPTO-AI-PRO-debug-apk` artifact ZIP.
4. Extract and install `app-debug.apk` on Android. You may need to allow installation from your file manager.

Workflow: `.github/workflows/android-apk.yml`

## Python analysis API
The repository also contains a FastAPI research API with CCXT public market data, indicators, risk-sizing utilities, and tests.

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
PYTHONPATH=. uvicorn app.main:app --reload
```
Open http://127.0.0.1:8000 and API docs at /docs.

Copy `.env.example` to `.env` if you want to customize local defaults. Do not commit credentials, wallet keys, or seed phrases.

## Safety and limitations
- No live execution, exchange login, deposits, withdrawals, or account permissions.
- Public market-data availability depends on network and exchange regional restrictions.
- The Android app currently screens the 30 highest-volume eligible USDT perpetuals, not every listed contract.
- No background service or push alerts are enabled; automatic refresh runs while the app screen is active.
- Indicators can lag, fail, or disagree. No signal guarantees a market outcome.

Crypto derivatives are high risk. This software is experimental and is for monitoring and research only.
