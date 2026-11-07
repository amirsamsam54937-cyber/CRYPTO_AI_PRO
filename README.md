# AMIR ARSALAN TRADER WOLF — CRYPTO AI PRO

**Android release line: 2.13.0**  
A Persian-first Android application for live Binance USDⓈ-M perpetual-futures market scanning, explainable technical analysis, signal visualization, and persistent outcome-based learning.

> **Analysis and signals only.** The Android app does not connect exchange accounts, store API keys, submit orders, change leverage, or execute trades. Entry, stop and target levels are analytical references, not orders or guarantees.

## Current scope and hard boundaries

- Exchange: **Binance USDⓈ-M public market data only**. XT integration, XT credentials, and account endpoints are removed from the active application and Python research API.
- Universe: active USDT-quoted perpetual contracts, ranked by 24-hour quote turnover.
- No account connection, no API secrets, no withdrawal permissions, and no automated/manual order placement.
- Signal output: LONG bias, SHORT bias, or NO TRADE/neutral, with reference entry, stop-loss, TP1–TP3 and a conservative leverage ceiling estimate.
- A suggested leverage number is informational only. It is not applied to an exchange account.

## Android modules

- **Home / command center:** scan status, scan coverage, update time, market sessions and leading signals.
- **Market scanner:** every active Binance USDT perpetual contract, volume-ranked results, progressive batch updates, directional filters and manual rescan. The foreground scanner repeats indefinitely: after each full pass it waits three minutes and starts another pass; there is no contract quota or scan-session expiry.
- **Technical analysis:** selected-symbol TradingView chart, local candlestick chart, entry/SL/TP level map and detailed technical snapshot.
- **Wolf AI:** ten explainable technical voting specialists, a small experimental on-device neural network, local knowledge retrieval and outcome journal.
- **Persian voice:** Android speech recognition (fa-IR) and text-to-speech for contextual questions about the selected signal.
- **Background monitoring:** user-started Android foreground service with a persistent notification and an endless scan/retry cycle. Use Settings → Start background monitoring before leaving the app. Android force-stop, reboot, battery policies, network/API blocks and OS limits can interrupt it; no Android app can guarantee uninterrupted 24/7 operation.

## Analysis engine

The Android scanner fetches closed candles for 15m, 1h and 4h, then evaluates:

- EMA 9/20/50/100/200, RSI, MACD, ADX and ATR
- Bollinger Bands and bandwidth, VWAP, relative volume and Stochastic RSI
- Candle patterns: Doji, Hammer, Shooting Star, Engulfing, Morning/Evening Star, Harami, Piercing Line, Dark Cloud and approximate Tweezer patterns
- Approximate harmonic structures: Gartley, Bat, Butterfly and Crab
- Swing support/resistance, HH/HL and LH/LL market-structure context
- Multi-timeframe agreement and filters for overextension, extreme volatility and higher-timeframe conflict
- Reference levels based on ATR and nearby structure

The ten specialists are deterministic technical rule modules, **not ten independent pretrained generative AI models**. The on-device neural network is a small experimental feed-forward network, not a pretrained foundation model. It is updated from subsequently observed signal outcomes and persists locally. Its output is not a calibrated probability of profit. The app should preserve neutral/NO TRADE decisions when evidence conflicts or data quality is inadequate.

## Market context and learning

The app can collect public Binance aggregate trades, open interest and funding context for a small number of leading candidates, and retrieve public crypto news feeds. These are contextual inputs, not proof of whale intent or a reliable sentiment label. Source, freshness, failures and missing data should remain visible.

The permanent on-device signal notebook stores up to 5,000 recent directional signal records, including timestamp, direction, reference entry, stop, three targets, confidence, rationale and observed status. New same-direction signals are deduplicated for 15 minutes. It observes later public candle prices. It does not submit or simulate real exchange orders. Same-candle stop/target ambiguity is handled conservatively. Learning labels are approximate horizon outcomes and must not be described as verified trade fills.

## Persian knowledge library

- Android contains a compact, curated Persian knowledge base used by the in-app knowledge assistant.
- The expanded reference document is knowledge/PERSIAN_CRYPTO_FUTURES_KNOWLEDGE_V2.md.
- Topics include futures mechanics, market structure, indicators, candlesticks, volume/order flow, risk, backtesting, machine learning, psychology, API safety and data quality.
- This is an extensible reference library, not one million independently verified facts and not a pretrained LLM. Future records should carry sources, dates, scope and confidence.

## Python research utilities

The Python package provides public-market research, indicator analysis and historical backtesting. It is separate from Android and does not place exchange orders.

    python -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt
    PYTHONPATH=. uvicorn app.main:app --reload

The default CCXT market adapter is Binance USDⓈ-M (binanceusdm). No API credentials are needed for public market data. A regional HTTP 451 response is an access-eligibility denial: the app stops polling instead of rotating hostnames or trying to evade the restriction. Use only market-data services whose terms permit access from your network location.

## Build and install Android APK

Every push affecting Android source starts the **Build Android APK** GitHub Actions workflow.

1. Open the repository's **Actions** tab.
2. Open the newest **Build Android APK** run.
3. Wait for all jobs to finish successfully.
4. Download the **AMIR-ARSALAN-TRADER-WOLF-pretrained** artifact ZIP.
5. Extract and install app-debug.apk on Android.

A successful CI build verifies compilation only. It does not prove API availability in every region, signal quality, profitability, background reliability or production readiness. The APK size must be measured from the generated artifact; no artificial padding is added to meet a file-size target.

## Quality and safety roadmap

Before describing the app as production-ready, verify:

1. Scanner coverage, latency, per-symbol errors, HTTP status and rate-limit behavior on real Android devices and networks.
2. Closed-candle alignment and correct resampling/time-zone behavior.
3. Indicator calculations against independently verified reference datasets.
4. BOS/CHOCH, liquidity sweeps, divergence and harmonic pattern definitions with deterministic tests.
5. Walk-forward and out-of-sample evaluation with fees, funding, spread, slippage, delisted symbols and survivorship-bias controls.
6. Neural-network chronological purged holdout, majority-class baseline, balanced accuracy, log-loss, Brier score, ROC-AUC, calibration, sample sufficiency, drift monitoring and rollback.
7. Android UI navigation, voice permissions, foreground-service behavior, persistence and battery restrictions.
8. Data freshness, error visibility, safe failure behavior and explicit NO TRADE handling.

Never expose secrets in source code, logs, screenshots or public repository commits. This app's analysis-only design does not require exchange API keys.
