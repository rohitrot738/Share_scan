from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import yfinance as yf

from false_breakout_filter import false_breakout_risk
from demand_supply import detect_zones
from ghost_trade_core import ghost_trade_snapshot

DEFAULT_SYMBOLS = [
    "RELIANCE", "HDFCBANK", "ICICIBANK", "SBIN", "INFY",
    "TCS", "ITC", "BHARTIARTL", "LT", "AXISBANK",
    "TATASTEEL", "HINDALCO", "PFC", "RECLTD", "CANBK",
    "BEL", "HAL", "IRFC", "IREDA", "RVNL",
]


def clean_frame(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    out = df.copy()
    out.columns = [str(c).lower() for c in out.columns]
    need = {"open", "high", "low", "close", "volume"}
    if not need.issubset(out.columns):
        return pd.DataFrame()
    return out[["open", "high", "low", "close", "volume"]].dropna(subset=["close"])


def analyse_symbol(df: pd.DataFrame) -> dict | None:
    if len(df) < 25:
        return None
    close=df["close"].astype(float); volume=df["volume"].astype(float).fillna(0)
    price=float(close.iloc[-1]); prev=float(close.iloc[-2]); avg20=float(volume.iloc[-21:-1].mean()); vol=float(volume.iloc[-1])
    rvol=vol/avg20 if avg20>0 else 0.0; change=((price/prev)-1)*100 if prev>0 else 0.0
    high20=float(df["high"].astype(float).iloc[-21:-1].max()); distance_high=((high20-price)/high20)*100 if high20>0 else 999.0
    score=min(max(rvol,0),4)*20 + max(min(change+2,6),0)*5 + max(20-max(distance_high,0)*3,0)

    fb=false_breakout_risk(df,window=20); fb_risk=float(fb.get("risk",100)); score=max(0,score-.15*fb_risk)
    zones=detect_zones(df,lookback=min(80,len(df)),pivot=3,max_zones=8)
    demand=[z for z in zones if z.get("type")=="demand" and float(z["price"])<=price]
    supply=[z for z in zones if z.get("type")=="supply" and float(z["price"])>=price]
    nd=max(demand,key=lambda z:float(z["price"])) if demand else None; ns=min(supply,key=lambda z:float(z["price"])) if supply else None
    dp=float(nd["price"]) if nd else None; sp=float(ns["price"]) if ns else None
    ds=float(nd.get("strength",0)) if nd else None; ss=float(ns.get("strength",0)) if ns else None
    if dp is not None and (price-dp)/max(price,1e-9)*100<=3: score+=min(8,2+2*max(ds or 0,0))
    if sp is not None and (sp-price)/max(price,1e-9)*100<=1: score-=4

    # Ghost is added to this same scanner, not as a separate mode/stage.
    ghost=ghost_trade_snapshot(df)
    ghost_score=float(ghost.get("ghost_score",0)); ghost_signal=str(ghost.get("signal","NEUTRAL"))
    flow=ghost.get("order_flow_proxy",{}) or {}; abnormal=ghost.get("abnormal_activity",{}) or {}; vwap=ghost.get("vwap_orb",{}) or {}; plan=ghost.get("trade_plan",{}) or {}
    # Keep the existing score as anchor; Ghost contributes a bounded confirmation adjustment.
    score += max(-10.0,min(10.0,(ghost_score-50.0)*0.20))

    return {
        "price":round(price,2),"volume":int(vol),"avg_volume20":int(avg20),"rvol":round(rvol,2),"change_pct":round(change,2),"distance_to_20d_high_pct":round(distance_high,2),
        "false_breakout_risk":round(fb_risk,2),"failed_up_breakout":bool(fb.get("failed_up_breakout",False)),"failed_down_breakout":bool(fb.get("failed_down_breakout",False)),
        "nearest_demand":round(dp,2) if dp is not None else None,"nearest_supply":round(sp,2) if sp is not None else None,"demand_strength":round(ds,2) if ds is not None else None,"supply_strength":round(ss,2) if ss is not None else None,"zone_count":len(zones),
        "ghost_score":round(ghost_score,2),"ghost_signal":ghost_signal,"order_flow":flow.get("dominance"),"absorption_score":ghost.get("absorption_score"),"abnormal_activity_score":abnormal.get("activity_score"),
        "above_vwap":vwap.get("above_vwap"),"low_volume_pullback":vwap.get("low_volume_pullback"),"green_confirmation":vwap.get("green_confirmation"),
        "entry":plan.get("entry"),"stop":plan.get("stop"),"target1":plan.get("target1"),"target2":plan.get("target2"),
        "score":round(max(0,min(100,score)),2),
    }


def scan(symbols:list[str])->tuple[list[dict],dict]:
    tickers=[f"{s}.NS" for s in symbols]
    raw=yf.download(tickers=" ".join(tickers),period="3mo",interval="1d",group_by="ticker",auto_adjust=False,threads=True,progress=False,timeout=30)
    rows=[]; errors={}
    for symbol,ticker in zip(symbols,tickers):
        try:
            part=raw[ticker] if len(tickers)>1 else raw; df=clean_frame(part); metrics=analyse_symbol(df)
            if not metrics: errors[symbol]="insufficient data"; continue
            rows.append({"symbol":symbol,"exchange":"NSE",**metrics})
        except Exception as exc: errors[symbol]=f"{type(exc).__name__}: {exc}"
    rows.sort(key=lambda x:(x["score"],x["volume"]),reverse=True)
    for i,row in enumerate(rows,1):row["rank"]=i
    return rows,errors


def main()->None:
    ap=argparse.ArgumentParser(); ap.add_argument("--limit",type=int,default=20); ap.add_argument("--output-dir",default="scan_results"); args=ap.parse_args()
    symbols=DEFAULT_SYMBOLS[:max(1,min(args.limit,len(DEFAULT_SYMBOLS)))]; rows,errors=scan(symbols)
    out=Path(args.output_dir); out.mkdir(parents=True,exist_ok=True)
    payload={"generated_at_utc":datetime.now(timezone.utc).isoformat(),"mode":"NSE_SCANNER","requested":len(symbols),"successful":len(rows),"errors":errors,"ranked":rows}
    (out/"latest.json").write_text(json.dumps(payload,indent=2),encoding="utf-8"); pd.DataFrame(rows).to_csv(out/"latest.csv",index=False)
    print(f"NSE scanner complete: {len(rows)}/{len(symbols)}")
    for r in rows[:10]: print(f"#{r['rank']} {r['symbol']} score={r['score']} ghost={r['ghost_score']} {r['ghost_signal']} flow={r['order_flow']}")

if __name__=="__main__":main()
