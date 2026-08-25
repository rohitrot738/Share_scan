from __future__ import annotations
import argparse, json, math, os, time
from datetime import datetime
from pathlib import Path
from typing import Dict, List
import numpy as np
import pandas as pd
from config import ScannerConfig
from ghost_trade_core import ghost_trade_snapshot
from groww_orderbook import fetch_depth_scores
from market_data import Instrument, build_universe, download_batch
from multi_timeframe import analyse_timeframes

OUTPUT_DIR=Path(os.getenv("SCAN_OUTPUT_DIR","scan_output")); CACHE_DIR=Path(os.getenv("SCAN_CACHE_DIR",".scan_cache")); CACHE_FILE=CACHE_DIR/"nse_stage1.csv"
TARGET_SECONDS=float(os.getenv("SCAN_TARGET_SECONDS","30")); DEEP_LIMIT=int(os.getenv("SCAN_DEEP_LIMIT","120")); DEPTH_LIMIT=int(os.getenv("SCAN_DEPTH_LIMIT","10")); CACHE_MAX_AGE_HOURS=float(os.getenv("SCAN_CACHE_MAX_AGE_HOURS","18"))

def _safe_float(v,default=0.0):
    try:
        x=float(v); return x if math.isfinite(x) else default
    except Exception:return default

def daily_prefilter_score(df:pd.DataFrame)->Dict[str,float]:
    if df is None or len(df)<55:return {"score":0.0}
    x=df.tail(90); c=x.close.astype(float); h=x.high.astype(float); l=x.low.astype(float); v=x.volume.astype(float); last=_safe_float(c.iloc[-1])
    if last<=0:return {"score":0.0}
    e20=_safe_float(c.ewm(span=20,adjust=False).mean().iloc[-1]); e50=_safe_float(c.ewm(span=50,adjust=False).mean().iloc[-1]); res=_safe_float(h.iloc[-21:-1].max(),last); sup=_safe_float(l.iloc[-21:-1].min(),last); av=_safe_float(v.iloc[-21:-1].mean()); med=_safe_float(v.iloc[-21:-1].median()); vol=_safe_float(v.iloc[-1]); rv=_safe_float(vol/max(av,1)); dist=max(-10,min(30,(res-last)/max(res,1e-9)*100)); r20=(last/max(_safe_float(c.iloc[-21]),1e-9)-1)*100; r5=(last/max(_safe_float(c.iloc[-6]),1e-9)-1)*100; turn=last*med
    s=(18 if last>e20 else 5)+(14 if e20>e50 else 2)+(18 if 0<=dist<=4 else 10 if dist<=8 else 0)+(12 if 1<=rv<=3.5 else 6 if rv>=.7 else 0)+(12 if 0<=r20<=22 else 6 if r20>-5 else 0)+(10 if -3<=r5<=10 else 3)+(8 if turn>=5e7 else 4 if turn>=1e7 else 0)
    return {"score":round(max(0,min(100,s)),2),"close":round(last,2),"resistance":round(res,2),"support":round(sup,2),"current_volume":int(vol),"avg_volume20":int(av),"rvol":round(rv,2),"turnover_proxy":round(turn,2)}

def _cache_fresh():
    return CACHE_FILE.exists() and (time.time()-CACHE_FILE.stat().st_mtime)<CACHE_MAX_AGE_HOURS*3600

def _load_cache(shortlist):
    try:
        df=pd.read_csv(CACHE_FILE)
        need={"symbol","exchange","yahoo_symbol","score","turnover_proxy"}
        if need.issubset(df.columns) and len(df)>=shortlist:
            print(f"Stage-1 CACHE HIT: {len(df)} NSE candidates"); return df.sort_values(["score","turnover_proxy"],ascending=[False,False]).head(shortlist).reset_index(drop=True)
    except Exception as e:print(f"[WARN] cache unreadable: {e}")
    return pd.DataFrame()

def stage1_bulk(universe:List[Instrument],batch_size=1200,shortlist=500)->pd.DataFrame:
    if _cache_fresh():
        hit=_load_cache(shortlist)
        if not hit.empty:return hit
    selected=[x for x in universe if x.exchange=="NSE"]; by={x.yahoo_symbol:x for x in selected}; syms=list(by); rows=[]
    # One Yahoo request per large chunk; no per-symbol fallback. This cold build is cached for later live runs.
    for i in range(0,len(syms),batch_size):
        batch=syms[i:i+batch_size]; print(f"Cold cache build NSE batch {i//batch_size+1}/{(len(syms)+batch_size-1)//batch_size}")
        try:data=download_batch(batch,"3mo","1d",retries=0)
        except Exception as e:print(f"[WARN] cold batch failed: {e}"); continue
        for sym in batch:
            m=daily_prefilter_score(data.get(sym,pd.DataFrame()))
            if m.get("score",0)>0:
                inst=by[sym]; rows.append({"symbol":inst.symbol,"exchange":"NSE","yahoo_symbol":sym,"name":inst.name,**m})
    if not rows:return pd.DataFrame()
    df=pd.DataFrame(rows).sort_values(["score","turnover_proxy"],ascending=[False,False]).reset_index(drop=True); CACHE_DIR.mkdir(parents=True,exist_ok=True); df.to_csv(CACHE_FILE,index=False); print(f"Stage-1 cache saved: {len(df)} rows")
    return df.head(shortlist).reset_index(drop=True)

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
    OUTPUT_DIR.mkdir(parents=True,exist_ok=True); top.to_csv(OUTPUT_DIR/"top100_by_volume.csv",index=False); s1.to_csv(OUTPUT_DIR/"stage1_shortlist.csv",index=False); payload={"generated_at":datetime.now().astimezone().isoformat(),"mode":"30-SECOND NSE CACHED MODE","target_seconds":TARGET_SECONDS,"runtime_seconds":round(runtime,2),"cache_used":_cache_fresh(),"shortlist_size":len(s1),"count":len(top),"results":top.replace({np.nan:None}).to_dict("records")}; (OUTPUT_DIR/"top100_by_volume.json").write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding="utf-8")

def main():
    t0=time.perf_counter(); p=argparse.ArgumentParser(); p.add_argument("--top",type=int,default=100); p.add_argument("--shortlist",type=int,default=500); p.add_argument("--batch-size",type=int,default=1200); a=p.parse_args(); universe=build_universe(include_nse=True,include_bse=False); print(f"NSE universe: {len(universe)}")
    if not universe:raise SystemExit("No NSE symbols")
    s1=stage1_bulk(universe,a.batch_size,max(a.shortlist,a.top)); print(f"Stage-1 shortlist: {len(s1)}")
    if s1.empty:raise SystemExit("No candidates")
    top=stage2(s1,a.top,t0); runtime=time.perf_counter()-t0; save_results(top,s1,runtime); print(f"NSE cached scan complete: {len(top)} in {runtime:.1f}s [{'TARGET_MET' if runtime<=TARGET_SECONDS else 'TARGET_MISSED'}]")
if __name__=="__main__":main()
