# HOPE HUNTER — Master Product Requirements

Consolidated from the user's project conversations through 2026-11-12. See `USER_REQUIREMENTS_DECODED_FA.md` for the complete Persian requirement map and superseded-request decisions. This is a requirements baseline, not a claim that every item is already implemented.

## 1. Current scope and hard exclusions
- Android app for crypto futures technical analysis and signals only.
- The older autonomous-trader request is superseded. No account login, API keys, order placement, automatic trading, position management, or manual trading terminal.
- Read-only public market data from XT USDⓈ-M futures and XT USDT perpetuals.
- Signal quality over quantity. NO TRADE is a correct result when evidence is insufficient.
- Technical and neural engines must be separate. Live LONG/SHORT requires both independently voting the same direction plus every quality/risk gate passing. Disagreement, neutral/unavailable vote, stale data, or unvalidated model => NO TRADE.
- Never describe confidence/consensus as calibrated win probability without calibration evidence. Never promise profit.

## 2. Branding and UX
- Name: HOPE HUNTER; Persian title: «اتاق شکار امیدها».
- Premium obsidian/black, warm ivory, champagne/gold palette. No navy or purple as dominant colors.
- Retain approved gold logo. The older wolf wallpaper/icon concept was superseded by the latest gold HOPE HUNTER identity; do not restore unless explicitly requested.
- Creator credit exactly: «سازنده: amir arsalan samsami».
- Persian RTL, legible numbers, polished responsive cards, loading/empty/error states, accessible controls.
- Main areas: Home, Market, Signal Strength/Analysis, Journal, Settings.
- Stability on Samsung Android 13 / 8 GB RAM: no startup crash, blank screen, immediate exit, main-thread network work, or unbounded background tasks.
- Historical desire for a substantial APK must not be met by artificial size inflation or unused assets.

## 3. Market coverage and data
- Discover broad available XT USDⓈ-M USDT perpetual and XT USDT perpetual universes; no silent tiny hardcoded watchlist.
- Prioritize by liquidity/volume/data quality and report eligible, scanned, successful, skipped and failed counts.
- Respect rate limits and exchange terms. Never bypass WAF, 403/451, regional restrictions or access controls. Bounded concurrency, timeout, cancellation, sensible backoff for 429/transient errors.
- HTML/WAF responses are errors, never JSON/candles. Validate OHLCV, timestamps, duplicates, gaps, freshness and history length.
- Use closed candles only; normalize symbols/exchange explicitly; show source and freshness.
- Distinguish cached/stale data from fresh data.
- Preserve historical data provenance. Verified real exchange data must never be silently mixed with synthetic/demo data.

## 4. Technical-analysis requirements
- Multi-timeframe analysis and dynamic trade-timeframe selection based on structure/volatility; display selected timeframe on every signal.
- Trend: EMA 9/20/50/100/200, HH/HL and LH/LL, BOS, CHOCH.
- Momentum: RSI, Stochastic RSI, MACD, CCI, ROC.
- Strength/regime: ADX, Supertrend, ATR, EWMA volatility and regime detection.
- Volume/flow: relative volume, VWAP, MFI, OBV.
- Range/volatility: Bollinger Bands/width, compression and expansion.
- Price action: candle patterns, support/resistance, swing highs/lows, breakout/retest, pullback/rejection.
- Liquidity sweep, likely liquidation zones, stop runs and fake breakouts only when supported by real available data. Never claim order-book depth or exchange liquidation maps unless connected.
- FVG and RSI divergence with explicit limitations; no fabricated detections.
- Multi-timeframe conflicts, late/chasing entries, false breakouts and pullback/retest opportunities.
- Explain evidence and contradictions. No single indicator may force a trade.

## 5. Signal format and risk
Every signal: exchange, symbol, LONG/SHORT/NO TRADE, tier/type 1/2/3, dynamic timeframe, entry, structural SL, staged TP1/TP2/TP3, adaptive leverage, risk/reward, score, timestamp/freshness, separate technical and neural directions/scores/status, agreement status, supporting reasons and vetoes.
- User specifically requested types 1/2/3, not arbitrary labels.
- SL, TP1, leverage and risk sizing must adapt to ATR, volatility, structure, liquidity and timeframe; no universal fixed settings.
- The user's later explicit request for multiple targets supersedes the older TP1-only wording: show distinct TP1/TP2/TP3 where supported by structure and risk. Never fabricate or collapse targets into duplicate prices.
- Reject invalid/unrealistic setups; never invent levels when data is missing.
- Conservative leverage based on stop distance/volatility; leverage does not make a weak setup better.
- Missing/invalid stop, non-positive R:R, stale candles, poor liquidity, extreme volatility, structural break or major higher-timeframe conflict => veto/downgrade.
- NO TRADE is correct behavior.

## 6. AI / neural intelligence
- Technical and neural outputs separate and visible.
- User requested ten AI opinions/models plus scoring/ranking. Current code has ten explainable rule-based specialist votes and a separate ten-member MLP ensemble. These are not ten independent general-purpose LLMs; describe accurately.
- Training/inference feature definitions and timeframe must match.
- Use real timestamped, sourced/versioned data; chronological purged holdout and walk-forward validation; prevent leakage, duplicate symbol-time samples and random time shuffling.
- Report balanced/ordinary accuracy against majority baseline, log loss against prevalence baseline, Brier, ROC-AUC, calibration, precision/recall and coverage.
- Only validated models may affect live signals. Failed validation => trained-but-not-validated, abstain. Never weaken gates to force pass. Random weights never vote live.
- User wants automatic continuous learning. Make learning auditable and safe: learn only from fully reviewed outcomes and verified data; version datasets/models; validate before promotion; preserve rollback and kill switch; never silently mutate production weights or learn from ambiguous outcomes.
- Monitor drift and suspend neural voting on degradation.
- Research backtests may inspect independent neural predictions even while live gate is closed; research cannot bypass live gate.

