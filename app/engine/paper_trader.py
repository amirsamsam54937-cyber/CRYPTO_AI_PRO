"""Deterministic paper-trading engine for XT USDT-M research.

This module is deliberately incapable of sending exchange orders. It models
position lifecycle, sizing, leverage ceilings, stop-loss and take-profit exits.
Live execution must be a separately reviewed adapter and remain disabled by
default until the account owner explicitly enables it.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
from typing import Optional
from uuid import uuid4

from app.risk import size_position


class Side(str, Enum):
    LONG = "LONG"
    SHORT = "SHORT"


@dataclass
class PaperPosition:
    position_id: str
    symbol: str
    side: Side
    quantity: float
    entry: float
    stop: float
    take_profit_1: float
    take_profit_2: float
    take_profit_3: float
    leverage: float
    risk_amount: float
    remaining_fraction: float = 1.0
    realized_pnl: float = 0.0
    status: str = "OPEN"
    exit_reason: Optional[str] = None


class PaperBroker:
    """In-memory simulator. No credentials, network, or exchange client."""

    def __init__(self, equity: float, risk_per_trade: float = 0.005,
                 max_risk_per_trade: float = 0.01,
                 max_portfolio_risk: float = 0.03,
                 max_leverage: float = 3.0,
                 max_positions: int = 3,
                 fee_rate: float = 0.0006):
        if equity <= 0:
            raise ValueError("equity must be positive")
        if not 0 < risk_per_trade <= max_risk_per_trade <= 0.02:
            raise ValueError("risk settings must be positive and capped at 2%")
        if not 1 <= max_leverage <= 5:
            raise ValueError("paper leverage ceiling must be between 1x and 5x")
        if max_positions < 1 or not 0 <= fee_rate < 0.02:
            raise ValueError("invalid portfolio settings")
        self.equity = float(equity)
        self.risk_per_trade = risk_per_trade
        self.max_risk_per_trade = max_risk_per_trade
        self.max_portfolio_risk = max_portfolio_risk
        self.max_leverage = max_leverage
        self.max_positions = max_positions
        self.fee_rate = fee_rate
        self.positions: dict[str, PaperPosition] = {}
        self.closed: list[dict] = []

    @property
    def open_positions(self) -> list[PaperPosition]:
        return [p for p in self.positions.values() if p.status == "OPEN"]

    @property
    def portfolio_risk_fraction(self) -> float:
        if self.equity <= 0:
            return 1.0
        return sum(p.risk_amount * p.remaining_fraction for p in self.open_positions) / self.equity

    def open_position(self, symbol: str, side: Side | str, entry: float,
                      stop: float, reward_risk: float = 2.0) -> PaperPosition:
        side = Side(side)
        if not symbol or entry <= 0 or stop <= 0 or reward_risk < 1.0:
            raise ValueError("invalid symbol, prices, or reward/risk")
        if len(self.open_positions) >= self.max_positions:
            raise ValueError("maximum paper positions reached")
        if any(p.symbol == symbol and p.status == "OPEN" for p in self.positions.values()):
            raise ValueError("an open paper position already exists for this symbol")
        if (side == Side.LONG and stop >= entry) or (side == Side.SHORT and stop <= entry):
            raise ValueError("stop must be below entry for LONG and above entry for SHORT")

        risk = size_position(
            equity=self.equity, entry=entry, stop=stop,
            risk_fraction=self.risk_per_trade,
            max_risk_fraction=self.max_risk_per_trade,
            max_leverage=self.max_leverage,
            max_portfolio_risk_fraction=self.max_portfolio_risk,
            current_portfolio_risk=self.portfolio_risk_fraction,
        )
        unit_risk = abs(entry - stop)
        direction = 1 if side == Side.LONG else -1
        targets = [entry + direction * unit_risk * reward_rr
                   for reward_rr in (1.0, reward_risk, reward_risk * 1.5)]
        pos = PaperPosition(
            position_id=str(uuid4()), symbol=symbol, side=side,
            quantity=risk.quantity, entry=entry, stop=stop,
            take_profit_1=targets[0], take_profit_2=targets[1],
            take_profit_3=targets[2], leverage=min(self.max_leverage, risk.leverage),
            risk_amount=risk.risk_amount,
        )
        self.positions[pos.position_id] = pos
        return pos

    def on_price(self, position_id: str, price: float) -> PaperPosition:
        """Apply conservative stop-first intrabar handling to a supplied price."""
        if price <= 0:
            raise ValueError("price must be positive")
        pos = self.positions[position_id]
        if pos.status != "OPEN":
            return pos
        long_side = pos.side == Side.LONG
        stopped = price <= pos.stop if long_side else price >= pos.stop
        if stopped:
            self._close_fraction(pos, pos.remaining_fraction, pos.stop, "STOP_LOSS")
            return pos

        hit1 = price >= pos.take_profit_1 if long_side else price <= pos.take_profit_1
        if hit1 and pos.remaining_fraction > 0.66:
            self._close_fraction(pos, 1 / 3, pos.take_profit_1, "TAKE_PROFIT_1")
            # Move remaining position stop to breakeven after TP1.
            pos.stop = pos.entry
        hit2 = price >= pos.take_profit_2 if long_side else price <= pos.take_profit_2
        if hit2 and pos.remaining_fraction > 0.34:
            self._close_fraction(pos, 0.5, pos.take_profit_2, "TAKE_PROFIT_2")
        hit3 = price >= pos.take_profit_3 if long_side else price <= pos.take_profit_3
        if hit3 and pos.remaining_fraction > 0:
            self._close_fraction(pos, pos.remaining_fraction, pos.take_profit_3, "TAKE_PROFIT_3")
        return pos

    def _close_fraction(self, pos: PaperPosition, fraction: float,
                        exit_price: float, reason: str) -> None:
        fraction = min(max(fraction, 0.0), pos.remaining_fraction)
        direction = 1 if pos.side == Side.LONG else -1
        qty = pos.quantity * fraction
        gross = (exit_price - pos.entry) * qty * direction
        fees = (pos.entry * qty + exit_price * qty) * self.fee_rate
        pnl = gross - fees
        pos.realized_pnl += pnl
        self.equity += pnl
        pos.remaining_fraction -= fraction
        if pos.remaining_fraction <= 1e-9:
            pos.remaining_fraction = 0.0
            pos.status = "CLOSED"
            pos.exit_reason = reason
            self.closed.append(asdict(pos))
