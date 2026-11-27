# WOLFBLACK Cloud Memory API

A minimal, self-hostable sync service for **non-secret** journal records. It uses Python's standard library and SQLite; no paid database or proprietary SDK is required.

## Important deployment facts
- This repository contains the server implementation, not a running cloud machine.
- Deploy the container on a host that provides HTTPS and a persistent disk mounted at `/data`. A free-tier host may sleep, impose quotas, or remove persistent storage; verify its current terms before relying on it.
- Do not use GitHub Actions as a permanent server. Workflow runners are temporary.
- The Android app is **not yet connected** to this API. Until client integration and a successful authenticated round-trip test are completed, the app must show Cloud Sync as disconnected.
- Never sync XT API keys, passwords, private keys, access tokens, account balances, or other credentials.

## Environment
- `WOLFBLACK_SIGNING_SECRET`: random secret, at least 32 characters.
- `PORT`: host-provided port (default 8080).
- `WOLFBLACK_DB`: SQLite path (default `/data/wolfblack.sqlite3`).

Generate a signing secret with `python -c "import secrets; print(secrets.token_urlsafe(48))"`.
Issue a short-lived user token with:
```sh
export WOLFBLACK_SIGNING_SECRET='the-same-secret-configured-on-the-host'
python issue_token.py user_12345678 30
```
The second argument is token lifetime in days. Keep the token private; do not commit it.

## API
- `GET /health`: liveness only; no auth.
- `GET /v1/memory?limit=100&before=<unix-ms>`: authenticated, user-partitioned records.
- `POST /v1/memory`: authenticated JSON body `{"records":[{"id":"stable_record_id","createdAt":1730000000000,"data":{"kind":"signal_outcome","value":{}}}]}`.

The API rejects credential-like field names, limits payloads, and uses idempotent record IDs. Put it behind HTTPS. Configure backups, retention, export and deletion procedures before storing personal data. This first version is a journal sync backend, not a model-training service or XT trading server.