## 7. Backtest and Journal
- Journal starts with concise historical backtest summary and then detailed comparisons.
- Independently compare technical-only, neural-only, and strict agreement portfolios on identical symbols/timestamps/data availability/entry-exit rules/costs.
- User's requested backtest report refresh interval is **2 minutes**; this is reporting cadence, not trading timeframe.
- Metrics: trades, wins/losses, win rate, gross/net return, fees, slippage, profit factor, max drawdown, average win/loss, expectancy, exposure, period.
- Model fees and realistic slippage; disclose funding if omitted. Conservative intrabar stop/target collision policy; no impossible fills.
- No look-ahead: decision uses only then-available candles; enter no earlier than next eligible candle; outcome uses subsequent bars.
- Chronological train/validation/test and walk-forward; no final holdout tuning.
- Show sample size. Do not declare a winner/reliable strategy below 30 trades per engine; 30 is only a reporting floor, not proof.
- Benchmark/baseline and uncertainty intervals where feasible.
- Separate seed-data, fresh-exchange-data, paper/live outcome tracking and synthetic/demo.
- Immutable signal history; do not rewrite old results after model changes.
- Show missing data, failed sources, sample limitations and validation status. Failed fetch must not silently become a zero-trade result.

## 8. News, whale and contextual feeds
- News/sentiment, whale activity, funding, open interest, liquidation and order-book data are modifiers, not dominant direction sources.
- Label every feed connected/unavailable/stale/estimated.
- Never fabricate whale trades, liquidation levels, news sentiment, funding or OI.
- If unavailable, continue technical analysis with visible limitation.

## 9. Learning, audit and reliability
- Durable journal of signal, model outputs, reasons, data timestamps, outcome and reviewed corrections.
- Analyze false positives/negatives, missed trades, stop-outs, TP1 hits and regime-specific results.
- Learn only after outcomes are observable and costs accounted for.
- Separate online adaptation from offline retraining; offline retraining reproducible, versioned and validated before deployment.
- Explain rejected signals and neural abstentions; retain audit logs and inspectable model/dataset/report versions.
- Read-only app; no exchange credentials.
- Bound network work, memory, history, caches and coroutines; cancel superseded scans.
- Graceful handling of network/WAF/rate-limit/empty/malformed data; safe persistence; crash diagnostics without secrets.
- Automated tests for indicators, parsing, consensus, risk, signal gate, backtest accounting and training validation.
- Verify APK ZIP, package/version and model/report assets. CI build is not physical-device test.

## 10. Source audit snapshot at consolidation
Observed in source:
- Compose Android app; XT/XT read-only scanners; multi-timeframe technical snapshots; ten rule-based consensus specialists; separate ten-member pretrained MLP; quality, regime, adversarial, forecast, math and time-series review engines; outcome journal, learning-memory components and historical backtest engine.
- Strict live neural validation gate exists; unvalidated models abstain.
- Current signal construction computes staged TP1/TP2/TP3 (nominally 1R/2R/3R with limited structural adjustment); validate levels against real structure and outcome data.
- XT discovers broad perpetual universe. XT universe completeness still needs audit.
- Incomplete/unproven: 2-minute backtest refresh cadence; fully fair independent three-way backtest; walk-forward retraining at each historical decision; complete fees/funding/slippage/intrabar simulation; safe automatic retraining/promotion; full news/whale/funding/OI integrations; physical-device stability.
- User-reported issue: XT WAF/HTTP 403 and intermittent XT/XT failures. Do not claim fixed until verified with real responses.
- 2.44.0 introduced reproducible evenly spaced historical samples, expanded research sampling, independent neural research vote and minimum-trade comparison guard. 2.45.0 is the XT-only migration; build and behavior still need verification.

## 11. Latest user corrections
- Multi-stage TP1/TP2/TP3 is the current requirement; it supersedes the older TP1-only requirement.
- User asks for a chart automatically analyzed and annotated by the system; no manual drawing tools are required.
- Raw news and whale/order-flow panels must remain hidden; only verified influence may affect analysis.
- The latest product is analysis/signals only; historical auto-trading and account-connection requests are superseded.

## 12. Release acceptance criteria
1. Automated tests pass.
2. APK integrity/package/version verified.
3. Startup, navigation, Market, Journal and Settings regression checked.
4. Data freshness, error handling and scan counts verified.
5. Tests prove disagreement/unvalidated neural model => NO TRADE.
6. Three backtests use identical windows and cost assumptions and disclose limitations.
7. Model report bundled and validation status visible.
8. No account/order/trading endpoints or API-key collection.
9. Share APK only after download and integrity verification.
10. State honestly whether physical-device installation was tested.

This document is the consolidated product contract. Do not mark unfinished items complete without source inspection and tests. Preserve the hard exclusions in section 1 in every release.
