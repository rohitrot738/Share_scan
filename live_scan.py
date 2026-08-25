from __future__ import annotations

import argparse, json, math, os, time
from concurrent.futures import ThreadPoolExecutor, as_completed
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

OUTPUT_DIR = Path(os.getenv("SCAN_OUTPUT_DIR", "scan_output"))
TARGET_SECONDS = float(os.getenv("SCAN_TARGET_SECONDS", "30"))
DEEP_LIMIT = int(os.getenv("SCAN_DEEP_LIMIT", "120"))
DEPTH_LIMIT = int(os.getenv("SCAN_DEPTH_LIMIT", "10"))
STAGE1_WORKERS = int(os.getenv("SCAN_STAGE1_WORKERS", "4"))


def _safe_float(v, default=0.0):
    try:
        x=float(v); return x if math.isfinite(x) else default
    except Exception: return default


def daily_prefilter_score(df: pd.DataFrame) -> Dict[str,float]:
    if df is None or len(df)<55: return {"score":0.0}
    x=df.tail(90); close=x["close"].astype(float); high=x["high"].astype(float); low=x["low"].astype(float); volume=x["volume"].astype(float)
    c=_safe_float(close.iloc[-1])
    if c<=0:return {"score":0.0}
    ema20=_safe_float(close.ewm(span=20,adjust=False).mean().iloc[-1]); ema50=_safe_float(close.ewm(span=50,adjust=False).mean().iloc[-1])
    resistance=_safe_float(high.iloc[-21:-1].max(),c); support=_safe_float(low.iloc[-21:-1].min(),c)
    avg20=_safe_float(volume.iloc[-21:-1].mean()); med20=_safe_float(volume.iloc[-21:-1].median()); vol=_safe_float(volume.iloc[-1])
    rvol=_safe_float(vol/max(avg20,1)); dist=max(-10,min(30,(resistance-c)/max(resistance,1e-9)*100))
    ret20=(c/max(_safe_float(close.iloc[-21]),1e-9)-1)*100; ret5=(c/max(_safe_float(close.iloc[-6]),1e-9)-1)*100; turnover=c*med20
    score=(18 if c>ema20 else 5)+(14 if ema20>ema50 else 2)
    score+=18 if 0<=dist<=4 else 10 if dist<=8 else 0
    score+=12 if 1<=rvol<=3.5 else 6 if rvol>=.7 else 0
    score+=12 if 0<=ret20<=22 else 6 if ret20>-5 else 0
    score+=10 if -3<=ret5<=10 else 3
    score+=8 if turnover>=5e7 else 4 if turnover>=1e7 else 0
    return {"score":round(max(0,min(100,score)),2),"close":round(c,2),"resistance":round(resistance,2),"support":round(support,2),
            "current_volume":int(vol),"avg_volume20":int(avg20),"rvol":round(rvol,2),"turnover_proxy":round(turnover,2),"distance_to_20d_high_pct":round(dist,2)}


def _scan_daily_batch(batch, by_symbol):
    rows=[]
    try:
        data=download_batch(batch,period="3mo",interval="1d",retries=0)
    except Exception as exc:
        print(f"[WARN] daily bulk batch failed: {exc}"); return rows
    for sym in batch:
        m=daily_prefilter_score(data.get(sym,pd.DataFrame()))
        if m.get("score",0)<=0: continue
        inst=by_symbol[sym]
        rows.append({"symbol":inst.symbol,"exchange":inst.exchange,"yahoo_symbol":inst.yahoo_symbol,"name":inst.name,**m})
    return rows


def stage1_bulk(universe: List[Instrument], batch_size=1200, shortlist=500) -> pd.DataFrame:
    by_symbol={x.yahoo_symbol:x for x in universe}; symbols=list(by_symbol); rows=[]
    batches=[symbols[i:i+batch_size] for i in range(0,len(symbols),batch_size)]
    print(f"Stage-1 full-universe bulk: {len(symbols)} symbols, {len(batches)} batches, workers={min(STAGE1_WORKERS,len(batches))}")
    with ThreadPoolExecutor(max_workers=max(1,min(STAGE1_WORKERS,len(batches)))) as pool:
        futures=[pool.submit(_scan_daily_batch,b,by_symbol) for b in batches]
        for f in as_completed(futures):
            try: rows.extend(f.result())
            except Exception as exc: print(f"[WARN] stage1 worker failed safely: {exc}")
    if not rows:return pd.DataFrame()
    return pd.DataFrame(rows).sort_values(["score","turnover_proxy"],ascending=[False,False]).head(shortlist).reset_index(drop=True)


