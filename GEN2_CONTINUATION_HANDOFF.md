# ZORO Generation 2 — Continuation Handoff

Last updated: 2026-11-13 UTC

## Active development target
- Repository: https://github.com/amirsamsam54937-cyber/CRYPTO_AI_PRO
- Active implementation branch: `zoro-gen2-neural-calibration`
- Production baseline (do not overwrite without validation): `release/hope-hunter-2.64-enhanced-signals`
- This handoff branch exists so future ChatGPT sessions can recover the exact project state without relying on chat memory.
- Product: ZORO, Persian-first Android XT USDT-M futures analysis and signal application. No exchange account credentials or order execution in the current documented product scope.
- Visual identity: black/gold, wordmark exactly `ZORO`.

## Last known GitHub Actions state (2026-11-13)
- Build workflow run: **43490287946**
- URL: https://github.com/amirsamsam54937-cyber/CRYPTO_AI_PRO/actions/runs/43490287946
- Head SHA: `0e85575c4d93d3c7ee38685fb725e6eb00e1e69f`
- Last observed status: `in_progress`
- Job: `build-apk`, job ID `132109324918`
- Completed: checkout, Java 17, Gradle 8.9 setup, Python 3.11 setup.
- In progress at last inspection: `Build 898,920 real XT USDT-M candles`.
- Not yet started at last inspection: provenance checks, model training, packaging, Gradle APK build, APK integrity verification, artifact upload.
- Python tests run 43490281601 for SHA `50f48bffbceaddabf99bbe53d3e3d5f8b968ae36` completed successfully.
- Earlier APK runs were cancelled by concurrency. Do not treat those as deliverables.
- Re-check the run status and jobs before making any claim or starting a new push. Avoid pushing another commit to the active implementation branch while its build is running because concurrency uses cancel-in-progress.

## Generation-two commits already on implementation branch
- `62f85624ff99c31ac7f00b4792783aed8dddb150`: export `training_prior_log_odds` in neural model JSON.
- `829bfc7864e190612414f9c112e0d48d882486ed`: apply training prior log-odds correction in Kotlin inference.
- `50f48bffbceaddabf99bbe53d3e3d5f8b968ae36`: backward-compatible default for older model bundles.
- `0e85575c4d93d3c7ee38685fb725e6eb00e1e69f`: trigger historical data build/training and require validated bundle on push to `zoro-gen2-neural-calibration`.

## Actual training/data pipeline (inspect before changing)
- `tools/build_real_market_seed.py`: XT USDT-M only, 1h closed candles, no synthetic rows, target 898,920 rows; manifest has source policy and SHA-256.
- `tools/train_wolf_ensemble.py`: trains 10 MLPClassifier models using role-specific feature masks, on 7 features and 1h bars with 6-bar forward directional labels; chronological purged holdout; exports `wolf_pretrained_ensemble.json`.
- Current ten members are **not ten distinct neural architectures**. They are ten MLPs with different feature masks/roles. Do not describe them as LSTM/TCN/Transformer specialists until those architectures are implemented and tested.
- Current pipeline requires at least 745,427 labeled samples. If XT cannot supply enough real rows, stop; do not synthesize or silently switch exchanges.
- Workflow validation gates include balanced accuracy >= 0.52, ROC AUC >= 0.55, ECE <= 0.10, and log-loss/Brier better than prevalence baselines. The training step writes a report status. The packaging gate requires `validated_for_scoring=true`.
- Important audit task: labels currently exclude near-zero returns using `abs(future)>0.0025`, while target metadata strings mention up-vs-nonpositive. Align the target description and metrics with the actual filtered two-class target before claiming full semantic consistency.
- Important modeling task: review whether the same 1h timeline pooled across symbols and global timestamp sorting is suitable; ensure chronological splits do not allow symbol/time leakage. Add per-symbol and regime metrics.
- Do not promote a model solely because it trains or clears weak metrics. Add cost-aware walk-forward evaluation with fees, slippage and funding before any performance claim.

## Required next actions for the next session
1. Inspect run 43490287946; fetch jobs, steps and artifacts. If still running, wait/check again rather than pushing to the same branch.
2. If failed, inspect failed step/logs and fix the root cause on the implementation branch; re-run tests and the workflow.
3. If training completes, inspect `wolf_model_training_report.json` from the artifact. Report the real metrics, sample counts, data provenance and whether it passed gates.
4. If build completes, download the exact APK artifact from that run, extract it, verify APK ZIP integrity and exact file path, then provide a sandbox link only after checking that path exists in the current runtime.
5. If successful, create/update this handoff document on the implementation branch after the current run is finished, so future sessions find it on the active branch.
6. Then implement true specialist diversity (e.g. compact MLP, GRU/LSTM, TCN, attention model, plus non-neural regime/boosting specialists only if Android inference supports them); align feature schema, normalization, serialization and Kotlin inference exactly.
7. Add unit/integration tests for Python-vs-Kotlin parity, stale-data rejection, no-trade decisions, calibration, and inference on Android-class hardware.
8. Add cost-aware walk-forward backtesting, per-symbol/per-regime reports, and controlled champion/challenger model promotion with rollback.
9. Keep an accurate changelog and update this handoff after every meaningful milestone.

## Safety / truthfulness constraints
- Never claim the models are trained, validated, installed, or profitable until workflow artifacts and tests prove it.
- Never claim online/self-learning. Current training is offline CI training; outcome journaling is not equivalent to online neural weight updates.
- Never invent historical data, model metrics, workflow success, APK links or Android device-test results.
- Preserve the stable production branch until generation-two gates pass.
