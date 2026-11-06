import pandas as pd
import pytest
from app.indicators import ema,rsi,build_signal
from app.risk import size_position

def frame(n=100):
    c=pd.Series([100+i*.2 for i in range(n)])
    return pd.DataFrame({"open":c,"high":c+1,"low":c-1,"close":c,"volume":10})

def test_ema_rising(): assert ema(frame()["close"],20).iloc[-1] > ema(frame()["close"],20).iloc[0]
def test_rsi_bounded(): assert rsi(frame()["close"]).between(0,100).all()
def test_signal_explainable():
    result=build_signal(frame())
    assert result["direction"] in {"LONG_BIAS","SHORT_BIAS","NEUTRAL"} and result["reasons"]
def test_risk_cap():
    p=size_position(1000,100,95,risk_fraction=.02,max_risk_fraction=.01)
    assert p.risk_amount == pytest.approx(10) and p.quantity == pytest.approx(2)
def test_portfolio_limit():
    with pytest.raises(ValueError): size_position(1000,100,95,current_portfolio_risk=.04)
