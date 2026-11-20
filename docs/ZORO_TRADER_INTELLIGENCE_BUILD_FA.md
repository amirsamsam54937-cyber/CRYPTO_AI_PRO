# ZORO Trader Intelligence — Build Notes (FA)

## What was added

A research-only Kotlin ledger at:
`app/src/main/java/com/cryptoaipro/monitor/ZoroTraderEvidenceLedger.kt`

Unit tests:
`app/src/test/java/com/cryptoaipro/monitor/ZoroTraderEvidenceLedgerTest.kt`

## What it does

- Stores each public call as an immutable observation with source URL and publication/capture timestamps.
- Records instrument, venue, timeframe, side, entry, stop, target, lifecycle status and evidence grade.
- Audits malformed URLs, missing timestamps, reversed chronology, invalid numeric values and closed calls without a measurable result.
- Calculates win rate, expectancy in R, profit factor, maximum peak-to-trough drawdown in R and missing-outcome count.
- Keeps unknown outcomes out of win/loss calculations instead of silently counting them as zero.
- Applies a minimum 30-resolved-call screening floor. Passing it only produces a review label; it does not validate profitability or authorize live trading.

## Evidence grades

- A: independently verifiable complete account/order history.
- B: continuous recording with timestamped order details and enough context to reconstruct execution.
- C: public call, screenshot or partial recording.
- D: verbal, promotional or otherwise uncorroborated claim.

A public-call ledger is not an account equity curve. Never label a call-tracker result as verified account PnL.

## Current research priorities

1. TRH / Khosro: collect complete pre-entry scenarios, stop events, target events and recaps; test whether the public structure → displacement → break → origin zone → retest → reaction sequence is consistently observable.
2. Beny Trade: compare higher-timeframe BTC bias and breakout/retest calls against all outcomes, not only target screenshots.
3. Daan Crypto Trades: test range-first conditional levels and separate long-term commentary from active trade calls.
4. Linda Bradford Raschke: encode preparation, selectivity, limited pattern set and post-trade review as process controls, not price predictors.
5. Expand the global watchlist only when a direct official cross-link and a traceable public sample exist.

## Metrics and accounting rules

- R is normalized by the initial planned risk, not by leverage or notional.
- A result is called net R only when fees, spread, slippage and funding are included or conservatively estimated and clearly labeled.
- Do not fabricate missing entry/stop/result fields.
- Include cancelled, invalidated, no-fill and still-open calls in completeness reporting even though they are not resolved outcomes.
- Report the observation window and sample denominator alongside every percentage.
- Compute drawdown on the chronological sequence of resolved R values; do not reorder trades to improve the curve.

## Safety boundary

This addition has no exchange API access, no credential handling, no signal generation and no order-execution authority. It cannot activate XT live trading or modify risk limits. The new tests must pass in CI before this code is considered build-verified. No APK was produced by this change.
