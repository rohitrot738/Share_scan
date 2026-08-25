from __future__ import annotations

import argparse
import json
import math
import os
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from typing import Dict, List

import numpy as np
import pandas as pd

from config import ScannerConfig
from ghost_trade_core import ghost_trade_snapshot
from market_data import Instrument, build_universe, download_batch, fetch_multitimeframe
from multi_timeframe import analyse_timeframes
from groww_orderbook import fetch_depth_scores

OUTPUT_DIR = Path(os.getenv("SCAN_OUTPUT_DIR", "scan_output"))


def _safe_float(v, default=0.0):
    try:
        x = float(v)
        return x if math.isfinite(x) else default
    except Exception:
        return default


def daily_prefilter_score(df: pd.DataFrame) -> Dict[str, float]:
    if df is None or len(df) < 60:
        return {"score": 0.0}
    x = df.tail(140).copy()
    close, high, low, volume = (x[c].astype(float) for c in ("close", "high", "low", "volume"))
    c = _safe_float(close.iloc[-1])
    if c <= 0:
        return {"score": 0.0}
    ema20 = _safe_float(close.ewm(span=20, adjust=False).mean().iloc[-1])
    ema50 = _safe_float(close.ewm(span=50, adjust=False).mean().iloc[-1])
    resistance = _safe_float(high.iloc[-21:-1].max(), c)
    support = _safe_float(low.iloc[-21:-1].min(), c)
    avg_vol20 = _safe_float(volume.iloc[-21:-1].mean(), 0.0)
    med_vol20 = _safe_float(volume.iloc[-21:-1].median(), 0.0)
    current_volume = _safe_float(volume.iloc[-1], 0.0)
    rvol = _safe_float(current_volume / max(avg_vol20, 1.0))
    distance = max(-10.0, min(30.0, (resistance - c) / max(resistance, 1e-9) * 100.0))
    range20 = (resistance - support) / max(c, 1e-9) * 100.0
    ret20 = (c / max(_safe_float(close.iloc[-21]), 1e-9) - 1.0) * 100.0
    ret5 = (c / max(_safe_float(close.iloc[-6]), 1e-9) - 1.0) * 100.0
    turnover = c * med_vol20
    score = (18 if c > ema20 else 5) + (14 if ema20 > ema50 else 2)
    score += 18 if 0 <= distance <= 4 else 10 if distance <= 8 else 0
    score += 12 if 1.0 <= rvol <= 3.5 else 6 if rvol >= 0.7 else 0
    score += 12 if 0 <= ret20 <= 22 else 6 if ret20 > -5 else 0
    score += 10 if -3 <= ret5 <= 10 else 3
    score += 8 if range20 <= 14 else 4 if range20 <= 22 else 0
    score += 8 if turnover >= 5e7 else 4 if turnover >= 1e7 else 0
    return {"score": round(max(0.0, min(100.0, score)), 2), "close": round(c, 2),
            "resistance": round(resistance, 2), "support": round(support, 2),
            "distance_to_20d_high_pct": round(distance, 2), "current_volume": int(current_volume),
            "avg_volume20": int(avg_vol20), "rvol": round(rvol, 2), "ret5_pct": round(ret5, 2),
            "ret20_pct": round(ret20, 2), "turnover_proxy": round(turnover, 2)}


def stage1(universe: List[Instrument], batch_size: int = 300, shortlist: int = 140) -> pd.DataFrame:
    rows, by_symbol = [], {x.yahoo_symbol: x for x in universe}
    symbols = list(by_symbol)
    for start in range(0, len(symbols), batch_size):
        batch = symbols[start:start + batch_size]
        data = download_batch(batch, period="6mo", interval="1d")
        for sym in batch:
            metrics = daily_prefilter_score(data.get(sym, pd.DataFrame()))
            if metrics.get("score", 0) <= 0:
                continue
            inst = by_symbol[sym]
            rows.append({"symbol": inst.symbol, "exchange": inst.exchange, "yahoo_symbol": inst.yahoo_symbol,
                         "name": inst.name, **metrics})
    if not rows:
        return pd.DataFrame()
    return pd.DataFrame(rows).sort_values(["score", "turnover_proxy"], ascending=[False, False]).head(shortlist).reset_index(drop=True)


def _first_available_frame(tf_data):
    for tf in ("15m", "30m", "1h"):
        df = tf_data.get(tf)
        if df is not None and not df.empty:
            return df
    return None


def _analyse_one(r: dict, cfg: ScannerConfig):
    sym = str(r["yahoo_symbol"])
    try:
        tf_data = fetch_multitimeframe(sym)
        if not tf_data:
            return None
        mtf = analyse_timeframes(tf_data, cfg)
        base_df = _first_available_frame(tf_data)
        ghost = ghost_trade_snapshot(base_df) if base_df is not None and len(base_df) >= 60 else {}
        ghost_score, mtf_score, pre_score = _safe_float(ghost.get("ghost_score", 0)), _safe_float(mtf.get("final_score", 0)), _safe_float(r.get("score", 0))
        plan = ghost.get("trade_plan", {}) if isinstance(ghost, dict) else {}
        fb = ghost.get("false_breakout", {}) if isinstance(ghost, dict) else {}
        return {"symbol": r["symbol"], "exchange": r["exchange"], "name": r.get("name", ""), "price": r.get("close", 0),
                "volume": int(_safe_float(r.get("current_volume", 0))), "avg_volume20": int(_safe_float(r.get("avg_volume20", 0))),
                "rank_score": round(.55*mtf_score+.25*ghost_score+.20*pre_score, 2), "mtf_score": round(mtf_score,2),
                "mtf_state": mtf.get("final_state", ""), "ghost_score": round(ghost_score,2), "ghost_signal": ghost.get("signal", ""),
                "false_breakout_risk": _safe_float(fb.get("risk",0)), "daily_support": r.get("support",0), "daily_resistance": r.get("resistance",0),
                "distance_to_20d_high_pct": r.get("distance_to_20d_high_pct",0), "rvol_daily": r.get("rvol",0),
                "entry": plan.get("entry"), "stop": plan.get("stop"), "target1": plan.get("target1"), "target2": plan.get("target2"),
                "timeframes_used": ",".join(sorted(tf_data.keys()))}
    except Exception as exc:
        print(f"[WARN] {sym}: {exc}")
        return None


