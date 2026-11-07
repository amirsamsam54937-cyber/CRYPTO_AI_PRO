# Wolf Cloud Brain

This is a deployable, persistent cloud research service. It scans public USDT perpetual futures data on Binance USD-M, performs multi-timeframe OHLCV analysis without rendering charts to the user, stores observations in managed PostgreSQL, and resolves 24-hour price-direction labels for later model evaluation.

## Safety boundary
- Public market data only. No exchange API keys, account access, order endpoints, or trade execution.
- Learning labels are forward price-direction observations, not trade fills or proof of profitability.
- The service deliberately uses a managed database; ephemeral container storage is not used for the research memory.
- Set a long random `WOLF_CLOUD_TOKEN`; never commit it or embed it in a public APK.

## Deploy
1. Import this repository into Render and choose **Blueprint** using `render.yaml`.
2. Review the paid Starter web-service and Basic PostgreSQL resources and approve provisioning. Render billing/account approval is required; repository changes cannot create a third-party cloud account or authorize charges.
3. After deployment, copy the generated `WOLF_CLOUD_TOKEN` from the service environment and keep it private.
4. Check `https://YOUR-SERVICE.onrender.com/health`.
5. Authenticated endpoints require `Authorization: Bearer YOUR_TOKEN`:
   - `GET /v1/status` — scan status and persistent row counts.
   - `GET /v1/signals?timeframe=1h&limit=50` — latest stored technical analyses.
   - `GET /v1/learning` — resolved outcome-memory summary.

## Runtime
- Scans the top 40 active USDT perpetual contracts by quote turnover every 5 minutes.
- Timeframes: 15m, 1h, 4h, 1d.
- Excludes the currently forming candle.
- Persists unique symbol/timeframe/candle observations and labels 24-hour forward returns when they mature.
- If exchange access, rate limits, or network availability fail, the worker retries on the next cycle. It does not attempt regional access circumvention.

## Important distinction
The cloud brain is a deployable service, not yet a provisioned server. Deployment requires the owner's Render login and approval of paid resources. The Android app is not yet configured with the private cloud endpoint/token; do not put the token into a distributable APK. A secure app-to-cloud authentication flow (for example, per-device credentials issued by a small authenticated gateway) must be completed before the app can consume private cloud signals.
