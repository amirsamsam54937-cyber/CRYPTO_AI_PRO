"""XT USDT-M Futures authenticated read-only adapter.

Credentials are loaded only from process environment. Never commit .env or
paste exchange credentials into chat. This module intentionally exposes no
order-placement method; it is the account-connectivity foundation.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import time
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlencode

import requests


BASE_URL = "https://fapi.xt.com"


class XTAPIError(RuntimeError):
    """Safe-to-display exchange error without credential material."""


@dataclass(frozen=True)
class XTCredentials:
    api_key: str
    secret_key: str

    @classmethod
    def from_env(cls) -> "XTCredentials":
        key = os.getenv("XT_API_KEY", "").strip()
        secret = os.getenv("XT_SECRET_KEY", "").strip()
        if not key or not secret:
            raise XTAPIError("XT credentials are not configured in the server environment.")
        return cls(key, secret)


class XTFuturesClient:
    """Minimal signed XT USDT-M client for account and balance reads."""

    def __init__(
        self,
        credentials: XTCredentials | None = None,
        base_url: str = BASE_URL,
        timeout: float = 12.0,
        session: requests.Session | None = None,
    ) -> None:
        self.credentials = credentials or XTCredentials.from_env()
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = session or requests.Session()

    def _signature(self, path: str, timestamp: str, params: dict[str, Any] | None = None) -> str:
        fixed = f"validate-appkey={self.credentials.api_key}&validate-timestamp={timestamp}"
        query = urlencode(sorted((params or {}).items()))
        payload = f"{fixed}#{path}#{query}" if query else f"{fixed}#{path}"
        return hmac.new(
            self.credentials.secret_key.encode("utf-8"),
            payload.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()

    def _get(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        timestamp = str(int(time.time() * 1000))
        headers = {
            "validate-appkey": self.credentials.api_key,
            "validate-timestamp": timestamp,
            "validate-signature": self._signature(path, timestamp, params),
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
        }
        try:
            response = self.session.get(
                self.base_url + path,
                params=params or {},
                headers=headers,
                timeout=self.timeout,
            )
            response.raise_for_status()
            data = response.json()
        except requests.RequestException as exc:
            raise XTAPIError(f"XT network request failed: {type(exc).__name__}") from None
        except ValueError:
            raise XTAPIError("XT returned an invalid JSON response.") from None

        if not isinstance(data, dict):
            raise XTAPIError("XT returned an unexpected response format.")
        # XT's futures API uses returnCode/error envelopes; tolerate documented variants.
        code = data.get("returnCode")
        if code not in (None, 0, "0", 200, "200"):
            error = data.get("error") or {}
            message = error.get("msg") if isinstance(error, dict) else None
            raise XTAPIError(str(message or data.get("msgInfo") or "XT rejected the request."))
        return data

    def account_info(self) -> dict[str, Any]:
        """Return authenticated futures account metadata (no trading action)."""
        return self._get("/future/user/v1/account/info")

    def balances(self, account_id: str) -> dict[str, Any]:
        """Return futures wallet balances for the supplied XT account ID."""
        if not account_id or not str(account_id).strip():
            raise ValueError("account_id is required")
        return self._get(
            "/future/user/v1/compat/balance/list",
            {"queryAccountId": str(account_id)},
        )

    def connection_check(self) -> dict[str, Any]:
        info = self.account_info()
        result = info.get("result") or info.get("data") or {}
        if not isinstance(result, dict):
            result = {}
        return {
            "connected": True,
            "account_id": result.get("accountId"),
            "account_state": result.get("state"),
            "allow_trade": result.get("allowTrade"),
            "allow_open_position": result.get("allowOpenPosition"),
            "withdrawal_not_requested": True,
        }