def _apply_true_orderbook(out: pd.DataFrame, depth_limit: int = 100) -> pd.DataFrame:
    if out.empty: return out
    defaults = {"depth_score": np.nan, "bid_qty_5": np.nan, "ask_qty_5": np.nan, "imbalance": np.nan,
                "best_bid": np.nan, "best_ask": np.nan, "spread_bps": np.nan, "depth_source": "OHLCV_FALLBACK"}
    for c,d in defaults.items(): out[c]=d
    depth_rows = out.sort_values("rank_score", ascending=False).head(depth_limit)
    try: depth = fetch_depth_scores([(str(r.exchange),str(r.symbol)) for r in depth_rows.itertuples(index=False)])
    except Exception as exc:
        print(f"[WARN] Groww depth unavailable: {exc}"); depth={}
    for idx,row in out.iterrows():
        info=depth.get((str(row["exchange"]).upper(),str(row["symbol"])))
        if info:
            for k,v in info.items(): out.at[idx,k]=v
    has=out["depth_score"].notna(); out["true_depth_used"]=has
    if has.any():
        penalty=out["spread_bps"].fillna(0).clip(0,25)*.20
        blended=.85*out["rank_score"]+.15*out["depth_score"].fillna(50)-penalty
        out.loc[has,"rank_score"]=blended.loc[has].clip(0,100).round(2)
    return out


def stage2(shortlisted: pd.DataFrame, cfg: ScannerConfig, top_n=100, workers=8) -> pd.DataFrame:
    rows=[]
    records=shortlisted.to_dict("records")
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures=[pool.submit(_analyse_one,r,cfg) for r in records]
        for f in as_completed(futures):
            v=f.result()
            if v: rows.append(v)
    if not rows: return pd.DataFrame()
    out=pd.DataFrame(rows); out["rank_score"]=(out["rank_score"]-.12*out["false_breakout_risk"].fillna(0)).clip(lower=0)
    # FAST MODE: expensive live depth is only requested for final high-conviction candidates.
    out=_apply_true_orderbook(out, depth_limit=min(top_n,100))
    qualified=out.sort_values("rank_score",ascending=False).head(top_n).copy()
    qualified=qualified.sort_values(["volume","rank_score"],ascending=[False,False]).reset_index(drop=True)
    qualified.insert(0,"volume_rank",np.arange(1,len(qualified)+1)); return qualified


def save_results(top, shortlist_df):
    OUTPUT_DIR.mkdir(parents=True,exist_ok=True)
    top.to_csv(OUTPUT_DIR/"top100_by_volume.csv",index=False); shortlist_df.to_csv(OUTPUT_DIR/"stage1_shortlist.csv",index=False)
    payload={"generated_at":datetime.now().astimezone().isoformat(),"mode":"FAST MODE","count":int(len(top)),"sort":"volume_desc",
             "results":top.replace({np.nan:None}).to_dict("records") if not top.empty else []}
    (OUTPUT_DIR/"top100_by_volume.json").write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding="utf-8")


def main():
    p=argparse.ArgumentParser(description="Share_scan FAST MODE: rapid prefilter then parallel deep scan")
    p.add_argument("--top",type=int,default=100); p.add_argument("--shortlist",type=int,default=140); p.add_argument("--batch-size",type=int,default=300)
    p.add_argument("--workers",type=int,default=int(os.getenv("FAST_SCAN_WORKERS","8"))); p.add_argument("--nse-only",action="store_true"); p.add_argument("--bse-only",action="store_true")
    a=p.parse_args(); print(f"FAST MODE enabled: shortlist={a.shortlist}, workers={a.workers}")
    universe=build_universe(include_nse=not a.bse_only,include_bse=not a.nse_only)
    print(f"Universe: {len(universe)} symbols")
    if not universe: raise SystemExit("No symbols loaded")
    s1=stage1(universe,batch_size=a.batch_size,shortlist=max(a.shortlist,a.top)); print(f"FAST stage-1 shortlist: {len(s1)}")
    if s1.empty: raise SystemExit("Stage-1 returned no candidates")
    top=stage2(s1,ScannerConfig(),top_n=a.top,workers=a.workers); save_results(top,s1)
    print(f"FAST MODE complete: {len(top)} final candidates")
    if not top.empty:
        cols=["volume_rank","symbol","exchange","price","volume","rvol_daily","rank_score","mtf_state","ghost_signal","false_breakout_risk","depth_score","true_depth_used","daily_support","daily_resistance"]
        print(top[cols].to_string(index=False))

if __name__=="__main__": main()
