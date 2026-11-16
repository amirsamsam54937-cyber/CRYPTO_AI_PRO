from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic_settings import BaseSettings, SettingsConfigDict
from app.market import fetch_ohlcv
from app.indicators import build_signal
from app.advanced_analysis import analyze_ohlcv
from app.backtest import run_backtest

class Settings(BaseSettings):
    app_name: str = "CRYPTO AI PRO"
    exchange_id: str = "xt"
    default_symbol: str = "BTC/USDT:USDT"
    default_timeframe: str = "1h"
    candle_limit: int = 1000
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

settings = Settings()
app = FastAPI(title=settings.app_name, version="0.3.0",
    description="XT USDT-M public market analysis and 10 USDT virtual futures backtesting. No live orders.")

DASHBOARD = """<!doctype html>
<html lang="fa" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>XT WOLF | Futures Lab</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#080d18;color:#edf3ff;font-family:Tahoma,Arial,sans-serif}
main{max-width:1050px;margin:auto;padding:22px}.hero{padding:24px;border:1px solid #263653;border-radius:20px;background:linear-gradient(135deg,#17243a,#101827 60%,#241b36)}
h1{margin:0 0 8px;font-size:25px}.muted{color:#9cacc4;font-size:13px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:12px}
.panel,.metric{background:#111b2b;border:1px solid #263653;border-radius:15px;padding:16px;margin-top:14px}
label{display:block;color:#aab9ce;font-size:12px;margin:0 0 6px}input,select{width:100%;background:#091321;color:#f4f7ff;border:1px solid #344967;border-radius:9px;padding:12px}
button{background:#a8f06b;color:#101a0d;border:0;border-radius:10px;padding:13px 22px;font-weight:bold;cursor:pointer}
button:disabled{opacity:.5;cursor:wait}.metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin-top:14px}
.metric small{display:block;color:#9cacc4;margin-bottom:8px}.metric strong{font-size:22px;overflow-wrap:anywhere}
.good{color:#a8f06b}.bad{color:#ff8282}.wide{grid-column:1/-1}canvas{width:100%;height:230px;background:#0a1423;border-radius:10px}
table{width:100%;border-collapse:collapse;font-size:12px}th,td{text-align:right;padding:10px;border-bottom:1px solid #273750;white-space:nowrap}th{color:#a8bad1}
.scroll{overflow:auto}.status{padding:10px;color:#a8bad1}.foot{font-size:11px;color:#8799b3;line-height:1.9}
@media(max-width:600px){main{padding:12px}.hero{padding:17px}h1{font-size:20px}.metric strong{font-size:18px}}
</style></head><body><main>
<header class="hero"><div class="muted">XT USDT-M • FUTURES RESEARCH TERMINAL</div>
<h1>XT WOLF — آزمایشگاه معاملات فیوچرز</h1>
<div class="muted">بک‌تست تاریخی با موجودی مجازی ۱۰ تتر، محاسبه کارمزد و گزارش عملکرد خالص</div></header>
<section class="panel"><h3>تنظیمات بک‌تست</h3><div class="grid">
<div><label>نماد فیوچرز</label><input id="symbol" value="BTC/USDT:USDT"></div>
<div><label>تایم‌فریم</label><select id="tf"><option>15m</option><option selected>1h</option><option>4h</option><option>1d</option></select></div>
<div><label>تعداد کندل تاریخی</label><select id="candles"><option>300</option><option selected>1000</option></select></div>
<div><label>نوع اجرای فرضی</label><select id="feeType" onchange="setFee()"><option value="taker" selected>Taker — سفارش بازار</option><option value="maker">Maker — سفارش محدود</option><option value="custom">کارمزد دلخواه</option></select></div>
<div><label>کارمزد هر سمت (%)</label><input id="fee" type="number" min="0" max="2" step="0.001" value="0.06"></div>
<div><label>لغزش هر سمت (%)</label><input id="slip" type="number" min="0" max="2" step="0.001" value="0.05"></div>
<div><label>فاندینگ هر ۸ ساعت (%)</label><input id="funding" type="number" min="-2" max="2" step="0.001" value="0.01"></div>
<div><label>ریسک هر معامله (%)</label><input id="risk" type="number" min="0.01" max="2" step="0.01" value="0.5"></div>
</div><div style="display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin-top:16px">
<button id="run" onclick="runBacktest()">اجرای بک‌تست ۱۰ دلاری</button><span class="muted" id="status">آماده دریافت داده‌های XT</span></div></section>
<section id="results" hidden><div class="metrics">
<div class="metric"><small>سرمایه اولیه</small><strong>10.00 USDT</strong></div>
<div class="metric"><small>موجودی نهایی</small><strong id="final">—</strong></div>
<div class="metric"><small>سود / زیان خالص</small><strong id="pnl">—</strong></div>
<div class="metric"><small>بازده کل</small><strong id="ret">—</strong></div>
<div class="metric"><small>تعداد معاملات</small><strong id="count">—</strong></div>
<div class="metric"><small>درصد برد</small><strong id="win">—</strong></div>
<div class="metric"><small>بیشترین افت سرمایه</small><strong id="dd">—</strong></div>
<div class="metric"><small>Profit Factor</small><strong id="pf">—</strong></div>
</div>
<div class="panel"><h3>منحنی سرمایه</h3><canvas id="curve" width="1000" height="230"></canvas></div>
<div class="metrics">
<div class="metric"><small>مجموع کارمزد ورود و خروج</small><strong id="fees">—</strong></div>
<div class="metric"><small>هزینه لغزش تخمینی</small><strong id="slippage">—</strong></div>
<div class="metric"><small>خالص فاندینگ پرداختی / دریافتی</small><strong id="fundingCost">—</strong></div>
<div class="metric"><small>شارپ معاملات</small><strong id="sharpe">—</strong></div>
</div>
<div class="panel"><h3>گزارش معاملات</h3><div class="scroll"><table><thead><tr><th>#</th><th>جهت</th><th>ورود</th><th>خروج</th><th>سود خالص</th><th>کارمزد</th><th>علت خروج</th><th>موجودی</th></tr></thead><tbody id="trades"></tbody></table></div></div>
<div class="panel foot" id="assumptions"></div></section>
<section class="panel foot">نرخ پایه XT برای VIP 0: Maker برابر ۰٫۰۲٪ و Taker برابر ۰٫۰۶٪ در هر سمت. نرخ اختصاصی حساب ممکن است متفاوت باشد. فاندینگ این نسخه برآوردی است و هنوز از تاریخچه واقعی funding rate هر کندل استفاده نمی‌کند. این ابزار سفارش واقعی ثبت نمی‌کند.</section>
</main><script>
function setFee(){const t=document.getElementById('feeType').value;const f=document.getElementById('fee');if(t==='maker')f.value='0.02';else if(t==='taker')f.value='0.06';}
const fmt=(v,d=4)=>Number(v).toLocaleString('en-US',{minimumFractionDigits:d,maximumFractionDigits:d});
function metric(id,value,good){const e=document.getElementById(id);e.textContent=value;if(good===true)e.className='good';else if(good===false)e.className='bad';else e.className='';}
function drawCurve(points){const c=document.getElementById('curve'),x=c.getContext('2d'),w=c.width,h=c.height;x.clearRect(0,0,w,h);if(!points.length)return;
const vals=points.map(p=>p.equity),lo=Math.min(...vals,10),hi=Math.max(...vals,10),span=Math.max(hi-lo,0.000001);
x.strokeStyle='#2c405d';x.lineWidth=1;for(let j=0;j<4;j++){let y=12+j*(h-24)/3;x.beginPath();x.moveTo(0,y);x.lineTo(w,y);x.stroke();}
x.strokeStyle='#a8f06b';x.lineWidth=3;x.beginPath();vals.forEach((v,i)=>{let px=i*(w-20)/Math.max(vals.length-1,1)+10,py=h-12-(v-lo)/span*(h-24);if(i===0)x.moveTo(px,py);else x.lineTo(px,py)});x.stroke();}
async function runBacktest(){const b=document.getElementById('run'),s=document.getElementById('status');b.disabled=true;s.textContent='در حال دریافت کندل‌ها و اجرای شبیه‌سازی...';
try{const symbol=encodeURIComponent(document.getElementById('symbol').value.trim()),tf=document.getElementById('tf').value;
const q=new URLSearchParams({timeframe:tf,candles:document.getElementById('candles').value,initial_equity:'10',
risk_per_trade:(Number(document.getElementById('risk').value)/100).toString(),
taker_fee_rate:(Number(document.getElementById('fee').value)/100).toString(),
slippage_rate:(Number(document.getElementById('slip').value)/100).toString(),
funding_rate_8h:(Number(document.getElementById('funding').value)/100).toString()});
const r=await fetch('/api/backtest/'+symbol+'?'+q),j=await r.json();if(!r.ok)throw Error(j.detail||'خطای دریافت اطلاعات');
const z=j.results;document.getElementById('results').hidden=false;
metric('final',fmt(z.final_equity,4)+' USDT',z.final_equity>=10);metric('pnl',(z.net_pnl>=0?'+':'')+fmt(z.net_pnl,4)+' USDT',z.net_pnl>=0);
metric('ret',(z.return_percent>=0?'+':'')+fmt(z.return_percent,2)+'%',z.return_percent>=0);metric('count',z.trade_count, null);
metric('win',fmt(z.win_rate_percent,2)+'%',z.win_rate_percent>=50);metric('dd',fmt(z.max_drawdown_percent,2)+'%',z.max_drawdown_percent<=10);
metric('pf',z.profit_factor===null?'—':String(z.profit_factor),typeof z.profit_factor==='number'&&z.profit_factor>=1);
metric('fees',fmt(z.estimated_fees,6)+' USDT');metric('slippage',fmt(z.estimated_slippage_cost,6)+' USDT');
metric('fundingCost',fmt(z.estimated_funding,6)+' USDT');metric('sharpe',fmt(z.trade_return_sharpe,3));
drawCurve(z.equity_curve||[]);const body=document.getElementById('trades');body.innerHTML='';
(z.trades||[]).slice().reverse().forEach((t,i)=>{const tr=document.createElement('tr');const vals=[i+1,t.side,fmt(t.entry,6),fmt(t.exit,6),(t.net_pnl>=0?'+':'')+fmt(t.net_pnl,6),fmt(t.fees,6),t.exit_reason,fmt(t.equity_after,4)];vals.forEach((v,k)=>{const td=document.createElement('td');td.textContent=v;if(k===4)td.className=t.net_pnl>=0?'good':'bad';tr.appendChild(td)});body.appendChild(tr)});
document.getElementById('assumptions').textContent='XT USDT-M • '+j.symbol+' • '+j.timeframe+' • کارمزد '+(Number(q.get('taker_fee_rate'))*100).toFixed(3)+'٪ هر سمت • لغزش '+(Number(q.get('slippage_rate'))*100).toFixed(3)+'٪ هر سمت • فاندینگ '+(Number(q.get('funding_rate_8h'))*100).toFixed(3)+'٪ هر ۸ ساعت. اگر حدضرر و حدسود در یک کندل لمس شوند، حدضرر اول فرض شده است.';
s.textContent='بک‌تست کامل شد — '+z.trade_count+' معامله';}
catch(e){s.textContent='خطا: '+e.message;}finally{b.disabled=false;}}
</script></body></html>"""

