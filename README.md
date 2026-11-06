# CRYPTO AI PRO ULTIMATE

**Android app version: 2.0.0**  
A Persian-first, modular crypto-futures market intelligence and paper-trading application. The Android client is built with Kotlin and Jetpack Compose; the Python package provides research, risk, and backtesting utilities.

> This is experimental trading software. Market signals are not investment advice and do not guarantee outcomes. Real-money order execution is not production-certified. Keep live trading disabled until the readiness gates in `docs/LIVE_TRADING_READINESS.md` are independently completed.

## Android application

The 2.0.0 interface is organized into six functional sections:

- **خانه** — market overview, session context, scan counts, and leading candidates.
- **بازار** — XT Futures / Binance Futures selector, bullish/bearish filters, manual refresh, and ranked market cards.
- **تحلیل** — selected candidate, multi-indicator diagnostics, market structure, support/resistance, and candlestick view.
- **معاملات** — autonomous paper-trading dashboard, persistent trade journal, risk limits, staged exits, trailing stops, and individually confirmed XT manual order ticket.
- **حساب** — encrypted on-device XT credential storage and authenticated connection check.
- **تنظیمات** — user-started foreground monitoring service and scanner configuration summary.

The visual system uses a dark navy base with blue and violet accents. The launcher icon has been refreshed for the ULTIMATE identity.

### Market analysis currently implemented

- XT USDT perpetual scanner: ranks up to 100 active USDT contracts by reported turnover and analyzes closed 15m, 1h, and 4h candles.
- Binance USDⓈ-M public market scanner.
- EMA 9/20/50/100/200, RSI, MACD, ATR, ADX, Bollinger context, VWAP, relative volume, stochastic RSI, market regime, candle pattern, and market-structure diagnostics.
- Reference entry, ATR-based stop, and three reference targets.
- A ten-voter **technical ensemble**. These are deterministic technical voting modules, not ten independent generative AI systems.
- Automatic refresh while the screen is open; user-started Android foreground monitoring can continue with a persistent notification. Android battery policies, force-stop, network loss, and OS restrictions can still interrupt it.

### Exchange connectivity and execution boundaries

- Public market scanning does not require API keys.
- XT credentials entered in the Android app are stored using Android Keystore-backed encryption.
- XT authenticated connection verification is implemented.
- The Android code contains a manually confirmed XT market-order path that requests leverage and attaches stop/first-target trigger fields. **This route has not been validated end-to-end against a real account and must not be considered production-safe.**
- Live order controls are off by default and require a saved XT credential, a successful account check, an explicit arming action, and a separate per-order confirmation.
- Automatic live trading is not enabled. The new autonomous mode is paper-only and has no code path to XT/Binance order endpoints.
- Binance scanning is public-data-only in this Android release; private Binance account connection and execution are not implemented.
- Never grant withdrawal or transfer permissions. Any credential previously pasted into chat or source control must be revoked and replaced. Do not commit secrets or place them in APK assets.

### Background monitoring

Use **تنظیمات → شروع پایش پس‌زمینه**. Android displays an ongoing notification while the foreground service runs. On Samsung devices, set the app battery mode to unrestricted if background monitoring is being interrupted. This is best-effort Android background execution, not a guarantee of 24/7 availability.

## Build and install the Android APK

Every push affecting Android source triggers the **Build Android APK** GitHub Actions workflow.

1. Open the repository's Actions tab.
2. Open the latest successful **Build Android APK** run.
3. Download the `CRYPTO-AI-PRO-debug-apk` artifact ZIP.
4. Extract it and install `app-debug.apk` on Android.

The debug APK is for testing. A successful CI build proves compilation, not exchange correctness, profitability, or production readiness.

## Python research API

Install dependencies and run locally:

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
PYTHONPATH=. uvicorn app.main:app --reload
```

API documentation is available at `http://127.0.0.1:8000/docs`.

The Python package includes:
- XT public USDT perpetual scanner.
- XT authenticated read adapter and account/balance endpoints.
- Explainable multi-factor technical analysis.
- Risk-sizing utilities with conservative caps.
- Paper-trading simulator.
- Historical OHLCV backtester with estimated fees, slippage, and funding assumptions.

## Historical backtesting

Open the [Google Colab backtest notebook](https://colab.research.google.com/github/amirsamsam54937-cyber/CRYPTO_AI_PRO/blob/main/notebooks/CRYPTO_AI_PRO_Backtest.ipynb) or run:

```bash
python tools/backtest.py --symbol BTCUSDT --days 90 --leverage 3 --risk 0.01
```

The simulator uses closed candles and conservative stop-first handling when stop and target are both touched in one candle. The Android paper autopilot persists its positions and journal across service restarts, but its fills are approximations using polling prices, not exchange-grade tick execution. Funding is estimated; exchange-specific liquidation, historical funding, spread dynamics, and full market impact are not fully modeled. Use walk-forward and out-of-sample tests across multiple symbols.

## Engineering gates before any live automation

The following are still required before unattended live trading can be considered:

1. Validate market-data freshness, reconnect behavior, rate limits, and symbol metadata.
2. Validate XT contract size, quantity step, minimum notional, price tick, and position mode for every symbol.
3. Reconcile balances, positions, leverage, and open orders before and after every execution event.
4. Implement a durable execution journal and idempotent order lifecycle.
5. Verify exchange-side stop-loss and take-profit acceptance and remaining protected quantity after partial fills.
6. Implement restart recovery, stale-feed circuit breakers, duplicate-order protection, and a tested kill switch.
7. Add private account/order streams or reliable REST reconciliation.
8. Complete sandbox/testnet and fault-injection testing before a supervised tiny-risk pilot.

See:
- [Target architecture](docs/ARCHITECTURE_V2.md)
- [Live-trading readiness checklist](docs/LIVE_TRADING_READINESS.md)
- [Persian ULTIMATE product specification](docs/ULTIMATE_PRODUCT_SPEC_FA.md)

## Tests

```bash
pytest -q
```

The tests cover indicator calculations, analysis, backtesting, paper trading, XT request signing, scanner behavior, and live-execution safety gates. Passing tests do not replace real exchange integration testing.
