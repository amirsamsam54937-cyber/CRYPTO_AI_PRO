# ZORO XT Futures integration — Gen27

## Current supported path

- Venue: XT USDT-M Futures.
- Public market data: existing XT futures market scanner.
- Private API: authenticated read-only account and position queries.
- API signing: XT Futures HMAC-SHA256 canonicalization, exact JSON body for POST and sorted query string for GET.
- Credential storage: Android Keystore AES-GCM; no secret logging.
- Simulation: existing ZORO paper ledger driven by current XT public market data.
- Live execution: hard-disabled. The current app has no callable order-placement, leverage-change, transfer, or withdrawal method in its read-only client.

## Test environment limitation

As of 2026-11-14, XT's public USDT-M futures API documentation lists https://fapi.xt.com as the production base URL and does not publish a separate futures testnet/sandbox URL. CCXT's XT comparison also reports that XT does not expose a sandbox mode. Do not label local simulation as XT Testnet.

Therefore the first validation stage is called XT Shadow/Paper:
1. Receive real public XT market data.
2. Run ZORO signal and neural analysis.
3. Simulate fills, fees, slippage, funding, stop and target handling locally.
4. Compare simulated order lifecycle with recorded XT market prices.
5. Test request signing and response parsing offline against deterministic fixtures.
6. Use a read-only XT API key only to verify account authentication and read permissions.

This is not exchange-side testnet execution and cannot prove that live order parameters, matching, trigger execution, or reduce-only behavior work.

## Live activation prerequisites (not met)

- XT publishes a dedicated futures sandbox or a controlled test account that can safely validate entry and protective orders.
- Verify exact contract quantity semantics, contract multiplier, min quantity, tick size, and order status reconciliation.
- Verify whether the order/trigger endpoints support reduce-only/close-position semantics for the chosen position mode.
- Verify partial fills, duplicate client order IDs, unknown HTTP timeouts, stop/target rejection, and emergency close behavior.
- Persist an atomic order journal and reconcile all open positions/orders at startup and after reconnect.
- Validate API key permissions independently; require Read + Trade only, Withdrawal disabled, and IP allowlist.
- Complete a separate small-notional live pilot with explicit owner approval and a physical in-app kill switch.
- Keep risk limits hard-coded and fail closed on stale data, clock skew, excessive slippage, rate limits, unknown order state, or protection failure.

## Current conservative limits for any future pilot

- Per-trade planned stop risk: at most 0.50% of equity.
- Daily loss breaker: 2.0%.
- Peak-to-trough drawdown breaker: 8.0%.
- Leverage cap: 3x.
- Concurrent positions: at most 3.
- Margin allocation cap: at most 10% of available balance per position in the XT sizing helper.
- No withdrawal or transfer endpoints.

These are engineering guardrails, not a guarantee against losses or exchange-side failure.

## Gen27 — fee-aware Futures capital management

- Added a deterministic Futures-only capital sizing module: `XtFuturesCapitalManager`.
- It distinguishes Wallet Balance, Equity, Available Margin, Used Margin, and Open-Order Frozen Margin. The authenticated client reads the explicit USDT Futures balance fields and fails closed if the exchange response does not contain a recognized free-margin field; it does not guess free margin by subtracting assumed fields.
- Contract sizing is constrained simultaneously by stop-loss risk budget, available margin, leverage cap, quantity step, minimum quantity, and minimum notional.
- Baseline cost model includes entry fee, exit fee, adverse slippage on both sides, and expected funding. Funding is costed using the absolute rate, so a possible funding receipt is not counted as guaranteed profit.
- The default cost baseline is Maker 0.02% / Taker 0.06%, based on XT's updated futures fee schedule; account VIP rates can differ. Actual exchange fill fee and funding records must reconcile the estimate and replace it in the realized-PnL ledger.
- Rejects a trade if estimated net target profit is non-positive or net reward/risk is below 1.2 after costs.
- Current guardrails in the helper: 0.10%–1.00% equity risk input (default 0.50%), maximum 10% equity margin allocation per trade by default, and maximum 3x leverage.
- Unit tests cover small balances, fees, adverse funding, insufficient margin, excessive leverage, and net-profit rejection.

Important: Gen27 adds the capital and fee calculation engine and a read-only wallet display. It does not activate order placement. XT public Futures Testnet is still not documented, so exchange-side order execution remains locked. A small balance is supported only if it satisfies the exchange's minimum order quantity/notional while staying inside the risk budget; otherwise the correct decision is no trade.