@app.get("/", response_class=HTMLResponse)
def dashboard():
    return DASHBOARD

@app.get("/health")
def health():
    return {"status":"ok","exchange":"XT USDT-M","mode":"public-data-and-virtual-backtest-only"}

@app.get("/api/signal/{symbol:path}")
def signal(symbol: str, timeframe: str = Query(default=None)):
    try:
        tf = timeframe or settings.default_timeframe
        frame = fetch_ohlcv(symbol=symbol,timeframe=tf,limit=settings.candle_limit,exchange_id=settings.exchange_id)
        result = build_signal(frame)
        result.update({"symbol":symbol,"timeframe":tf,"candles":len(frame),"exchange":"XT","mode":"research-only"})
        return result
    except (ValueError,RuntimeError) as exc:
        raise HTTPException(status_code=400,detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502,detail="XT public market data unavailable; retry later.")

@app.get("/api/analysis/advanced/{symbol:path}")
def advanced_analysis(symbol: str, timeframe: str = Query(default="1h")):
    if timeframe not in {"5m","15m","30m","1h","2h","4h","1d"}:
        raise HTTPException(status_code=400,detail="Unsupported timeframe")
    try:
        frame=fetch_ohlcv(symbol=symbol,timeframe=timeframe,limit=300,exchange_id=settings.exchange_id)
        if len(frame)>60: frame=frame.iloc[:-1].reset_index(drop=True)
        return {"symbol":symbol,"timeframe":timeframe,"exchange":"XT","mode":"research-only","analysis":analyze_ohlcv(frame)}
    except ValueError as exc:
        raise HTTPException(status_code=400,detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502,detail="XT public market data unavailable; retry later.")

