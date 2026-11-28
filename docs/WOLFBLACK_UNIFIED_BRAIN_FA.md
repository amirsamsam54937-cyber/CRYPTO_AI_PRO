# WOLFBLACK Unified Brain — Integration Contract

## Base and scope
- Base: `zoro-apex-unified-gen31`
- Integration branch: `wolfblack-unified-brain-gen1`
- Android app: Kotlin / Jetpack Compose, package `com.cryptoaipro.monitor`
- This integration preserves the existing XT market scanner, advanced technical engine, liquidity map, ten-member pretrained ensemble, HOPE supervisory/research loop, outcome journals, Queen Core, Nexus cognitive checks, XT capital manager, live execution gates and profit-lock engine.

## New central boundary
`WolfBlackUnifiedBrain` is the explicit orchestration boundary around `ZoroApexQueenCore`.
It accepts:
- market state with actual spread, data quality, regime, open-position state, emergency-stop state and account readiness;
- independently produced expert evidence with bounded scores, measured out-of-sample reliability and source freshness;
- neural council votes plus the model's actual validation status and out-of-distribution flag;
- explicit equity and risk constraints.

It never fabricates spread, reliability, data freshness or account state. Missing/invalid values are expected to fail closed through Queen Core. A neural model is passed to the arbiter only when loaded, independently validated, sufficiently populated and not out-of-distribution.

## Existing components retained
| Component | Responsibility |
|---|---|
| `ZoroAdvancedTechnicalEngine` | OHLCV indicators, structure, patterns and levels |
| `ZoroLiquidityMapEngine` | Candle-derived liquidity pools and sweep/reclaim observations |
| `WolfPretrainedEnsemble` | Ten role-diverse MLP specialists and model-health metadata |
| `HopeLivingBrain` / `HopeResearchEngine` | Supervision, research cadence and durable source memory |
| `NexusCognitiveEngine` | Data freshness, multi-horizon conflict, cost stress test and abstention |
| `ZoroApexQueenCore` | Deterministic evidence arbitration and bounded risk plan |
| `XtFuturesCapitalManager` | Contract sizing, fees, funding, slippage and margin constraints |
| `XtLiveTradingEngine` | Separately armed XT order lifecycle and emergency protection |
| `ZoroProfitLockEngine` | Stop ratchets and fee-aware profit locking |
| `WolfSignalOutcomeJournal` | Outcome tracking and training/evaluation records |

## Safety and integration gates
1. The new coordinator is an advisory/eligibility layer, not an order-placement API.
2. The current app's scan path still uses its existing production gates. The new Queen boundary must not be made authoritative until actual spread and specialist reliability inputs are supplied by production sources.
3. No default reliability values are invented. The next integration step is a chronological per-specialist calibration ledger derived from resolved, fee-aware outcomes.
4. Live execution must continue to require explicit arming, verified XT account connectivity, correct contract filters, idempotent order reconciliation, exchange-side SL/TP confirmation and emergency-stop handling.
5. The XT Futures public endpoint has no verified sandbox in the repository's existing readiness notes. Local paper/backtest is not XT testnet.
6. The ten-member neural bundle is not automatically considered profitable. Model promotion requires fresh walk-forward and symbol-holdout evaluation against a baseline, calibration and drift checks.
7. The $10 backtest must include XT fees, adverse slippage and funding assumptions and must label assumptions separately from realized fills.

## Validation added in this branch
Unit tests cover validated versus unvalidated neural participation, out-of-distribution abstention, missing specialists, invalid spread and emergency-stop blocking.
Android CI must pass before this branch is considered build-verified.
