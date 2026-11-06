# CRYPTO AI PRO ULTIMATE

**Android app version: 2.3.0 (development)**  
A Persian-first, modular crypto-futures technical-analysis application. The Android client is built with Kotlin and Jetpack Compose; the Python package provides research, risk, and backtesting utilities.

> This is experimental trading software. Market signals are not investment advice and do not guarantee outcomes. Real-money order execution is not production-certified. Keep live trading disabled until the readiness gates in `docs/LIVE_TRADING_READINESS.md` are independently completed.

## Android application

The 2.2.0 interface focuses on live technical analysis and is organized into five primary sections:

- **خانه** — market overview, session context, scan counts, and leading candidates.
- **بازار** — XT Futures / Binance Futures selector, bullish/bearish filters, manual refresh, and ranked market cards.
- **تحلیل** — selected candidate, single-symbol TradingView chart, multi-indicator diagnostics, candlestick and harmonic-pattern recognition, persistent feedback learning, and Persian voice interaction.
- **حساب** — encrypted on-device XT credential storage and authenticated connection check.
- **تنظیمات** — scanner configuration summary. Trading and paper-autopilot controls are disabled in this analysis-focused build.

The visual system uses a dark navy base with blue and violet accents, a subtle geometric wolf watermark, and AMIR ARSALAN TRADER WOLF branding.

### Market analysis currently implemented

- XT USDT perpetual scanner: ranks up to 100 active USDT contracts by reported turnover and analyzes closed 15m, 1h, and 4h candles.
- Binance USDⓈ-M public market scanner.
- EMA 9/20/50/100/200, RSI, MACD, ATR, ADX, Bollinger context, VWAP, relative volume, stochastic RSI, market regime, candle pattern, and market-structure diagnostics.
- Reference entry, ATR-based stop, and three reference targets.
- Single-pair TradingView chart in the analysis screen. Only the currently selected signal's chart is displayed; TOTAL/TOTAL2/TOTAL3 panels are intentionally omitted from the user interface.
- A ten-voter **technical ensemble**. These are deterministic technical voting modules, not ten independent generative AI systems.
- Automatic refresh while the screen is open; user-started Android foreground monitoring can continue with a persistent notification. Android battery policies, force-stop, network loss, and OS restrictions can still interrupt it.

### Chart, learning, and voice behavior

The Android analysis screen embeds one TradingView chart for the currently selected signal only. TOTAL/TOTAL2/TOTAL3 charts are intentionally not displayed. TradingView is a visual chart surface; technical signals are calculated from exchange OHLCV candles, not scraped from TradingView.

The on-device learning component is a small online logistic model. It stores feature weights and reviewed-signal feedback in Android SharedPreferences, so its memory persists after the app closes. It updates only when the user reviews a signal and marks its direction correct or incorrect. It does not silently label future market outcomes. Its output is an experimental model-alignment score, not a proven win probability.

Voice interaction uses Android Persian speech recognition for microphone input and Android text-to-speech for short contextual answers. It currently answers common questions about the selected signal locally; it is not a general-purpose cloud AI conversation engine.

Candlestick heuristics include doji, hammer, shooting star, engulfing, morning/evening star, harami, piercing line, dark cloud, and possible tweezer-style levels. Harmonic heuristics check approximate Fibonacci ratios for Gartley, Bat, Butterfly, and Crab-like structures. All pattern detections require confirmation from market context and are not guaranteed signals.

### Analysis-only execution boundary

The Android app's trading tab is removed from the primary navigation, order actions are disabled, and background paper autopilot is forced off. The current Android build is for market scanning, chart review, and technical analysis only. Python research/backtesting utilities remain in the repository but are separate from Android order execution.

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

The simulator uses closed candles and conservative stop-first handling when stop and target are both touched in one candle. Funding is estimated; exchange-specific liquidation, historical funding, spread dynamics, and full market impact are not fully modeled. Use walk-forward and out-of-sample tests across multiple symbols.

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
