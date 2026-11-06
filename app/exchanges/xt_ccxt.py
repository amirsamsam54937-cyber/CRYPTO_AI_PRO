"""CCXT-based XT USDT perpetual account adapter.

Read-only by design: this module exposes account, balance, position and open-order
snapshots only. It deliberately contains no create_order/cancel_order calls.
Credentials are read from process environment and are never returned or logged.
"""
from __future__ import annotations

import os
from typing import Any

import ccxt
from dotenv import load_dotenv

# Load local .env for self-hosted research deployments; real deployments should
# inject secrets through the process environment/secret manager.
load_dotenv()


class XTCCXTError(RuntimeError):
    """Safe account-connection error."""


def _credentials() -> tuple[str, str]:
    api_key = os.getenv("XT_API_KEY", "").strip()
    secret = (os.getenv("XT_API_SECRET", "") or os.getenv("XT_SECRET_KEY", "")).strip()
    if not api_key or not secret:
        raise XTCCXTError(
            "کلیدهای XT روی سرور تنظیم نشده‌اند؛ XT_API_KEY و XT_API_SECRET (یا نام قدیمی XT_SECRET_KEY) را در Secret Manager وارد کنید."
        )
    return api_key, secret


def _number(value: Any) -> float:
    try:
        result = float(value or 0)
        return result if result == result and abs(result) != float("inf") else 0.0
    except (TypeError, ValueError):
        return 0.0


def build_xt_exchange():
    """Create a rate-limited XT swap client without exposing credentials."""
    api_key, secret = _credentials()
    return ccxt.xt({
        "apiKey": api_key,
        "secret": secret,
        "enableRateLimit": True,
        "timeout": 30_000,
        "options": {
            "defaultType": "swap",
            "adjustForTimeDifference": True,
        },
    })


def _component_error(exc: Exception) -> dict[str, str]:
    # Do not return raw exception text: some SDKs include request details.
    return {"status": "unavailable", "error": type(exc).__name__}


def xt_account_snapshot(include_positions: bool = True,
                        include_open_orders: bool = True) -> dict[str, Any]:
    """Load markets and read account state; never submits or modifies orders."""
    exchange = build_xt_exchange()
    result: dict[str, Any] = {
        "exchange": "XT",
        "market_type": "USDT perpetual",
        "mode": "authenticated-read-only",
        "connected": False,
        "markets_loaded": 0,
        "balance": None,
        "positions": {"status": "not_requested", "items": []},
        "open_orders": {"status": "not_requested", "items": []},
    }

    try:
        markets = exchange.load_markets()
        result["markets_loaded"] = len(markets or {})
        balance = exchange.fetch_balance()
        usdt = (balance or {}).get("USDT") or {}
        free = _number(usdt.get("free"))
        used = _number(usdt.get("used"))
        total = _number(usdt.get("total"))
        result["balance"] = {
            "currency": "USDT",
            "free": free,
            "used": used,
            "total": total,
            "is_empty": total <= 0 and free <= 0 and used <= 0,
        }
        result["connected"] = True
        result["account_note"] = (
            "اتصال احراز هویت شد؛ موجودی فیوچرز USDT صفر است."
            if total <= 0 and free <= 0 and used <= 0
            else "اتصال احراز هویت شد."
        )
    except Exception as exc:
        result["connection_error"] = _component_error(exc)
        return result

    if include_positions:
        try:
            rows = exchange.fetch_positions()
            active = []
            for row in rows or []:
                contracts = abs(_number(row.get("contracts")))
                if contracts <= 0:
                    continue
                active.append({
                    "symbol": row.get("symbol"),
                    "side": row.get("side"),
                    "contracts": contracts,
                    "entry_price": _number(row.get("entryPrice")),
                    "mark_price": _number(row.get("markPrice")),
                    "unrealized_pnl": _number(row.get("unrealizedPnl")),
                    "leverage": _number(row.get("leverage")),
                    "margin_mode": row.get("marginMode"),
                    "liquidation_price": _number(row.get("liquidationPrice")),
                })
            result["positions"] = {"status": "ok", "count": len(active), "items": active}
        except Exception as exc:
            result["positions"] = _component_error(exc) | {"items": []}

    if include_open_orders:
        try:
            rows = exchange.fetch_open_orders()
            active_orders = []
            for row in rows or []:
                active_orders.append({
                    "id": str(row.get("id") or ""),
                    "client_order_id": row.get("clientOrderId"),
                    "symbol": row.get("symbol"),
                    "side": row.get("side"),
                    "type": row.get("type"),
                    "status": row.get("status"),
                    "amount": _number(row.get("amount")),
                    "filled": _number(row.get("filled")),
                    "remaining": _number(row.get("remaining")),
                    "price": _number(row.get("price")),
                })
            result["open_orders"] = {
                "status": "ok", "count": len(active_orders), "items": active_orders
            }
        except Exception as exc:
            result["open_orders"] = _component_error(exc) | {"items": []}

    return result
