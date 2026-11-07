# AMIRARSALAN TRADER WOLF — v2.14.0 engineering notes

## Scope

This release remains a market-analysis and signal application only. It has no exchange account connection, API-key input, wallet access, order placement, manual order ticket, or automated trade execution. Binance USDⓈ-M public market data is the configured source. A regional HTTP 451 response is respected and is not bypassed.

## Implemented in this release

- Refined Compose background: dark terminal grid, layered wolf crest, subtle gold eye accents and a faint market trace. The artwork is drawn locally and does not require a remote image request.
- TradingView chart interval controls for 5m, 15m, 1h, 4h and 1D.
- Full-universe scan still evaluates 15m/1h/4h; the 24 strongest candidates receive an additional 5m/1D deep review, with the actual coverage stored in signal cards and the journal.
- Additional technical context: CCI, ROC, MFI, normalized OBV direction, iterative ATR-band Supertrend, approximate RSI divergence, recent liquidity-sweep detection and three-candle fair-value-gap detection.
- Swing-pivot market structure with BOS/CHOCH context, replacing the former fixed-size chunk comparison.
- Corrected harmonic ratio calculation to use the XA leg for AD/XA; separated Bat and Crab ratio ranges.
- Updated Android versionCode 28 / versionName 2.14.0.

## Existing safeguards retained

- Only closed candles are used for signal calculations.
- OHLCV finite-value and consistency checks remain active.
- Ten technical specialists are explainable rule-based voters, not ten independent LLMs.
- The bundled neural ensemble remains experimental unless its held-out metrics beat the prevalence baseline under the configured validation gate.
- LONG/SHORT/NO TRADE, entry, stop and target prices are analytical references, not exchange orders or guaranteed outcomes.
- Foreground monitoring is subject to Android battery, force-stop, network and OS restrictions.

## Known limitations and next engineering work

1. The live market data scanner still uses Binance public USDⓈ-M endpoints. If Binance returns HTTP 451 from the user's network, this app cannot lawfully or reliably provide Binance data on that connection. A separate provider adapter would need explicit source labeling and independent data validation.
2. The live scanner's signal-decision timeframes remain 15m/1h/4h. Chart interval selection does not imply that every interval has been independently incorporated into the signal engine.
3. CCI/ROC/MFI/OBV, Supertrend, divergence, liquidity sweep, FVG and BOS/CHOCH are technical evidence/context, not standalone predictive guarantees. Approximate divergence and FVG detection need additional unit tests and walk-forward research.
4. The harmonic detector is a pivot-ratio heuristic, not a full PRZ engine with volatility-scaled tolerance and confirmation rules.
5. Neural training has only a single purged chronological holdout in the bundled report, not a broad multi-regime walk-forward certification. The current model did not outperform the prevalence baseline on log loss and therefore must remain excluded from signal-confidence scoring.
6. No claim is made that this release is profitable, superior to professional traders, a foundation model, or a million-fact database. More data and model complexity are useful only when sourced, tested, versioned and shown to improve out-of-sample results.

## Acceptance

A green CI build confirms source compilation and configured automated tests only. It does not establish runtime compatibility on every Android device, regional Binance availability, uninterrupted background execution, or trading profitability.