def _analyse_from_bulk(r, d15, d1h, cfg):
    sym=str(r["yahoo_symbol"]); tf={}
    a=d15.get(sym,pd.DataFrame()); b=d1h.get(sym,pd.DataFrame())
    if len(a)>=60: tf["15m"]=a
    if len(b)>=60: tf["1h"]=b
    if not tf:return None
    mtf=analyse_timeframes(tf,cfg)
    base=tf.get("15m") if "15m" in tf else tf.get("1h")
    ghost=ghost_trade_snapshot(base) if base is not None and len(base)>=60 else {}
    gs=_safe_float(ghost.get("ghost_score",0)); ms=_safe_float(mtf.get("final_score",0)); ps=_safe_float(r.get("score",0))
    plan=ghost.get("trade_plan",{}) if isinstance(ghost,dict) else {}; fb=ghost.get("false_breakout",{}) if isinstance(ghost,dict) else {}
    return {"symbol":r["symbol"],"exchange":r["exchange"],"name":r.get("name",""),"price":r.get("close",0),"volume":int(_safe_float(r.get("current_volume",0))),
            "avg_volume20":int(_safe_float(r.get("avg_volume20",0))),"rvol_daily":r.get("rvol",0),"rank_score":round(.55*ms+.25*gs+.20*ps,2),
            "mtf_score":round(ms,2),"mtf_state":mtf.get("final_state",""),"ghost_score":round(gs,2),"ghost_signal":ghost.get("signal",""),
            "false_breakout_risk":_safe_float(fb.get("risk",0)),"daily_support":r.get("support",0),"daily_resistance":r.get("resistance",0),
            "entry":plan.get("entry"),"stop":plan.get("stop"),"target1":plan.get("target1"),"target2":plan.get("target2"),"timeframes_used":",".join(sorted(tf)),"analysis_tier":"DEEP"}


def _prefilter_rows(df: pd.DataFrame) -> List[dict]:
    rows=[]
    for r in df.to_dict("records"):
        rows.append({"symbol":r["symbol"],"exchange":r["exchange"],"name":r.get("name",""),"price":r.get("close",0),
                     "volume":int(_safe_float(r.get("current_volume",0))),"avg_volume20":int(_safe_float(r.get("avg_volume20",0))),
                     "rvol_daily":r.get("rvol",0),"rank_score":round(_safe_float(r.get("score",0)),2),"mtf_score":np.nan,"mtf_state":"PREFILTER",
                     "ghost_score":np.nan,"ghost_signal":"","false_breakout_risk":0.0,"daily_support":r.get("support",0),"daily_resistance":r.get("resistance",0),
                     "entry":None,"stop":None,"target1":None,"target2":None,"timeframes_used":"1d","analysis_tier":"PREFILTER"})
    return rows


def apply_depth(out, limit=10):
    if out.empty:return out
    defaults={"depth_score":np.nan,"bid_qty_5":np.nan,"ask_qty_5":np.nan,"imbalance":np.nan,"best_bid":np.nan,"best_ask":np.nan,"spread_bps":np.nan,"depth_source":"OHLCV_FALLBACK"}
    for c,d in defaults.items(): out[c]=d
    if not os.getenv("GROWW_ACCESS_TOKEN") or limit<=0:
        out["true_depth_used"]=False; return out
    try:
        rows=out.sort_values("rank_score",ascending=False).head(limit)
        depth=fetch_depth_scores([(str(r.exchange),str(r.symbol)) for r in rows.itertuples(index=False)])
    except Exception as exc:
        print(f"[WARN] depth fallback: {exc}"); depth={}
    for idx,row in out.iterrows():
        info=depth.get((str(row["exchange"]).upper(),str(row["symbol"])))
        if info:
            for k,v in info.items(): out.at[idx,k]=v
    out["true_depth_used"]=out["depth_score"].notna(); return out


