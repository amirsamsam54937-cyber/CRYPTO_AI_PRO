# CRYPTO AI PRO — Target Architecture (XT USDT-M)

## Product principles

- Android is the control surface; the trading engine is a separately supervised service.
- Live trading is OFF by default and remains unavailable until every readiness gate passes.
- Public market data uses streaming; account/order state uses authenticated private streams plus REST reconciliation.
- Every order is attributable to a strategy run and has a durable client-generated idempotency key.
- No withdrawal permission is required or accepted.
- A UI toggle is never treated as proof that an exchange-side action succeeded.

## Runtime topology

1. **Android app (Kotlin + Jetpack Compose)**
   - Dashboard, live chart, watchlist/scanner, analysis detail, positions, orders, history, risk settings, connection and audit screens.
   - XT credentials can be entered in-app. The local credential vault uses an AES-256-GCM key held by Android Keystore. This protects data at rest on the device, but a compromised/rooted device can still expose credentials while the app is using them.
   - Do not put credentials in source code, APK resources, analytics, logs, crash reports, GitHub Actions artifacts, or URLs.
   - Prefer a dedicated authenticated backend for live trading. If direct device-to-exchange mode is offered, clearly label the device-availability risk: Android may suspend the app/network and cannot guarantee 24/7 execution.

2. **Market data service**
   - XT public WebSocket for trades, depth and candle updates; REST snapshots for bootstrap and recovery.
   - Maintain heartbeat, reconnect with exponential backoff+jitter, sequence/time validation, stale-feed detection and gap repair.
   - Use a normalized market-event schema so indicators do not depend on exchange payload formats.
   - Display feed age and connection health. A disconnected/stale feed blocks new entries.

3. **Research and signal engine**
   - Universe discovery from active XT USDT perpetual contracts; liquidity, spread, depth and contract-status filters.
   - Multi-timeframe analysis on completed candles; current candle may update the chart but cannot alone confirm a trade.
   - Feature pipeline, regime classifier, signal ensemble, explainability and confidence calibration.
   - No claim of ten independent AI models unless ten independently evaluated models are actually deployed. Use named, testable specialist modules and a calibrated meta-model.

4. **Risk and portfolio governor**
   - Risk sizing from stop distance, contract multiplier, tick/lot/min-notional rules and available margin.
   - Portfolio heat, correlated exposure, max concurrent positions, daily loss lockout, drawdown lockout, funding/spread/slippage limits.
   - Leverage is a margin setting, not a substitute for position sizing.
   - Hard limits are enforced server-side and cannot be bypassed by the UI.

5. **Execution service**
   - Explicit state machine: DISARMED → ARMING → ARMED → ENTRY_PENDING → POSITION_OPEN → EXIT_PENDING → FLAT; any uncertain state becomes RECONCILIATION_REQUIRED.
   - Pre-trade validation, exchange precision checks, leverage/margin verification, unique client order IDs, partial-fill handling and attached protective orders.
   - Verify SL/TP are accepted and visible at the exchange before considering a position protected.
   - Never blindly retry an order after a timeout. Query order/trade/position state first.
   - Durable event journal, restart recovery, rate-limit-aware backoff and circuit breakers.

6. **Kill switch semantics**
   - OFF immediately disarms new entries and cancels the bot's pending entry orders.
   - Then cancel/replace bot-managed conditional orders only as part of a verified close workflow; do not remove protective stops while exposure remains.
   - Close bot-owned positions by default, then poll/reconcile until confirmed flat.
   - Keep protective orders in place for any position that failed to close. Show a persistent warning and manual action path.
   - A separate, clearly labelled option may close all account positions, including manually opened ones, only after explicit confirmation.
   - Kill-switch request and final outcome are distinct states: STOP_REQUESTED, CLOSING, FLAT_CONFIRMED, or CLOSE_FAILED.

## Technical analysis feature set

### Trend and structure
- EMA/SMA 9, 20, 50, 100, 200; VWAP and anchored VWAP; ADX/DMI; Supertrend.
- Market structure: swing highs/lows, HH/HL/LH/LL, break of structure and change of character.
- Multi-timeframe alignment, trend persistence and slope/volatility normalization.

### Momentum
- RSI, Stochastic RSI, MACD histogram/signal, ROC, CCI, Williams %R, MFI, OBV and rate-of-change divergence.
- Divergence is a supporting feature, never a standalone entry.

### Volatility and risk geometry
- ATR, true-range percentile, Bollinger Bands/width, Keltner Channels, Donchian Channels, realized volatility.
- Volatility regime and stop-distance adequacy; reject trades whose stop is too tight for market noise.

### Volume, liquidity and derivatives
- Relative volume, volume profile/value area/POC where reliable, VWAP deviation, order-book imbalance and spread.
- Open interest change, funding rate, basis, liquidation/forced-order data where officially available.
- Filter thin books, abnormal spread, low depth and excessive estimated slippage.

### Price action and context
- Support/resistance zones, pivots, Fibonacci retracement as contextual confluence, candlestick patterns and gap/impulse detection.
- Session/time-of-day context and scheduled-event/news risk only when reliable timestamped feeds exist.

### Signal ensemble and validation
- Specialist votes: trend, momentum, volatility, volume/liquidity, market structure, derivatives, mean-reversion and regime.
- Weighted score calibrated on out-of-sample data; report evidence, conflicting signals and uncertainty.
- Walk-forward validation, purged/embargoed time-series splits, realistic fees/funding/slippage, Monte Carlo trade-order stress, and paper forward testing.
- Track expectancy, profit factor, max drawdown, Sharpe/Sortino, turnover, exposure, win/loss distribution and calibration drift.
- Detect data leakage, survivorship bias and overfitting; keep a champion/challenger model registry with rollback.

## Android UX

- Overview: equity/balance, unrealized PnL, realized PnL, margin ratio, daily risk budget, feed health and engine state.
- Live chart: candlesticks, volume, selectable 1m/5m/15m/1h/4h/1d, crosshair, zoom/pan, overlays and entry/SL/TP markers.
- Scanner: sortable opportunities with liquidity, spread, score, confidence, regime and reasons.
- Analysis: all indicator values, timeframe consensus, invalidation level and risk/reward.
- Trading controls: large ON/OFF control, clear armed/disarmed state, confirmation before arming, separate emergency close workflow and visible close confirmation.
- Settings: max risk/trade, daily loss, max positions, portfolio heat, leverage ceiling, allowed symbols, timeframes and slippage limits.
- Account: API connection test, masked key status, permission warning, balances, positions, open orders and audit trail.
- Accessibility: Persian RTL, readable numeric formatting, color plus text status, confirmation dialogs and no color-only meaning.

## Delivery gates (must pass in order)

1. Live public market feed and chart; no credentials needed.
2. Secure credential entry and read-only XT account verification.
3. Scanner/analysis correctness and unit/integration tests.
4. Paper execution with fees, funding and slippage.
5. Private order/account stream and REST reconciliation.
6. Order lifecycle, partial fills, protective-order verification and restart recovery in test environment.
7. Kill-switch tests, stale-feed tests, rate-limit tests and fault injection.
8. Manual supervised pilot with tiny risk and explicit user confirmation.
9. Live automation only after all prior gates pass and a separate explicit enable acknowledgement.

## Current repository gap

The existing Android app is a periodic Binance scanner, not yet an XT live-chart or live execution client. The Python service has XT account-read methods and a guarded order helper, but lacks the complete execution orchestrator, durable journal, position reconciliation and verified kill-switch workflow. The current code must not present live automation as operational until those components exist.
