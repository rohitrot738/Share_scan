from __future__ import annotations

import argparse
import json
import math
import os
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
    close = x["close"].astype(float)
    high = x["high"].astype(float)
    low = x["low"].astype(float)
    volume = x["volume"].astype(float)

    c = _safe_float(close.iloc[-1])
    if c <= 0:
        return {"score": 0.0}
    ema20 = _safe_float(close.ewm(span=20, adjust=False).mean().iloc[-1])
    ema50 = _safe_float(close.ewm(span=50, adjust=False).mean().iloc[-1])
    resistance = _safe_float(high.iloc[-21:-1].max(), c)
    support = _safe_float(low.iloc[-21:-1].min(), c)
    avg_vol20 = _safe_float(volume.iloc[-21:-1].mean(), 0.0)
    med_vol20 = _safe_float(volume.iloc[-21:-1].median(), 0.0)
    rvol = _safe_float(volume.iloc[-1] / max(avg_vol20, 1.0))
    distance = max(-10.0, min(30.0, (resistance - c) / max(resistance, 1e-9) * 100.0))
    range20 = (resistance - support) / max(c, 1e-9) * 100.0
    ret20 = (c / max(_safe_float(close.iloc[-21]), 1e-9) - 1.0) * 100.0
    ret5 = (c / max(_safe_float(close.iloc[-6]), 1e-9) - 1.0) * 100.0
    turnover = c * med_vol20

    score = 0.0
    score += 18 if c > ema20 else 5
    score += 14 if ema20 > ema50 else 2
    score += 18 if 0 <= distance <= 4 else 10 if distance <= 8 else 0
    score += 12 if 1.0 <= rvol <= 3.5 else 6 if rvol >= 0.7 else 0
    score += 12 if 0 <= ret20 <= 22 else 6 if ret20 > -5 else 0
    score += 10 if -3 <= ret5 <= 10 else 3
    score += 8 if range20 <= 14 else 4 if range20 <= 22 else 0
    score += 8 if turnover >= 5e7 else 4 if turnover >= 1e7 else 0

    return {
        "score": round(max(0.0, min(100.0, score)), 2),
        "close": round(c, 2),
        "resistance": round(resistance, 2),
        "support": round(support, 2),
        "distance_to_20d_high_pct": round(distance, 2),
        "rvol": round(rvol, 2),
        "ret5_pct": round(ret5, 2),
        "ret20_pct": round(ret20, 2),
        "turnover_proxy": round(turnover, 2),
    }


def stage1(universe: List[Instrument], batch_size: int = 150, shortlist: int = 120) -> pd.DataFrame:
    rows = []
    by_symbol = {x.yahoo_symbol: x for x in universe}
    symbols = list(by_symbol)
    for start in range(0, len(symbols), batch_size):
        batch = symbols[start:start + batch_size]
        data = download_batch(batch, period="8mo", interval="1d")
        for sym in batch:
            metrics = daily_prefilter_score(data.get(sym, pd.DataFrame()))
            if metrics.get("score", 0) <= 0:
                continue
            inst = by_symbol[sym]
            rows.append({"symbol": inst.symbol, "exchange": inst.exchange, "yahoo_symbol": inst.yahoo_symbol, "name": inst.name, **metrics})
    if not rows:
        return pd.DataFrame()
    return pd.DataFrame(rows).sort_values(["score", "turnover_proxy"], ascending=[False, False]).head(shortlist).reset_index(drop=True)


def _first_available_frame(tf_data: Dict[str, pd.DataFrame]) -> pd.DataFrame | None:
    for tf in ("15m", "30m", "1h"):
        df = tf_data.get(tf)
        if df is not None and not df.empty:
            return df
    return None


def _apply_true_orderbook(out: pd.DataFrame) -> pd.DataFrame:
    if out.empty:
        return out
    pairs = [(str(r.exchange), str(r.symbol)) for r in out.itertuples(index=False)]
    try:
        depth = fetch_depth_scores(pairs)
    except Exception as exc:
        print(f"[WARN] Groww market depth unavailable: {exc}")
        depth = {}

    defaults = {
        "depth_score": np.nan,
        "bid_qty_5": np.nan,
        "ask_qty_5": np.nan,
        "imbalance": np.nan,
        "best_bid": np.nan,
        "best_ask": np.nan,
        "spread_bps": np.nan,
        "depth_source": "OHLCV_FALLBACK",
    }
    for col, default in defaults.items():
        out[col] = default

    if not depth:
        out["true_depth_used"] = False
        return out

    for idx, row in out.iterrows():
        info = depth.get((str(row["exchange"]).upper(), str(row["symbol"])))
        if not info:
            continue
        for k, v in info.items():
            out.at[idx, k] = v

    has_depth = out["depth_score"].notna()
    out["true_depth_used"] = has_depth
    if has_depth.any():
        # Real order-book is a confirmation layer, not the whole strategy.
        # 15% weight prevents one fleeting queue snapshot from overpowering swing structure.
        spread_penalty = out["spread_bps"].fillna(0).clip(lower=0, upper=25) * 0.20
        blended = 0.85 * out["rank_score"] + 0.15 * out["depth_score"].fillna(50) - spread_penalty
        out.loc[has_depth, "rank_score"] = blended.loc[has_depth].clip(lower=0, upper=100).round(2)
    return out


