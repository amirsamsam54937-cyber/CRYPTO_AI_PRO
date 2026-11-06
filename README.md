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
- Shows a reference entry, ATR-based stop-loss, and three take-profit levels (1R / 2R / 3R)
- Displays a conservative leverage ceiling estimate (1x–5x) derived from stop distance; it is not an instruction to use that leverage
- Uses 15m for the setup with 1h and 4h trend confirmation

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

## Historical backtesting

A standalone multi-timeframe historical backtest is available at `tools/backtest.py`. For an easier Google Colab workflow, open [CRYPTO AI PRO Backtest Notebook](https://colab.research.google.com/github/amirsamsam54937-cyber/CRYPTO_AI_PRO/blob/main/notebooks/CRYPTO_AI_PRO_Backtest.ipynb). It downloads public Binance USD-M Futures candles and exports a trade-by-trade CSV. It uses closed candles, enters on the next 15m candle, includes estimated taker fees and configurable slippage, and assumes the stop is hit first when stop and target are both touched inside one candle.

```bash
python tools/backtest.py --symbol BTCUSDT --days 90 --leverage 3 --risk 0.01
```

Options include `--symbol`, `--days`, `--leverage`, `--risk`, `--fee`, `--slippage-bps`, and `--out`. The default output is `backtest_trades.csv`. This is a research baseline, not a fully exchange-faithful simulator: funding, liquidation mechanics, spread dynamics, and full market impact are not modeled. Test several symbols and out-of-sample periods before drawing conclusions.

## Safety and limitations
- No live execution, exchange login, deposits, withdrawals, or account permissions.
- Public market-data availability depends on network and exchange regional restrictions.
- The Android app currently screens the 30 highest-volume eligible USDT perpetuals, not every listed contract.
- No background service or push alerts are enabled; automatic refresh runs while the app screen is active.
- Entry and target levels are indicative calculations based on ATR, not guaranteed fills. Leverage display is a ceiling estimate, not a personal recommendation.
- Indicators can lag, fail, or disagree. No signal guarantees a market outcome.

Crypto derivatives are high risk. This software is experimental and is for monitoring and research only.


## XT USDT-M Futures account connection (read-only foundation)

The project now includes a signed XT USDT-M account adapter. It can authenticate
against XT, read futures account metadata, and request the futures balance list.
It does **not** submit, cancel, or modify orders and it does not move funds.

Configure `XT_API_KEY` and `XT_SECRET_KEY` as private environment variables on
the machine hosting the FastAPI service. Never put real credentials in this
repository, Android source code, APK resources, notebooks, or chat messages.
The old credentials previously shared in chat should be revoked and replaced.

Available endpoints:
- `GET /api/xt/connection` — authenticated account connection check
- `GET /api/xt/dashboard` — account metadata plus balances when XT returns an account ID
- `GET /api/xt/balances?account_id=...` — futures balance list

This is a first integration milestone, not a live trading bot. The Android APK
does not yet connect to XT, and no live order execution is enabled. Verify
authentication and account responses against a small test environment before
building further execution functionality. XT USDT-M API base URL and signing
rules are documented at [XT Futures API](https://doc.xt.com/docs/futures/Access%20Description/BasicInformationOfTheInterface)
and [XT Signature Generation](https://doc.xt.com/docs/futures/Access%20Description/SignatureGeneration).
