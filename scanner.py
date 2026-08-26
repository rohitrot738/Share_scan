from __future__ import annotations

import argparse
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict

import pandas as pd

from demand_supply import detect_zones
from false_breakout_filter import false_breakout_risk
from ghost_pro.case_training_fusion import fuse_with_technical
from ghost_pro.ultimate_engine import multi_timeframe
from market_data import Instrument, build_universe, download_batch, resample_ohlcv


BASE_FEEDS = {
    "1m": ("7d", "1m"),
    "5m": ("30d", "5m"),
    "15m": ("60d", "15m"),
    "30m": ("60d", "30m"),
    "1h": ("1y", "60m"),
    "1d": ("5y", "1d"),
}


def _safe_float(value, default=0.0):
    try:
        x = float(value)
        return x if math.isfinite(x) else default
    except Exception:
        return default


def clean_frame(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    out = df.copy()
    out.columns = [str(c).lower() for c in out.columns]
    need = {"open", "high", "low", "close", "volume"}
    if not need.issubset(out.columns):
        return pd.DataFrame()
    out = out[["open", "high", "low", "close", "volume"]].copy()
    for c in out.columns:
        out[c] = pd.to_numeric(out[c], errors="coerce")
    out = out.dropna(subset=["open", "high", "low", "close"])
    out["volume"] = out["volume"].fillna(0).clip(lower=0)
    out.index = pd.to_datetime(out.index, errors="coerce")
    return out[~out.index.isna()].sort_index()


def baseline_daily(day: pd.DataFrame) -> dict:
    if day is None or len(day) < 25:
        raise ValueError("daily baseline needs at least 25 candles")
    close = day.close.astype(float)
    volume = day.volume.astype(float).fillna(0)
    price = float(close.iloc[-1])
    prev = float(close.iloc[-2])
    vol = float(volume.iloc[-1])
    avg5 = float(volume.iloc[-6:-1].mean())
    avg10 = float(volume.iloc[-11:-1].mean())
    avg20 = float(volume.iloc[-21:-1].mean())
    avg50 = float(volume.iloc[-51:-1].mean()) if len(volume) >= 51 else float(volume.iloc[:-1].mean())
    med20 = float(volume.iloc[-21:-1].median())
    max20 = float(volume.iloc[-21:-1].max())
    min20 = float(volume.iloc[-21:-1].min())
    rvol5 = vol / avg5 if avg5 > 0 else 0
    rvol10 = vol / avg10 if avg10 > 0 else 0
    rvol20 = vol / avg20 if avg20 > 0 else 0
    rvol50 = vol / avg50 if avg50 > 0 else 0
    change = ((price / prev) - 1) * 100 if prev > 0 else 0
    high20 = float(day.high.iloc[-21:-1].max())
    distance_high = ((high20 - price) / high20) * 100 if high20 > 0 else 999
    turnover = price * vol
    avg_turnover20 = float((day.close.astype(float).iloc[-21:-1] * volume.iloc[-21:-1]).mean())

    # Fast whole-market score: volume participation + positive momentum + breakout proximity.
    base_score = min(max(rvol20, 0), 4) * 20
    base_score += max(min(change + 2, 6), 0) * 5
    base_score += max(20 - max(distance_high, 0) * 3, 0)

    legacy_fb = false_breakout_risk(day, window=20)
    legacy_risk = float(legacy_fb.get("risk", 100))
    base_score = max(0, base_score - 0.15 * legacy_risk)

    zones = detect_zones(day, lookback=min(80, len(day)), pivot=3, max_zones=8)
    demand = [z for z in zones if z.get("type") == "demand" and float(z["price"]) <= price]
    supply = [z for z in zones if z.get("type") == "supply" and float(z["price"]) >= price]
    nd = max(demand, key=lambda z: float(z["price"])) if demand else None
    ns = min(supply, key=lambda z: float(z["price"])) if supply else None
    dp = float(nd["price"]) if nd else None
    sp = float(ns["price"]) if ns else None
    ds = float(nd.get("strength", 0)) if nd else None
    ss = float(ns.get("strength", 0)) if ns else None
    if dp is not None and (price - dp) / max(price, 1e-9) * 100 <= 3:
        base_score += min(8, 2 + 2 * max(ds or 0, 0))
    if sp is not None and (sp - price) / max(price, 1e-9) * 100 <= 1:
        base_score -= 4

    return {
        "price": round(price, 2),
        "volume": int(vol),
        "avg_volume5": int(avg5),
        "avg_volume10": int(avg10),
        "avg_volume20": int(avg20),
        "avg_volume50": int(avg50),
        "median_volume20": int(med20),
        "max_volume20": int(max20),
        "min_volume20": int(min20),
        "rvol5": round(rvol5, 2),
        "rvol10": round(rvol10, 2),
        "rvol20": round(rvol20, 2),
        "rvol50": round(rvol50, 2),
        "turnover": round(turnover, 2),
        "avg_turnover20": round(avg_turnover20, 2),
        "change_pct": round(change, 2),
        "distance_to_20d_high_pct": round(distance_high, 2),
        "baseline_score": round(max(0, min(100, base_score)), 2),
        "baseline_false_breakout_risk": round(legacy_risk, 2),
        "failed_up_breakout": bool(legacy_fb.get("failed_up_breakout", False)),
        "failed_down_breakout": bool(legacy_fb.get("failed_down_breakout", False)),
        "nearest_demand": round(dp, 2) if dp is not None else None,
        "nearest_supply": round(sp, 2) if sp is not None else None,
        "demand_strength": round(ds, 2) if ds is not None else None,
        "supply_strength": round(ss, 2) if ss is not None else None,
        "zone_count": len(zones),
    }


def load_market(market: str) -> list[Instrument]:
    market = market.upper()
    include_nse = market in {"NSE", "ALL"}
    include_bse = market in {"BSE", "ALL"}
    universe = build_universe(include_nse=include_nse, include_bse=include_bse)
    # Ordinary equity universe only; remove duplicate Yahoo instruments while preserving exchange metadata.
    dedup = {}
    for inst in universe:
        dedup[(inst.exchange, inst.yahoo_symbol)] = inst
    return list(dedup.values())


def stage1_full_market(universe: list[Instrument], batch_size: int = 200) -> tuple[list[dict], dict]:
    rows: list[dict] = []
    errors: dict = {}
    by_yahoo = {x.yahoo_symbol: x for x in universe}
    tickers = list(by_yahoo)
    total_batches = max(1, (len(tickers) + batch_size - 1) // batch_size)

    for start in range(0, len(tickers), batch_size):
        batch = tickers[start:start + batch_size]
        batch_no = start // batch_size + 1
        print(f"Stage-1 daily batch {batch_no}/{total_batches}: {len(batch)}", flush=True)
        try:
            data = download_batch(batch, "6mo", "1d", retries=1)
        except Exception as exc:
            errors[f"batch_{batch_no}"] = f"{type(exc).__name__}: {exc}"
            continue
        for ticker in batch:
            inst = by_yahoo[ticker]
            try:
                day = clean_frame(data.get(ticker, pd.DataFrame()))
                base = baseline_daily(day)
                # Skip essentially dead/illiquid instruments before expensive Ghost analysis.
                if base["price"] <= 0 or base["avg_turnover20"] <= 0:
                    continue
                rows.append({
                    "symbol": inst.symbol,
                    "exchange": inst.exchange,
                    "yahoo_symbol": inst.yahoo_symbol,
                    "name": inst.name,
                    **base,
                })
            except Exception as exc:
                errors[f"{inst.exchange}:{inst.symbol}"] = f"{type(exc).__name__}: {exc}"

    rows.sort(
        key=lambda x: (
            x.get("baseline_score", 0),
            x.get("rvol20", 0),
            x.get("turnover", 0),
        ),
        reverse=True,
    )
    for i, row in enumerate(rows, 1):
        row["stage1_rank"] = i
    return rows, errors


def download_all_frames(instruments: list[Instrument], batch_size: int = 60) -> tuple[Dict[str, Dict[str, pd.DataFrame]], dict]:
    by = {x.yahoo_symbol: {} for x in instruments}
    errors = {}
    tickers = list(by)

    for tf, (period, interval) in BASE_FEEDS.items():
        for start in range(0, len(tickers), batch_size):
            batch = tickers[start:start + batch_size]
            try:
                data = download_batch(batch, period, interval, retries=1)
            except Exception as exc:
                errors[f"{tf}:{start // batch_size + 1}"] = f"{type(exc).__name__}: {exc}"
                continue
            for ticker in batch:
                df = clean_frame(data.get(ticker, pd.DataFrame()))
                if len(df) >= 60:
                    by[ticker][tf] = df.tail(2000)

    for ticker in tickers:
        f = by[ticker]
        if "1m" in f:
            x = resample_ohlcv(f["1m"], "3min")
            if len(x) >= 60:
                f["3m"] = x.tail(2000)
        if "5m" in f:
            x = resample_ohlcv(f["5m"], "10min")
            if len(x) >= 60:
                f["10m"] = x.tail(2000)
        if "1h" in f:
            x = resample_ohlcv(f["1h"], "4h")
            if len(x) >= 60:
                f["4h"] = x.tail(2000)
    return by, errors


def analyse_symbol(inst: Instrument, frames: Dict[str, pd.DataFrame], stage1: dict) -> dict:
    if "1d" not in frames:
        raise ValueError("1d frame unavailable")
    if len(frames) < 2:
        raise ValueError("not enough Ghost timeframes")

    technical = multi_timeframe(frames, symbol=inst.symbol, capital=100000, risk_pct=.5)
    ghost = fuse_with_technical(technical, frames)
    case = ghost.get("case_training", {}) or {}
    exec_tf = str(ghost.get("execution_timeframe", ""))
    exec_rep = (ghost.get("timeframes", {}) or {}).get(exec_tf, {}) or {}
    layers = exec_rep.get("layer_scores", {}) or {}
    flow_rep = exec_rep.get("flow", {}) or {}
    flow = flow_rep.get("snapshot", {}) or {}
    abnormal = flow_rep.get("abnormal_participation", {}) or {}
    adv = exec_rep.get("advanced_confirmation", {}) or {}
    setups = exec_rep.get("setups", {}) or {}
    risk = exec_rep.get("risk", {}) or {}
    plan = risk.get("plan", {}) or {}

    final = float(ghost.get("final_score", 0))
    volume_flow_score = float(layers.get("flow", flow.get("score", 0)) or 0)
    state = str(ghost.get("final_state", "IGNORE"))
    action = {
        "EARLY": "EARLY BUY",
        "READY": "READY BUY",
        "CONFIRMED": "CONFIRMED BUY",
        "WATCH": "WATCH",
        "IGNORE": "SKIP",
        "AVOID": "SKIP",
    }.get(state, state)

    return {
        **stage1,
        "score": round(final, 2),
        "ghost_score": round(final, 2),
        "ghost_state": state,
        "action": action,
        "ghost_confidence": round(float(ghost.get("confidence", 0)), 2),
        "false_breakout_risk": round(float(ghost.get("false_breakout_risk", 100)), 2),
        "advanced_veto_count": int(ghost.get("advanced_veto_count", 0) or 0),
        "execution_timeframe": exec_tf,
        "timeframes_used": ",".join(sorted(frames)),
        "timeframe_count": len(frames),
        "entry": ghost.get("entry"),
        "stop": ghost.get("stop"),
        "target1": ghost.get("target1"),
        "target2": ghost.get("target2"),
        "target3": ghost.get("target3"),
        "case_score": case.get("score"),
        "case_state": case.get("state"),
        "case_agreement": case.get("agreement"),
        "case_chase_votes": case.get("chase_votes"),
        "structure_score": layers.get("structure"),
        "volume_flow_score": round(volume_flow_score, 2),
        "smart_money_score": layers.get("smart_money"),
        "momentum_score": layers.get("momentum"),
        "setup_score": layers.get("setup"),
        "risk_quality_score": layers.get("risk_quality"),
        "trap_safety_score": layers.get("trap_safety"),
        "advanced_confirmation_score": layers.get("advanced_confirmation"),
        "flow_rvol": flow.get("rvol"),
        "buy_pressure": flow.get("buy_pressure"),
        "sell_pressure": flow.get("sell_pressure"),
        "delta_proxy": flow.get("delta_proxy"),
        "absorption_buy": flow.get("absorption_buy"),
        "absorption_sell": flow.get("absorption_sell"),
        "volume_dryup": flow.get("dryup"),
        "volume_climax": flow.get("climax"),
        "effort_result": flow.get("effort_result"),
        "accumulation": flow.get("accumulation"),
        "distribution": flow.get("distribution"),
        "abnormal_rvol": abnormal.get("rvol"),
        "volume_zscore": abnormal.get("volume_z"),
        "large_participation_proxy": abnormal.get("large_participation_proxy"),
        "cvd_bullish_divergence": flow_rep.get("cvd_bullish_divergence"),
        "vwap": flow_rep.get("vwap"),
        "above_vwap": flow_rep.get("above_vwap"),
        "red_volume_dryup": flow_rep.get("red_volume_dryup"),
        "green_volume_expansion": flow_rep.get("green_volume_expansion"),
        "active_setups": setups.get("active_count"),
        "position_size": plan.get("position_size"),
        "risk_per_share": plan.get("risk_per_share"),
        "rr1": plan.get("rr1"),
        "rr2": plan.get("rr2"),
        "rr3": plan.get("rr3"),
        "advanced_regime": (adv.get("summary", {}) or {}).get("regime"),
        "ghost_status": "OK",
        "ghost_details": ghost,
    }


def scan_full_market(market: str, shortlist: int, deep: int, universe_limit: int = 0) -> tuple[list[dict], list[dict], dict, dict]:
    universe = load_market(market)
    if universe_limit > 0:
        universe = universe[:universe_limit]
    if not universe:
        raise RuntimeError(f"no instruments found for {market}")

    print(f"Universe loaded: {len(universe)} instruments ({market})", flush=True)
    stage1_rows, stage1_errors = stage1_full_market(universe)
    if not stage1_rows:
        raise RuntimeError("stage-1 produced no usable candidates")

    shortlist_rows = stage1_rows[:max(1, min(shortlist, len(stage1_rows)))]
    deep_rows = shortlist_rows[:max(1, min(deep, len(shortlist_rows)))]
    inst_map = {(x.exchange, x.symbol): x for x in universe}
    deep_instruments = [inst_map[(r["exchange"], r["symbol"])] for r in deep_rows]

    frames_by_ticker, feed_errors = download_all_frames(deep_instruments)
    rows = []
    errors = dict(stage1_errors)
    for r in deep_rows:
        inst = inst_map[(r["exchange"], r["symbol"])]
        try:
            rows.append(analyse_symbol(inst, frames_by_ticker.get(inst.yahoo_symbol, {}), r))
        except Exception as exc:
            errors[f"{inst.exchange}:{inst.symbol}"] = f"{type(exc).__name__}: {exc}"

    # User-requested priority: strongest volume/order-flow first, then relative volume,
    # Ghost conviction and low false-breakout risk.
    rows.sort(
        key=lambda x: (
            x.get("volume_flow_score", 0),
            x.get("rvol20", 0),
            x.get("ghost_score", 0),
            -x.get("false_breakout_risk", 100),
            x.get("volume", 0),
        ),
        reverse=True,
    )
    for i, row in enumerate(rows, 1):
        row["rank"] = i
    return rows, shortlist_rows, errors, feed_errors


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--market", choices=["NSE", "BSE", "ALL"], default="NSE")
    ap.add_argument("--top", type=int, default=100, help="final number of ranked results")
    ap.add_argument("--shortlist", type=int, default=300, help="Stage-1 candidates retained")
    ap.add_argument("--deep", type=int, default=120, help="candidates receiving full Ghost multi-timeframe analysis")
    ap.add_argument("--limit", type=int, default=0, help="optional universe cap; 0 scans the whole selected market")
    ap.add_argument("--output-dir", default="scan_results")
    args = ap.parse_args()

    rows, stage1, errors, feed_errors = scan_full_market(
        args.market,
        shortlist=max(args.shortlist, args.top),
        deep=max(args.deep, args.top),
        universe_limit=max(0, args.limit),
    )
    rows = rows[:max(1, args.top)]

    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    ghost_ok = sum(1 for r in rows if r.get("ghost_status") == "OK")
    payload = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "mode": "FULL_MARKET_TWO_STAGE_GHOST_VOLUME_RANKED",
        "market": args.market,
        "speed_modes_enabled": False,
        "ranking": "volume_flow_score,rvol20,ghost_score,false_breakout_risk ASC,volume DESC",
        "universe_cap": args.limit,
        "stage1_successful": len(stage1),
        "requested": len(rows),
        "successful": len(rows),
        "ghost_successful": ghost_ok,
        "feed_errors": feed_errors,
        "errors": errors,
        "ranked": rows,
    }
    (out / "latest.json").write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
    (out / "stage1_shortlist.json").write_text(json.dumps(stage1, indent=2, default=str), encoding="utf-8")

    csv_rows = []
    for row in rows:
        flat = dict(row)
        flat["ghost_details"] = json.dumps(flat.get("ghost_details", {}), default=str, separators=(",", ":"))
        csv_rows.append(flat)
    pd.DataFrame(csv_rows).to_csv(out / "latest.csv", index=False)
    pd.DataFrame(stage1).to_csv(out / "stage1_shortlist.csv", index=False)

    print(f"Full-market scanner complete: stage1={len(stage1)} deep_ok={len(rows)} ghost={ghost_ok}")
    for r in rows:
        print(
            f"#{r['rank']} {r['exchange']}:{r['symbol']} {r.get('action')} "
            f"volflow={r.get('volume_flow_score')} rvol20={r.get('rvol20')} "
            f"ghost={r.get('ghost_score')} false={r.get('false_breakout_risk')} "
            f"entry={r.get('entry')} sl={r.get('stop')} t1={r.get('target1')}"
        )


if __name__ == "__main__":
    main()
