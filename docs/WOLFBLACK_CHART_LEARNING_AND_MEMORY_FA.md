# WOLFBLACK — Chart Intelligence & Memory

## Chart curriculum
The chart-learning layer studies closed XT OHLCV candles, never partial candles as settled facts. It extracts explainable lessons from:
- Multi-period EMA alignment and market regime
- HH/HL and LH/LL structure, swing levels and range compression
- Candle anatomy (doji, pin bars, engulfing patterns)
- ATR/range volatility and relative volume
- Momentum and breakout context

The existing `ZoroAdvancedTechnicalEngine` remains the deeper chart-analysis engine and already covers RSI, MACD, ADX, ATR, Bollinger, VWAP, Stoch RSI, CCI, MFI, OBV, Supertrend, Ichimoku, Fibonacci, FVG, order blocks, liquidity sweeps, BOS/structure and chart patterns. The new curriculum layer creates compact, explainable learning records alongside it.

## Learning loop
1. Analyze only validated, timestamped OHLCV windows.
2. Persist chart lessons with symbol/timeframe and feature context.
3. When a signal resolves, append the realized outcome including fees, adverse/favorable excursion and horizon.
4. Use resolved records for walk-forward and symbol-holdout retraining/calibration.
5. Promote new model weights only after out-of-sample validation, drift checks and baseline comparison. No online weight mutation can authorize live orders.

## Memory status
`WolfChartLearningMemory` is a durable on-device journal (SharedPreferences) and survives app restarts. It stores recent chart lessons and resolved outcomes. It is not cloud storage.

## Cloud memory requirement — not yet connected
A real cloud memory needs a user-owned backend project, authenticated identity, TLS API, server-side access rules, per-user partitioning, retention/export/delete controls, and conflict/version handling. No Firebase/Supabase project credentials or backend endpoint currently exist in this repository. Do not silently upload private XT credentials, API keys, or account secrets. The app must show **Cloud: not connected** until a backend is configured and a round-trip sync is verified. Once provisioned, sync only the non-secret journal records, encrypt in transit, and retain the local journal as offline cache.
