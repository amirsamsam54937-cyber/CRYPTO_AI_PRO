# AMIRARSALAN TRADER WOLF

**Android app version: 2.1.0**  
A Persian-first, modular crypto-futures market intelligence and paper-trading application. The Android client is built with Kotlin and Jetpack Compose; the Python package provides research, risk, and backtesting utilities.

> This is experimental trading software. Market signals are not investment advice and do not guarantee outcomes. Real-money order execution is not production-certified. Keep live trading disabled until the readiness gates in `docs/LIVE_TRADING_READINESS.md` are independently completed.

## Android application

The 2.1.0 interface is organized into six functional sections:

- **خانه** — market overview, session context, scan counts, and leading candidates.
- **بازار** — XT Futures / Binance Futures selector, bullish/bearish filters, manual refresh, and ranked market cards.
- **تحلیل** — selected candidate, multi-indicator diagnostics, market structure, support/resistance, and candlestick view.
- **معاملات** — autonomous paper-trading dashboard, persistent trade journal, risk limits, staged exits, trailing stops, and individually confirmed XT manual order ticket.
- **حساب** — encrypted on-device XT credential storage, authenticated connection check, and a manual USDT futures balance readout that clearly reports an empty wallet.
- **تنظیمات** — user-started foreground monitoring service and scanner configuration summary.

The visual system uses a dark navy base with electric blue and violet accents. Version 2.1.0 brands the app as **AMIRARSALAN TRADER WOLF**, with a custom angular wolf-head launcher emblem and an enhanced chart view that overlays visible support/resistance plus reference entry, stop-loss, and first target levels on the latest 54 closed 15-minute candles.

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
- A new CCXT-based read-only XT account dashboard loads markets, USDT futures balance, non-zero positions, and open orders; an empty wallet is reported as zero balance rather than a connection failure.
- The new API route is `GET /api/xt/ccxt/dashboard`; configure `XT_API_KEY` and `XT_API_SECRET` only as server environment secrets. The provided code snippet contains variable placeholders, not actual credentials.
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

## Trading modes

- Live market analysis/signals: public XT/Binance market data; no account funding required.
- Paper autopilot: automatic simulated entries/exits, local journal, risk caps and on-device online learning from resolved paper outcomes.
- Live manual order: separately armed and requires a final in-app confirmation.
- Unattended live auto: intentionally locked in Android until execution reconciliation, exchange-side protective-order verification, restart recovery, duplicate-order prevention, and fault-injection tests are complete.

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


## Explainable online learning (paper outcomes only)

The Android paper engine now keeps a small logistic model locally on-device. It stores a feature snapshot at simulated entry and updates weights only after the complete simulated position closes, using net realized PnL after modeled fees/slippage. The paper dashboard shows the number of learning updates. This is online adaptation, not a large language model, not an independently validated AI ensemble, and not a guarantee of predictive edge.

The research API now includes a persistent SQLite logistic learner. It learns only after a user submits a resolved paper-trade outcome; it never labels an open trade or treats an unrealized signal as a win/loss.

- `GET /api/learning/status` — update count, feature weights and calibration status.
- `POST /api/learning/feedback` — submit one resolved paper outcome.
- `GET /api/analysis/advanced/{symbol}?timeframe=1h` — technical feature pack plus an adaptive research estimate.

Example feedback body:

```json
{
  "symbol": "BTC/USDT:USDT",
  "direction": "LONG",
  "outcome": 1,
  "metrics": {
    "score": 4,
    "rsi": 64,
    "macd_histogram": 0.12,
    "adx": 28,
    "volume_ratio": 1.4,
    "bb_position": 0.7
  }
}
```

The estimate is explicitly marked uncalibrated until 100 resolved paper outcomes. It is a research feature, not a probability guarantee or a live-order authorization. Local SQLite state is excluded from Git.
