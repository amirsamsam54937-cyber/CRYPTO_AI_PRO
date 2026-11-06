"""Risk calculations only; this module never submits exchange orders."""
from dataclasses import dataclass

@dataclass(frozen=True)
class RiskPlan:
    quantity: float
    risk_amount: float
    notional: float
    leverage: float

def size_position(equity: float, entry: float, stop: float, risk_fraction: float=.01,
                  max_risk_fraction: float=.01, max_leverage: float=2.,
                  max_portfolio_risk_fraction: float=.04,
                  current_portfolio_risk: float=0.) -> RiskPlan:
    vals=[equity,entry,stop,risk_fraction,max_risk_fraction,max_leverage,
          max_portfolio_risk_fraction,current_portfolio_risk]
    if any(v < 0 for v in vals) or equity <= 0 or entry <= 0 or stop <= 0:
        raise ValueError("Prices/equity must be positive and risk values non-negative")
    distance=abs(entry-stop)
    if distance <= 0: raise ValueError("Entry and stop must differ")
    risk_amount=min(equity*min(risk_fraction,max_risk_fraction),
                    equity*max(0.,max_portfolio_risk_fraction-current_portfolio_risk))
    if risk_amount <= 0: raise ValueError("Portfolio risk limit reached")
    quantity=risk_amount/distance
    notional=quantity*entry
    leverage=min(max_leverage,notional/equity)
    return RiskPlan(quantity,risk_amount,notional,leverage)