@app.get("/api/backtest/{symbol:path}")
def backtest(symbol: str,
    timeframe: str = Query(default="1h"),
    candles: int = Query(default=1000,ge=150,le=1000),
    initial_equity: float = Query(default=10.0,gt=0,le=1000000),
    risk_per_trade: float = Query(default=0.005,gt=0,le=0.02),
    taker_fee_rate: float = Query(default=0.0006,ge=0,le=0.02),
    slippage_rate: float = Query(default=0.0005,ge=0,le=0.02),
    funding_rate_8h: float = Query(default=0.0001,ge=-0.02,le=0.02)):
    if timeframe not in {"5m","15m","30m","1h","2h","4h","1d"}:
        raise HTTPException(status_code=400,detail="Unsupported timeframe")
    try:
        frame=fetch_ohlcv(symbol=symbol,timeframe=timeframe,limit=candles,exchange_id=settings.exchange_id)
        if len(frame)>1: frame=frame.iloc[:-1].reset_index(drop=True)
        result=run_backtest(frame,initial_equity=initial_equity,risk_per_trade=risk_per_trade,
            fee_rate=taker_fee_rate,slippage_rate=slippage_rate,funding_rate_8h=funding_rate_8h)
        return {"symbol":symbol,"timeframe":timeframe,"exchange":"XT",
            "mode":"historical-research-only","fee_model":"XT VIP0 reference rates: maker 0.02%, taker 0.06% per side; user can override",
            "results":result}
    except ValueError as exc:
        raise HTTPException(status_code=400,detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502,detail="XT backtest data unavailable; verify symbol and network.")

@app.get("/api/consensus/{symbol:path}")
def consensus_analysis(symbol: str,timeframe: str=Query(default="1h"),exchange_id: str=Query(default="xt")):
    if timeframe not in {"5m","15m","30m","1h","2h","4h","1d"}:
        raise HTTPException(status_code=400,detail="Unsupported timeframe")
    if (exchange_id or "xt").lower()!="xt":
        raise HTTPException(status_code=400,detail="Only XT USDT-M is configured")
    try:
        frame=fetch_ohlcv(symbol=symbol,timeframe=timeframe,limit=300,exchange_id="xt")
        if len(frame)>60: frame=frame.iloc[:-1].reset_index(drop=True)
        from app.multi_agent import analyze_consensus
        return {"symbol":symbol,"exchange":"XT","timeframe":timeframe,"candles":len(frame),
            "mode":"read-only-research","analysis":analyze_consensus(frame)}
    except ValueError as exc:
        raise HTTPException(status_code=400,detail=str(exc))
    except Exception:
        raise HTTPException(status_code=502,detail="XT public market data unavailable; retry later.")
