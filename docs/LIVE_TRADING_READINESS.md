# Live-trading readiness and security gate

## Credential incident response

Any XT API key/secret pasted into a chat or other shared surface must be treated as compromised, even if withdrawals are disabled.

1. Revoke the exposed key in XT immediately.
2. Create a replacement key only after revocation.
3. Grant only the minimum futures permissions required. Do not grant withdrawals or transfers.
4. Apply IP allowlisting to the production server where XT supports it.
5. Store replacement values in the server's secret manager/environment as `XT_API_KEY` and `XT_SECRET_KEY`.
6. Never put secrets in Git, source code, Colab output, Android assets, logs, screenshots, or API responses.

## Current implementation status

- Public XT USDT perpetual market scanner: implemented.
- Closed-candle multi-timeframe signal evaluation: implemented.
- XT authenticated account/balance reads: implemented.
- Android autonomous paper-trading pilot: integrated into foreground scans and the background monitor; explicit opt-in required. It persists simulated positions/journal locally, requires a validated ten-member neural bundle plus aligned neural direction and technical consensus, uses adaptive 0.15%-0.50% equity risk, volatility-scaled 1x-3x leverage, 2.5% portfolio open-risk ceiling, 2% daily loss stop, cooldowns, conservative stop/target handling and break-even/ATR trailing stops. Estimated fees, slippage and funding are applied. Paper mode never sends exchange orders.
- XT contract metadata lookup: implemented (public read-only).
- Protected market-order adapter: implemented behind two explicit server-side gates; not exposed as a public API route and not enabled.
- Automated LIVE strategy-to-order orchestration, exchange position reconciliation, durable live-order journal, leverage/margin setup, and independent stop-order verification: NOT implemented. The Android paper journal is not a live execution journal.
- Live order placement: disabled by default.
- `LIVE_TRADING_ENABLED` must remain `false`.

## Paper autopilot limits in Android 2.0.0\n\nThe Android background service can automatically open and manage simulated positions only when the user explicitly enables paper mode. It uses polling snapshots, not exchange tick streams. Risk guardrails: 0.15%-0.50% planned equity risk per trade, 2.5% aggregate open risk, three concurrent paper positions, 60-minute per-symbol cooldown, 2% daily loss stop, and a 48-hour maximum holding period. Stop/target collision on an OHLC candle is resolved conservatively in favor of the stop. These controls reduce simulated exposure only; they do not protect a live account. Restart persistence is local SharedPreferences and is not tamper-resistant accounting.\n\n## Mandatory engineering gates before live execution

Live execution is a separate subsystem and must not be enabled merely because API credentials exist. It requires:

1. Fetch and validate XT contract metadata for every symbol: contract size, quantity precision/step, minimum quantity/notional, price tick, trading status, and supported order types.
2. Reconcile account positions, open orders, leverage and margin mode from XT before every new action and after every restart.
3. Persist order intents, exchange order IDs, fills, position state, risk reservations, and idempotency keys in durable storage.
4. Use deterministic client order IDs/idempotency and prevent duplicate entries on retries, reconnects, or concurrent workers.
5. Verify entry fills before calculating protection quantities. Confirm stop-loss and take-profit orders are accepted by XT; if protection cannot be confirmed, immediately reduce/close exposure and halt new entries.
6. Handle partial fills, rejected orders, rate limits, timeouts with unknown outcomes, clock drift, network partitions, delistings, precision errors, and exchange maintenance.
7. On startup, reconcile local state with the exchange; never assume in-memory state survived a restart.
8. Add independent circuit breakers: daily realized + unrealized loss, consecutive losses, portfolio risk, maximum positions, maximum leverage, stale market data, abnormal spread/slippage, and API error rate.
9. Provide an authenticated emergency stop that blocks new entries and safely manages existing exposure; test it under simulated failures.
10. Run unit tests, mocked XT API contract tests, paper trading, and a monitored testnet/small-capital pilot before considering unattended operation.

## Initial conservative policy

The example configuration uses 0.5% planned equity risk per trade, a 1% hard per-trade ceiling, 3% portfolio risk, 3x leverage ceiling, three concurrent positions, and a 2% daily loss circuit breaker. These are guardrails, not guarantees against gaps, slippage, liquidation, or exchange failure.

## Activation policy

Do not set `LIVE_TRADING_ENABLED=true` until every mandatory gate above has passing evidence and the account owner has reviewed the exact deployment configuration. API credentials alone never authorize bypassing risk checks.
