# wolfblack

**wolfblack — Android USDT-M Futures Trading Terminal**

A Persian-first Android application with a black-and-gold wolf identity, signal-focused native candlestick charts, multi-timeframe technical analysis, neural research panels, a $10 virtual paper ledger, and guarded XT USDT-M live-execution infrastructure.

## Product identity

- App label and product name: **wolfblack**
- Visual system: obsidian black, metallic gold, emerald profit accents, red risk accents, cinematic wolf crest.
- Charts: native Android candle renderer. Only directional signal symbols are charted; no TradingView embed.
- Android launcher name: wolfblack.

## Core features

- XT USDT-M perpetual market scanner and directional LONG/SHORT signal filtering.
- Native OHLCV candlestick charts with entry, stop-loss, TP1/TP2/TP3 levels.
- Technical chart interpretation including EMA, Bollinger Bands, RSI, MACD, ADX, ATR, volume, market structure, candle patterns, Supertrend, liquidity concepts and FVG where source data is available.
- Ten specialist analysis outputs and bundled neural ensemble research. Model opinions remain advisory unless independent validation gates permit their use.
- Persistent signal journal and a $10 virtual paper-trading ledger with estimated fees and funding.
- XT live execution path with explicit arming, credential checks, account checks, order preflight, exchange-side protection verification, emergency-stop behavior and profit-locking logic.

## Live trading safety

The existence of live-order code does not mean a user's XT account has been connected or tested. API credentials must be entered only inside the app. Use only the required futures read/trade permissions; keep withdrawals and transfers disabled. Do not share API secrets in chat.

The live path must remain fail-closed when connectivity, authentication, account state, market data, model validation, order parameters, or exchange-side TP/SL protection cannot be verified. A successful APK build is not proof of a successful live order or profitable trading.

Android background execution is subject to network availability, force-stop, battery optimization and operating-system limits; uninterrupted 24/7 execution cannot be guaranteed.

## Build

GitHub Actions workflow: **Build wolfblack APK**.

1. Open Actions.
2. Select the newest Build wolfblack APK run.
3. Wait for the build and tests to finish successfully.
4. Download the **wolfblack-1.0.0-APK** artifact and extract the APK.

A successful CI run verifies the checks performed by that workflow. It does not validate a live XT account, guarantee connectivity from every region, or establish profitability.