def stage2_30s(shortlisted: pd.DataFrame, top_n=100, started=None) -> pd.DataFrame:
    # Keep all 500 shortlisted names eligible for final output, but spend expensive intraday work only on the strongest subset.
    deep=shortlisted.head(min(DEEP_LIMIT,len(shortlisted))).copy(); symbols=deep["yahoo_symbol"].astype(str).tolist()
    elapsed=time.perf_counter()-started if started else 0
    budget=max(0.0,TARGET_SECONDS-elapsed)
    print(f"30-SECOND MODE stage2: shortlist={len(shortlisted)}, deep={len(deep)}, remaining_budget={budget:.1f}s")
    d15={}; d1h={}
    if budget>5 and symbols:
        try: d15=download_batch(symbols,period="10d",interval="15m",retries=0)
        except Exception as exc: print(f"[WARN] 15m bulk skipped: {exc}")
    elapsed=time.perf_counter()-started if started else 0
    if TARGET_SECONDS-elapsed>5 and symbols:
        try: d1h=download_batch(symbols,period="60d",interval="60m",retries=0)
        except Exception as exc: print(f"[WARN] 1h bulk skipped: {exc}")

    cfg=ScannerConfig(); deep_rows=[]
    for r in deep.to_dict("records"):
        try:
            v=_analyse_from_bulk(r,d15,d1h,cfg)
            if v: deep_rows.append(v)
        except Exception as exc: print(f"[WARN] analyse {r.get('yahoo_symbol')}: {exc}")

    # Guaranteed-result fallback: remaining shortlisted names retain daily prefilter scores.
    deep_syms={r["symbol"] for r in deep_rows}
    fallback_df=shortlisted[~shortlisted["symbol"].isin(deep_syms)]
    rows=deep_rows+_prefilter_rows(fallback_df)
    if not rows:return pd.DataFrame()
    out=pd.DataFrame(rows)
    out["rank_score"]=(out["rank_score"]-.12*out["false_breakout_risk"].fillna(0)).clip(lower=0)

    elapsed=time.perf_counter()-started if started else 0
    # Protect the 30s target: order-book is optional and only attempted when meaningful budget remains.
    depth_limit=min(DEPTH_LIMIT,len(out)) if TARGET_SECONDS-elapsed>6 else 0
    if depth_limit==0: print("Depth skipped to protect 30-second target")
    out=apply_depth(out,depth_limit)
    q=out.sort_values("rank_score",ascending=False).head(top_n).copy()
    q=q.sort_values(["volume","rank_score"],ascending=[False,False]).reset_index(drop=True); q.insert(0,"volume_rank",np.arange(1,len(q)+1)); return q


def save_results(top, shortlist_df, runtime):
    OUTPUT_DIR.mkdir(parents=True,exist_ok=True)
    top.to_csv(OUTPUT_DIR/"top100_by_volume.csv",index=False); shortlist_df.to_csv(OUTPUT_DIR/"stage1_shortlist.csv",index=False)
    payload={"generated_at":datetime.now().astimezone().isoformat(),"mode":"30-SECOND MODE","target_seconds":TARGET_SECONDS,"runtime_seconds":round(runtime,2),
             "shortlist_size":int(len(shortlist_df)),"deep_limit":DEEP_LIMIT,"count":int(len(top)),"sort":"volume_desc",
             "results":top.replace({np.nan:None}).to_dict("records") if not top.empty else []}
    (OUTPUT_DIR/"top100_by_volume.json").write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding="utf-8")


def main():
    t0=time.perf_counter(); p=argparse.ArgumentParser()
    p.add_argument("--top",type=int,default=100); p.add_argument("--shortlist",type=int,default=500); p.add_argument("--batch-size",type=int,default=1200); p.add_argument("--nse-only",action="store_true"); p.add_argument("--bse-only",action="store_true")
    a=p.parse_args(); print(f"30-SECOND MODE target={TARGET_SECONDS:.0f}s, shortlist={a.shortlist}, deep_limit={DEEP_LIMIT}")
    universe=build_universe(include_nse=not a.bse_only,include_bse=not a.nse_only); print(f"Universe coverage: {len(universe)} symbols")
    if not universe: raise SystemExit("No symbols loaded")
    s1=stage1_bulk(universe,a.batch_size,max(a.shortlist,a.top)); print(f"Stage-1 shortlist: {len(s1)}")
    if s1.empty: raise SystemExit("No candidates")
    top=stage2_30s(s1,a.top,t0); runtime=time.perf_counter()-t0; save_results(top,s1,runtime)
    status="TARGET_MET" if runtime<=TARGET_SECONDS else "TARGET_MISSED"
    print(f"30-SECOND MODE complete: {len(top)} candidates in {runtime:.1f}s [{status}]")

if __name__=="__main__": main()
