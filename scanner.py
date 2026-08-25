from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict

import pandas as pd
import yfinance as yf

from false_breakout_filter import false_breakout_risk
from demand_supply import detect_zones
from ghost_pro.ultimate_engine import multi_timeframe
from ghost_pro.case_training_fusion import fuse_with_technical

DEFAULT_SYMBOLS = [
    "RELIANCE", "HDFCBANK", "ICICIBANK", "SBIN", "INFY",
    "TCS", "ITC", "BHARTIARTL", "LT", "AXISBANK",
    "TATASTEEL", "HINDALCO", "PFC", "RECLTD", "CANBK",
    "BEL", "HAL", "IRFC", "IREDA", "RVNL",
]

BASE_FEEDS = {
    "1m": ("7d", "1m"), "5m": ("30d", "5m"), "15m": ("60d", "15m"),
    "30m": ("60d", "30m"), "1h": ("1y", "60m"), "1d": ("5y", "1d"),
}


def clean_frame(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty: return pd.DataFrame()
    out=df.copy(); out.columns=[str(c).lower() for c in out.columns]
    need={"open","high","low","close","volume"}
    if not need.issubset(out.columns): return pd.DataFrame()
    out=out[["open","high","low","close","volume"]].copy()
    for c in out.columns: out[c]=pd.to_numeric(out[c],errors="coerce")
    out=out.dropna(); out=out[out.volume>=0]; out.index=pd.to_datetime(out.index,errors="coerce")
    return out[~out.index.isna()].sort_index()


def _extract(raw,ticker,multi):
    try:return clean_frame(raw[ticker] if multi else raw)
    except Exception:return pd.DataFrame()


def _resample(df,rule):
    if df is None or df.empty:return pd.DataFrame()
    return df.resample(rule).agg({"open":"first","high":"max","low":"min","close":"last","volume":"sum"}).dropna()


def download_all_frames(symbols:list[str])->tuple[Dict[str,Dict[str,pd.DataFrame]],dict]:
    tickers=[f"{s}.NS" for s in symbols]; joined=" ".join(tickers); by={s:{} for s in symbols}; errors={}
    for tf,(period,interval) in BASE_FEEDS.items():
        try:
            raw=yf.download(tickers=joined,period=period,interval=interval,group_by="ticker",auto_adjust=False,threads=True,progress=False,timeout=45)
        except Exception as exc:
            errors[tf]=f"{type(exc).__name__}: {exc}"; continue
        for symbol,ticker in zip(symbols,tickers):
            df=_extract(raw,ticker,len(tickers)>1)
            if len(df)>=60: by[symbol][tf]=df.tail(2000)
    for symbol in symbols:
        f=by[symbol]
        if "1m" in f:
            x=_resample(f["1m"],"3min")
            if len(x)>=60:f["3m"]=x.tail(2000)
        if "5m" in f:
            x=_resample(f["5m"],"10min")
            if len(x)>=60:f["10m"]=x.tail(2000)
        if "1h" in f:
            x=_resample(f["1h"],"4h")
            if len(x)>=60:f["4h"]=x.tail(2000)
    return by,errors


def baseline_daily(day:pd.DataFrame)->dict:
    if day is None or len(day)<25: raise ValueError("daily baseline needs at least 25 candles")
    close=day.close.astype(float); volume=day.volume.astype(float).fillna(0); price=float(close.iloc[-1]); prev=float(close.iloc[-2])
    vol=float(volume.iloc[-1]); avg5=float(volume.iloc[-6:-1].mean()); avg10=float(volume.iloc[-11:-1].mean()); avg20=float(volume.iloc[-21:-1].mean()); avg50=float(volume.iloc[-51:-1].mean()) if len(volume)>=51 else float(volume.iloc[:-1].mean())
    med20=float(volume.iloc[-21:-1].median()); max20=float(volume.iloc[-21:-1].max()); min20=float(volume.iloc[-21:-1].min())
    rvol5=vol/avg5 if avg5>0 else 0; rvol10=vol/avg10 if avg10>0 else 0; rvol20=vol/avg20 if avg20>0 else 0; rvol50=vol/avg50 if avg50>0 else 0
    change=((price/prev)-1)*100 if prev>0 else 0; high20=float(day.high.iloc[-21:-1].max()); distance_high=((high20-price)/high20)*100 if high20>0 else 999
    turnover=price*vol; avg_turnover20=float((day.close.astype(float).iloc[-21:-1]*volume.iloc[-21:-1]).mean())
    base_score=min(max(rvol20,0),4)*20+max(min(change+2,6),0)*5+max(20-max(distance_high,0)*3,0)
    legacy_fb=false_breakout_risk(day,window=20); legacy_risk=float(legacy_fb.get("risk",100)); base_score=max(0,base_score-.15*legacy_risk)
    zones=detect_zones(day,lookback=min(80,len(day)),pivot=3,max_zones=8); demand=[z for z in zones if z.get("type")=="demand" and float(z["price"])<=price]; supply=[z for z in zones if z.get("type")=="supply" and float(z["price"])>=price]
    nd=max(demand,key=lambda z:float(z["price"])) if demand else None; ns=min(supply,key=lambda z:float(z["price"])) if supply else None; dp=float(nd["price"]) if nd else None; sp=float(ns["price"]) if ns else None; ds=float(nd.get("strength",0)) if nd else None; ss=float(ns.get("strength",0)) if ns else None
    if dp is not None and (price-dp)/max(price,1e-9)*100<=3: base_score+=min(8,2+2*max(ds or 0,0))
    if sp is not None and (sp-price)/max(price,1e-9)*100<=1: base_score-=4
    return {"price":round(price,2),"volume":int(vol),"avg_volume5":int(avg5),"avg_volume10":int(avg10),"avg_volume20":int(avg20),"avg_volume50":int(avg50),"median_volume20":int(med20),"max_volume20":int(max20),"min_volume20":int(min20),"rvol5":round(rvol5,2),"rvol10":round(rvol10,2),"rvol20":round(rvol20,2),"rvol50":round(rvol50,2),"turnover":round(turnover,2),"avg_turnover20":round(avg_turnover20,2),"change_pct":round(change,2),"distance_to_20d_high_pct":round(distance_high,2),"baseline_score":round(max(0,min(100,base_score)),2),"baseline_false_breakout_risk":round(legacy_risk,2),"failed_up_breakout":bool(legacy_fb.get("failed_up_breakout",False)),"failed_down_breakout":bool(legacy_fb.get("failed_down_breakout",False)),"nearest_demand":round(dp,2) if dp is not None else None,"nearest_supply":round(sp,2) if sp is not None else None,"demand_strength":round(ds,2) if ds is not None else None,"supply_strength":round(ss,2) if ss is not None else None,"zone_count":len(zones)}


def analyse_symbol(symbol:str,frames:Dict[str,pd.DataFrame])->dict:
    if "1d" not in frames: raise ValueError("1d frame unavailable")
    if len(frames)<2: raise ValueError("not enough Ghost timeframes")
    base=baseline_daily(frames["1d"]); technical=multi_timeframe(frames,symbol=symbol,capital=100000,risk_pct=.5); ghost=fuse_with_technical(technical,frames)
    case=ghost.get("case_training",{}) or {}; exec_tf=str(ghost.get("execution_timeframe","")); exec_rep=(ghost.get("timeframes",{}) or {}).get(exec_tf,{}) or {}; layers=exec_rep.get("layer_scores",{}) or {}
    flow_rep=exec_rep.get("flow",{}) or {}; flow=flow_rep.get("snapshot",{}) or {}; abnormal=flow_rep.get("abnormal_participation",{}) or {}; adv=exec_rep.get("advanced_confirmation",{}) or {}; setups=exec_rep.get("setups",{}) or {}; risk=exec_rep.get("risk",{}) or {}; plan=risk.get("plan",{}) or {}
    final=float(ghost.get("final_score",0)); volume_flow_score=float(layers.get("flow",flow.get("score",0)) or 0)
    return {**base,"score":round(final,2),"ghost_score":round(final,2),"ghost_state":str(ghost.get("final_state","IGNORE")),"ghost_confidence":round(float(ghost.get("confidence",0)),2),"false_breakout_risk":round(float(ghost.get("false_breakout_risk",100)),2),"advanced_veto_count":int(ghost.get("advanced_veto_count",0) or 0),"execution_timeframe":exec_tf,"timeframes_used":",".join(sorted(frames)),"timeframe_count":len(frames),"entry":ghost.get("entry"),"stop":ghost.get("stop"),"target1":ghost.get("target1"),"target2":ghost.get("target2"),"target3":ghost.get("target3"),"case_score":case.get("score"),"case_state":case.get("state"),"case_agreement":case.get("agreement"),"case_chase_votes":case.get("chase_votes"),"structure_score":layers.get("structure"),"volume_flow_score":round(volume_flow_score,2),"smart_money_score":layers.get("smart_money"),"momentum_score":layers.get("momentum"),"setup_score":layers.get("setup"),"risk_quality_score":layers.get("risk_quality"),"trap_safety_score":layers.get("trap_safety"),"advanced_confirmation_score":layers.get("advanced_confirmation"),"flow_rvol":flow.get("rvol"),"buy_pressure":flow.get("buy_pressure"),"sell_pressure":flow.get("sell_pressure"),"delta_proxy":flow.get("delta_proxy"),"absorption_buy":flow.get("absorption_buy"),"absorption_sell":flow.get("absorption_sell"),"volume_dryup":flow.get("dryup"),"volume_climax":flow.get("climax"),"effort_result":flow.get("effort_result"),"accumulation":flow.get("accumulation"),"distribution":flow.get("distribution"),"abnormal_rvol":abnormal.get("rvol"),"volume_zscore":abnormal.get("volume_z"),"large_participation_proxy":abnormal.get("large_participation_proxy"),"cvd_bullish_divergence":flow_rep.get("cvd_bullish_divergence"),"vwap":flow_rep.get("vwap"),"above_vwap":flow_rep.get("above_vwap"),"red_volume_dryup":flow_rep.get("red_volume_dryup"),"green_volume_expansion":flow_rep.get("green_volume_expansion"),"active_setups":setups.get("active_count"),"position_size":plan.get("position_size"),"risk_per_share":plan.get("risk_per_share"),"rr1":plan.get("rr1"),"rr2":plan.get("rr2"),"rr3":plan.get("rr3"),"advanced_regime":(adv.get("summary",{}) or {}).get("regime"),"ghost_status":"OK","ghost_details":ghost}


def scan(symbols:list[str])->tuple[list[dict],dict,dict]:
    all_frames,feed_errors=download_all_frames(symbols); rows=[]; errors={}
    for symbol in symbols:
        try: rows.append({"symbol":symbol,"exchange":"NSE",**analyse_symbol(symbol,all_frames.get(symbol,{}))})
        except Exception as exc: errors[symbol]=f"{type(exc).__name__}: {exc}"
    # Requested view: strongest volume/order-flow first; Ghost score is tiebreaker.
    rows.sort(key=lambda x:(x.get("volume_flow_score",0),x.get("rvol20",0),x.get("volume",0),x.get("ghost_score",0)),reverse=True)
    for i,row in enumerate(rows,1): row["rank"]=i
    return rows,errors,feed_errors


def main()->None:
    ap=argparse.ArgumentParser(); ap.add_argument("--limit",type=int,default=20); ap.add_argument("--output-dir",default="scan_results"); args=ap.parse_args(); symbols=DEFAULT_SYMBOLS[:max(1,min(args.limit,len(DEFAULT_SYMBOLS)))]; rows,errors,feed_errors=scan(symbols)
    out=Path(args.output_dir); out.mkdir(parents=True,exist_ok=True); ghost_ok=sum(1 for r in rows if r.get("ghost_status")=="OK")
    payload={"generated_at_utc":datetime.now(timezone.utc).isoformat(),"mode":"NSE_SCANNER_FULL_GHOST_VOLUME_RANKED","speed_modes_enabled":False,"ranking":"volume_flow_score,rvol20,volume,ghost_score DESC","requested":len(symbols),"successful":len(rows),"ghost_successful":ghost_ok,"feed_errors":feed_errors,"errors":errors,"ranked":rows}; (out/"latest.json").write_text(json.dumps(payload,indent=2,default=str),encoding="utf-8")
    csv_rows=[]
    for row in rows:
        flat=dict(row); flat["ghost_details"]=json.dumps(flat.get("ghost_details",{}),default=str,separators=(",",":")); csv_rows.append(flat)
    pd.DataFrame(csv_rows).to_csv(out/"latest.csv",index=False)
    print(f"NSE full Ghost + volume scanner complete: {len(rows)}/{len(symbols)}; ghost={ghost_ok}")
    for r in rows: print(f"#{r['rank']} {r['symbol']} volflow={r['volume_flow_score']} rvol20={r['rvol20']} vol={r['volume']} ghost={r['ghost_score']} {r['ghost_state']}")

if __name__=="__main__": main()
