from __future__ import annotations
import argparse, json, math, os, time
from datetime import datetime
from pathlib import Path
import numpy as np
import pandas as pd
from config import ScannerConfig
from ghost_trade_core import ghost_trade_snapshot
from groww_orderbook import fetch_depth_scores
from market_data import download_batch
from multi_timeframe import analyse_timeframes

OUTPUT_DIR=Path(os.getenv("SCAN_OUTPUT_DIR","scan_output"))
CACHE_DIR=Path(os.getenv("SCAN_CACHE_DIR",".scan_cache"))
CACHE_FILE=CACHE_DIR/"nse_stage1.csv"
TARGET_SECONDS=float(os.getenv("SCAN_TARGET_SECONDS","30"))
DEEP_LIMIT=int(os.getenv("SCAN_DEEP_LIMIT","120"))
DEPTH_LIMIT=int(os.getenv("SCAN_DEPTH_LIMIT","10"))
CACHE_MAX_AGE_HOURS=float(os.getenv("SCAN_CACHE_MAX_AGE_HOURS","18"))

def _safe_float(v,default=0.0):
    try:
        x=float(v); return x if math.isfinite(x) else default
    except Exception:return default

def daily_prefilter_score(df: pd.DataFrame) -> dict:
    """Fast 1-day ranking metrics used to build the NSE stage-1 cache."""
    if df is None or df.empty or len(df) < 20:
        return {"score":0.0,"turnover_proxy":0.0,"current_volume":0,"avg_volume20":0,"rvol":0.0,"support":0.0,"resistance":0.0,"close":0.0}
    x=df.copy()
    close=pd.to_numeric(x["close"],errors="coerce").dropna()
    vol=pd.to_numeric(x.get("volume",0),errors="coerce").fillna(0)
    if close.empty: return {"score":0.0,"turnover_proxy":0.0,"current_volume":0,"avg_volume20":0,"rvol":0.0,"support":0.0,"resistance":0.0,"close":0.0}
    c=float(close.iloc[-1]); v=float(vol.iloc[-1]); av=float(vol.tail(20).mean()) if len(vol) else 0.0
    rvol=v/av if av>0 else 0.0
    ret5=(c/float(close.iloc[-6])-1.0)*100 if len(close)>=6 and float(close.iloc[-6]) else 0.0
    ret20=(c/float(close.iloc[-21])-1.0)*100 if len(close)>=21 and float(close.iloc[-21]) else 0.0
    momentum=max(0.0,min(100.0,50.0+ret5*4.0+ret20*1.5))
    liquidity=max(0.0,min(100.0,20.0+math.log10(max(v*c,1.0))*4.0))
    volume_score=max(0.0,min(100.0,rvol*25.0))
    score=0.45*liquidity+0.30*volume_score+0.25*momentum
    return {"score":round(score,4),"turnover_proxy":round(v*c,2),"current_volume":int(v),"avg_volume20":int(av),"rvol":round(rvol,4),"support":float(pd.to_numeric(x["low"],errors="coerce").tail(20).min()),"resistance":float(pd.to_numeric(x["high"],errors="coerce").tail(20).max()),"close":c}

def _cache_fresh():
    return CACHE_FILE.exists() and (time.time()-CACHE_FILE.stat().st_mtime)<CACHE_MAX_AGE_HOURS*3600

def load_stage1_cache(shortlist:int)->pd.DataFrame:
    if not _cache_fresh():
        raise SystemExit("NSE stage1 cache missing/stale. Run cache builder first; live scan will not cold-build history.")
    df=pd.read_csv(CACHE_FILE)
    need={"symbol","exchange","yahoo_symbol","score","turnover_proxy","current_volume"}
    if not need.issubset(df.columns): raise SystemExit("NSE stage1 cache invalid. Run cache builder first.")
    if len(df)<shortlist: raise SystemExit(f"NSE stage1 cache too small ({len(df)} rows); need at least {shortlist}.")
    print(f"Stage-1 CACHE HIT: {len(df)} rows")
    return df.sort_values(["score","turnover_proxy"],ascending=[False,False]).head(shortlist).reset_index(drop=True)

def _prefilter_rows(df):
    return [{"symbol":r.symbol,"exchange":"NSE","name":getattr(r,"name",""),"price":getattr(r,"close",0),"volume":int(_safe_float(getattr(r,"current_volume",0))),"avg_volume20":int(_safe_float(getattr(r,"avg_volume20",0))),"rvol_daily":getattr(r,"rvol",0),"rank_score":_safe_float(getattr(r,"score",0)),"mtf_score":np.nan,"mtf_state":"PREFILTER","ghost_score":np.nan,"ghost_signal":"","false_breakout_risk":0.0,"daily_support":getattr(r,"support",0),"daily_resistance":getattr(r,"resistance",0),"entry":None,"stop":None,"target1":None,"target2":None,"timeframes_used":"cached-1d","analysis_tier":"PREFILTER"} for r in df.itertuples(index=False)]