def stage2(shortlisted: pd.DataFrame, cfg: ScannerConfig, top_n: int = 10) -> pd.DataFrame:
    rows = []
    for _, r in shortlisted.iterrows():
        sym = str(r["yahoo_symbol"])
        try:
            tf_data = fetch_multitimeframe(sym)
            if not tf_data:
                continue
            mtf = analyse_timeframes(tf_data, cfg)
            base_df = _first_available_frame(tf_data)
            ghost = ghost_trade_snapshot(base_df) if base_df is not None and len(base_df) >= 60 else {}
            ghost_score = _safe_float(ghost.get("ghost_score", 0.0))
            mtf_score = _safe_float(mtf.get("final_score", 0.0))
            pre_score = _safe_float(r.get("score", 0.0))
            final_rank = 0.55 * mtf_score + 0.25 * ghost_score + 0.20 * pre_score

            plan = ghost.get("trade_plan", {}) if isinstance(ghost, dict) else {}
            fb = ghost.get("false_breakout", {}) if isinstance(ghost, dict) else {}
            rows.append({
                "symbol": r["symbol"], "exchange": r["exchange"], "name": r.get("name", ""), "price": r.get("close", 0.0),
                "rank_score": round(final_rank, 2), "mtf_score": round(mtf_score, 2), "mtf_state": mtf.get("final_state", ""),
                "ghost_score": round(ghost_score, 2), "ghost_signal": ghost.get("signal", ""),
                "false_breakout_risk": _safe_float(fb.get("risk", 0.0)), "daily_support": r.get("support", 0.0),
                "daily_resistance": r.get("resistance", 0.0), "distance_to_20d_high_pct": r.get("distance_to_20d_high_pct", 0.0),
                "rvol_daily": r.get("rvol", 0.0), "entry": plan.get("entry"), "stop": plan.get("stop"),
                "target1": plan.get("target1"), "target2": plan.get("target2"), "timeframes_used": ",".join(sorted(tf_data.keys())),
            })
        except Exception as exc:
            print(f"[WARN] {sym}: {exc}")

    if not rows:
        return pd.DataFrame()
    out = pd.DataFrame(rows)
    out["rank_score"] = (out["rank_score"] - 0.12 * out["false_breakout_risk"].fillna(0)).clip(lower=0)
    out = _apply_true_orderbook(out)
    return out.sort_values("rank_score", ascending=False).head(top_n).reset_index(drop=True)


def save_results(top: pd.DataFrame, shortlist_df: pd.DataFrame) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    top.to_csv(OUTPUT_DIR / "top10.csv", index=False)
    shortlist_df.to_csv(OUTPUT_DIR / "stage1_shortlist.csv", index=False)
    payload = {"generated_at": datetime.now().astimezone().isoformat(), "count": int(len(top)), "results": top.replace({np.nan: None}).to_dict(orient="records") if not top.empty else []}
    (OUTPUT_DIR / "top10.json").write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def main():
    p = argparse.ArgumentParser(description="Share_scan live NSE/BSE two-stage scanner with optional Groww true market depth")
    p.add_argument("--top", type=int, default=10)
    p.add_argument("--shortlist", type=int, default=120)
    p.add_argument("--batch-size", type=int, default=150)
    p.add_argument("--nse-only", action="store_true")
    p.add_argument("--bse-only", action="store_true")
    args = p.parse_args()

    include_nse = not args.bse_only
    include_bse = not args.nse_only
    universe = build_universe(include_nse=include_nse, include_bse=include_bse)
    print(f"Universe: {len(universe)} symbols (NSE={include_nse}, BSE={include_bse})")
    if not universe:
        raise SystemExit("No symbols loaded. Check network/BSE endpoint or EXTRA_SYMBOLS_FILE.")
    s1 = stage1(universe, batch_size=args.batch_size, shortlist=args.shortlist)
    print(f"Stage-1 shortlist: {len(s1)}")
    if s1.empty:
        raise SystemExit("Stage-1 returned no candidates.")
    top = stage2(s1, ScannerConfig(), top_n=args.top)
    save_results(top, s1)
    if top.empty:
        print("No stage-2 candidates.")
    else:
        cols = ["symbol", "exchange", "price", "rank_score", "mtf_state", "ghost_signal", "false_breakout_risk", "depth_score", "imbalance", "spread_bps", "true_depth_used", "daily_support", "daily_resistance"]
        print(top[cols].to_string(index=False))


if __name__ == "__main__":
    main()