def _analyse(r,d15,d1h,cfg):
    sym=str(r["yahoo_symbol"]); tf={}; a=d15.get(sym,pd.DataFrame()); b=d1h.get(sym,pd.DataFrame())
    if len(a)>=60:tf["15m"]=a
    if len(b)>=60:tf["1h"]=b
    if not tf:return None
    mtf=analyse_timeframes(tf,cfg); base=tf.get("15m",tf.get("1h")); ghost=ghost_trade_snapshot(base); gs=_safe_float(ghost.get("ghost_score",0)); ms=_safe_float(mtf.get("final_score",0)); plan=ghost.get("trade_plan",{}); fb=ghost.get("false_breakout",{})
    return {"symbol":r["symbol"],"exchange":"NSE","name":r.get("name",""),"price":r.get("close",0),"volume":int(_safe_float(r.get("current_volume",0))),"avg_volume20":int(_safe_float(r.get("avg_volume20",0))),"rvol_daily":r.get("rvol",0),"rank_score":round(.55*ms+.25*gs+.20*_safe_float(r.get("score",0)),2),"mtf_score":round(ms,2),"mtf_state":mtf.get("final_state",""),"ghost_score":round(gs,2),"ghost_signal":ghost.get("signal",""),"false_breakout_risk":_safe_float(fb.get("risk",0)),"daily_support":r.get("support",0),"daily_resistance":r.get("resistance",0),"entry":plan.get("entry"),"stop":plan.get("stop"),"target1":plan.get("target1"),"target2":plan.get("target2"),"timeframes_used":",".join(sorted(tf)),"analysis_tier":"DEEP"}

def apply_depth(out,limit):
    for c,d in {"depth_score":np.nan,"bid_qty_5":np.nan,"ask_qty_5":np.nan,"imbalance":np.nan,"best_bid":np.nan,"best_ask":np.nan,"spread_bps":np.nan,"depth_source":"OHLCV_FALLBACK"}.items():out[c]=d
    if not os.getenv("GROWW_ACCESS_TOKEN") or limit<=0:out["true_depth_used"]=False; return out
    try:depth=fetch_depth_scores([("NSE",str(r.symbol)) for r in out.sort_values("rank_score",ascending=False).head(limit).itertuples()])
    except Exception as e:print(f"[WARN] depth fallback: {e}"); depth={}
    for idx,row in out.iterrows():
        info=depth.get(("NSE",str(row.symbol)))
        if info:
            for k,v in info.items():out.at[idx,k]=v
    out["true_depth_used"]=out.depth_score.notna(); return out

def stage2(shortlisted,top_n,started):
    deep=shortlisted.head(min(DEEP_LIMIT,len(shortlisted))); syms=deep.yahoo_symbol.astype(str).tolist(); d15={}; d1h={}
    if TARGET_SECONDS-(time.perf_counter()-started)>10:
        try:d15=download_batch(syms,"10d","15m",retries=0)
        except Exception as e:print(f"[WARN] 15m skipped: {e}")
    if TARGET_SECONDS-(time.perf_counter()-started)>8:
        try:d1h=download_batch(syms,"60d","60m",retries=0)
        except Exception as e:print(f"[WARN] 1h skipped: {e}")
    cfg=ScannerConfig(); analysed=[]
    for r in deep.to_dict("records"):
        try:
            x=_analyse(r,d15,d1h,cfg)
            if x:analysed.append(x)
        except Exception:pass
    done={x["symbol"] for x in analysed}; rows=analysed+_prefilter_rows(shortlisted[~shortlisted.symbol.isin(done)])
    out=pd.DataFrame(rows); out["rank_score"]=(out.rank_score-.12*out.false_breakout_risk.fillna(0)).clip(lower=0); depth_n=min(DEPTH_LIMIT,len(out)) if TARGET_SECONDS-(time.perf_counter()-started)>5 else 0; out=apply_depth(out,depth_n); q=out.sort_values("rank_score",ascending=False).head(top_n).sort_values(["volume","rank_score"],ascending=[False,False]).reset_index(drop=True); q.insert(0,"volume_rank",np.arange(1,len(q)+1)); return q

def save_results(top,s1,runtime):
    OUTPUT_DIR.mkdir(parents=True,exist_ok=True); top.to_csv(OUTPUT_DIR/"top100_by_volume.csv",index=False); s1.to_csv(OUTPUT_DIR/"stage1_shortlist.csv",index=False); payload={"generated_at":datetime.now().astimezone().isoformat(),"mode":"30-SECOND NSE CACHE-ONLY MODE","target_seconds":TARGET_SECONDS,"runtime_seconds":round(runtime,2),"cache_used":True,"shortlist_size":len(s1),"count":len(top),"results":top.replace({np.nan:None}).to_dict("records")}; (OUTPUT_DIR/"top100_by_volume.json").write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding="utf-8")

def main():
    t0=time.perf_counter(); p=argparse.ArgumentParser(); p.add_argument("--top",type=int,default=100); p.add_argument("--shortlist",type=int,default=500); a=p.parse_args(); s1=load_stage1_cache(max(a.shortlist,a.top)); print(f"Stage-1 shortlist: {len(s1)}"); top=stage2(s1,a.top,t0); runtime=time.perf_counter()-t0; save_results(top,s1,runtime); print(f"NSE cache-only scan complete: {len(top)} in {runtime:.1f}s [{'TARGET_MET' if runtime<=TARGET_SECONDS else 'TARGET_MISSED'}]")
if __name__=="__main__":main()
