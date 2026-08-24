"""Smart-money style price-action proxies for Ghost Trade Pro.

These are deterministic OHLCV interpretations: order blocks, liquidity sweeps,
fair-value gaps, displacement and premium/discount zones.  They are not direct
visibility into hidden institutional orders.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict, List, Optional
import numpy as np
import pandas as pd


@dataclass
class FairValueGap:
    direction: str
    index: int
    low: float
    high: float
    size_pct: float
    filled_pct: float


@dataclass
class OrderBlock:
    direction: str
    index: int
    low: float
    high: float
    displacement: float
    freshness: float
    score: float


@dataclass
class Sweep:
    direction: str
    index: int
    level: float
    extreme: float
    reclaim: float
    volume_factor: float
    score: float


def safe_div(a, b, default=0.0):
    if b == 0 or not np.isfinite(b):
        return default
    return float(a / b)


def atr(df: pd.DataFrame, length: int = 14) -> pd.Series:
    pc = df["close"].shift(1)
    tr = pd.concat([
        df["high"] - df["low"],
        (df["high"] - pc).abs(),
        (df["low"] - pc).abs(),
    ], axis=1).max(axis=1)
    return tr.rolling(length, min_periods=5).mean()


def body_ratio(df: pd.DataFrame) -> pd.Series:
    rng = (df["high"] - df["low"]).replace(0, np.nan)
    return (df["close"] - df["open"]).abs() / rng


def displacement_series(df: pd.DataFrame) -> pd.Series:
    a = atr(df, 14).replace(0, np.nan)
    body = (df["close"] - df["open"]).abs()
    return (body / a).replace([np.inf, -np.inf], np.nan).fillna(0)


def detect_fvg(df: pd.DataFrame, min_atr_frac: float = 0.08) -> List[FairValueGap]:
    a = atr(df, 14)
    out: List[FairValueGap] = []
    for i in range(2, len(df)):
        atr_i = float(a.iloc[i]) if np.isfinite(a.iloc[i]) else 0.0
        # Bullish: current low above high two candles back.
        prev2_high = float(df["high"].iloc[i-2])
        curr_low = float(df["low"].iloc[i])
        if curr_low > prev2_high and (curr_low - prev2_high) >= atr_i * min_atr_frac:
            lo, hi = prev2_high, curr_low
            future = df.iloc[i+1:]
            penetration = 0.0
            if not future.empty:
                min_low = float(future["low"].min())
                penetration = np.clip((hi - min_low) / max(hi - lo, 1e-9), 0, 1)
            out.append(FairValueGap("BULL", i, lo, hi, safe_div(hi-lo, lo)*100, float(penetration)))
        prev2_low = float(df["low"].iloc[i-2])
        curr_high = float(df["high"].iloc[i])
        if curr_high < prev2_low and (prev2_low - curr_high) >= atr_i * min_atr_frac:
            lo, hi = curr_high, prev2_low
            future = df.iloc[i+1:]
            penetration = 0.0
            if not future.empty:
                max_high = float(future["high"].max())
                penetration = np.clip((max_high - lo) / max(hi - lo, 1e-9), 0, 1)
            out.append(FairValueGap("BEAR", i, lo, hi, safe_div(hi-lo, lo)*100, float(penetration)))
    return out


def _future_displacement(df: pd.DataFrame, i: int, direction: str, bars: int = 4) -> float:
    if i + 1 >= len(df):
        return 0.0
    end = min(len(df), i + 1 + bars)
    future = df.iloc[i+1:end]
    base = float(df["close"].iloc[i])
    a = atr(df, 14).iloc[i]
    a = float(a) if np.isfinite(a) and a > 0 else max(base * 0.005, 1e-9)
    if direction == "BULL":
        move = float(future["high"].max()) - base
    else:
        move = base - float(future["low"].min())
    return max(0.0, move / a)


def detect_order_blocks(df: pd.DataFrame, lookback: int = 120) -> List[OrderBlock]:
    start = max(0, len(df) - lookback)
    out: List[OrderBlock] = []
    for i in range(start, len(df)-2):
        o = float(df["open"].iloc[i])
        c = float(df["close"].iloc[i])
        lo = float(df["low"].iloc[i])
        hi = float(df["high"].iloc[i])
        # Last bearish candle before bullish displacement.
        if c < o:
            disp = _future_displacement(df, i, "BULL", 4)
            if disp >= 1.1:
                future = df.iloc[i+1:]
                revisits = future[(future["low"] <= hi) & (future["high"] >= lo)]
                touches = len(revisits)
                freshness = 1.0 / (1.0 + touches)
                age = len(df) - 1 - i
                age_factor = 1.0 / (1.0 + age / 60)
                score = min(100.0, 35*min(disp/2.5,1) + 35*freshness + 30*age_factor)
                out.append(OrderBlock("BULL", i, lo, hi, disp, freshness, score))
        if c > o:
            disp = _future_displacement(df, i, "BEAR", 4)
            if disp >= 1.1:
                future = df.iloc[i+1:]
                revisits = future[(future["low"] <= hi) & (future["high"] >= lo)]
                touches = len(revisits)
                freshness = 1.0 / (1.0 + touches)
                age = len(df) - 1 - i
                age_factor = 1.0 / (1.0 + age / 60)
                score = min(100.0, 35*min(disp/2.5,1) + 35*freshness + 30*age_factor)
                out.append(OrderBlock("BEAR", i, lo, hi, disp, freshness, score))
    return sorted(out, key=lambda x: x.score, reverse=True)


def equal_highs(df: pd.DataFrame, length: int = 60, tolerance_pct: float = 0.15) -> List[float]:
    r = df.tail(length)
    highs = r["high"].to_numpy(float)
    levels: List[float] = []
    for i in range(len(highs)):
        for j in range(i+2, len(highs)):
            tol = highs[i] * tolerance_pct / 100
            if abs(highs[i] - highs[j]) <= tol:
                levels.append((highs[i] + highs[j]) / 2)
    if not levels:
        return []
    levels.sort()
    merged = [levels[0]]
    for x in levels[1:]:
        if abs(x - merged[-1]) <= merged[-1] * tolerance_pct / 100:
            merged[-1] = (merged[-1] + x) / 2
        else:
            merged.append(x)
    return merged


def equal_lows(df: pd.DataFrame, length: int = 60, tolerance_pct: float = 0.15) -> List[float]:
    r = df.tail(length)
    lows = r["low"].to_numpy(float)
    levels: List[float] = []
    for i in range(len(lows)):
        for j in range(i+2, len(lows)):
            tol = lows[i] * tolerance_pct / 100
            if abs(lows[i] - lows[j]) <= tol:
                levels.append((lows[i] + lows[j]) / 2)
    if not levels:
        return []
    levels.sort()
    merged = [levels[0]]
    for x in levels[1:]:
        if abs(x - merged[-1]) <= merged[-1] * tolerance_pct / 100:
            merged[-1] = (merged[-1] + x) / 2
        else:
            merged.append(x)
    return merged


def detect_liquidity_sweeps(df: pd.DataFrame, length: int = 80) -> List[Sweep]:
    out: List[Sweep] = []
    highs = equal_highs(df, length)
    lows = equal_lows(df, length)
    avg_vol = df["volume"].rolling(20, min_periods=5).mean()
    start = max(1, len(df) - length)
    for i in range(start, len(df)):
        h = float(df["high"].iloc[i]); l = float(df["low"].iloc[i]); c = float(df["close"].iloc[i])
        vbase = float(avg_vol.iloc[i]) if np.isfinite(avg_vol.iloc[i]) and avg_vol.iloc[i] > 0 else 1.0
        vf = float(df["volume"].iloc[i]) / vbase
        for level in highs:
            if h > level and c < level:
                reclaim = safe_div(level-c, level)*100
                extreme = safe_div(h-level, level)*100
                score = min(100.0, 35*min(extreme/0.5,1) + 35*min(reclaim/0.5,1) + 30*min(vf/2,1))
                out.append(Sweep("BEAR_SWEEP", i, level, h, reclaim, vf, score))
        for level in lows:
            if l < level and c > level:
                reclaim = safe_div(c-level, level)*100
                extreme = safe_div(level-l, level)*100
                score = min(100.0, 35*min(extreme/0.5,1) + 35*min(reclaim/0.5,1) + 30*min(vf/2,1))
                out.append(Sweep("BULL_SWEEP", i, level, l, reclaim, vf, score))
    return sorted(out, key=lambda x: x.score, reverse=True)


def premium_discount(df: pd.DataFrame, length: int = 50) -> Dict[str, float | str]:
    r = df.tail(length)
    lo = float(r["low"].min()); hi = float(r["high"].max()); close = float(r["close"].iloc[-1])
    eq = (lo + hi) / 2
    pos = safe_div(close-lo, hi-lo, 0.5)
    zone = "DISCOUNT" if close < eq else "PREMIUM"
    return {"low":lo,"high":hi,"equilibrium":eq,"position":pos,"zone":zone}


def displacement_event(df: pd.DataFrame) -> Dict[str, float | bool | str]:
    ds = displacement_series(df)
    current = float(ds.iloc[-1])
    direction = "UP" if df["close"].iloc[-1] > df["open"].iloc[-1] else "DOWN"
    body = body_ratio(df).iloc[-1]
    body = 0.0 if not np.isfinite(body) else float(body)
    strong = current >= 1.25 and body >= 0.65
    return {"strength_atr":current,"body_ratio":body,"direction":direction,"strong":bool(strong)}


def recent_unfilled_fvg_score(df: pd.DataFrame) -> float:
    gaps = detect_fvg(df)
    bulls = [g for g in gaps if g.direction == "BULL" and g.filled_pct < 0.7]
    if not bulls:
        return 0.0
    g = max(bulls, key=lambda x: x.index)
    age = len(df)-1-g.index
    age_factor = 1/(1+age/20)
    size_factor = min(1.0, g.size_pct/0.8)
    fill_factor = 1-g.filled_pct
    return float(np.clip((0.35*age_factor+0.35*size_factor+0.30*fill_factor)*100,0,100))


def bullish_orderblock_proximity(df: pd.DataFrame) -> Dict[str, float]:
    blocks = [b for b in detect_order_blocks(df) if b.direction == "BULL"]
    close = float(df["close"].iloc[-1])
    if not blocks:
        return {"distance_pct":999.0,"block_score":0.0}
    best = min(blocks, key=lambda b: min(abs(close-b.low), abs(close-b.high)))
    if best.low <= close <= best.high:
        dist = 0.0
    else:
        edge = best.high if close > best.high else best.low
        dist = abs(close-edge)/max(abs(close),1e-9)*100
    return {"distance_pct":float(dist),"block_score":float(best.score),"low":best.low,"high":best.high}


def smart_money_report(df: pd.DataFrame) -> Dict[str, object]:
    gaps = detect_fvg(df)
    blocks = detect_order_blocks(df)
    sweeps = detect_liquidity_sweeps(df)
    bull_sweeps = [s for s in sweeps if s.direction == "BULL_SWEEP"]
    bear_sweeps = [s for s in sweeps if s.direction == "BEAR_SWEEP"]
    displacement = displacement_event(df)
    pdz = premium_discount(df)
    obp = bullish_orderblock_proximity(df)
    bull_liq = max([s.score for s in bull_sweeps], default=0.0)
    bear_liq = max([s.score for s in bear_sweeps], default=0.0)
    fvg_score = recent_unfilled_fvg_score(df)
    ob_score = obp.get("block_score",0.0) * max(0.0, 1-min(obp.get("distance_pct",999)/3,1))
    score = 0.30*min(bull_liq,100)+0.25*fvg_score+0.25*ob_score
    score += 12 if pdz["zone"] == "DISCOUNT" else 0
    score += 8 if displacement["strong"] and displacement["direction"] == "UP" else 0
    score -= 0.25*min(bear_liq,100)
    score = float(np.clip(score,0,100))
    return {
        "score":score,
        "premium_discount":pdz,
        "displacement":displacement,
        "bullish_orderblock_proximity":obp,
        "fvg_score":fvg_score,
        "bull_liquidity_sweep_score":bull_liq,
        "bear_liquidity_sweep_score":bear_liq,
        "recent_fvgs":[asdict(x) for x in gaps[-12:]],
        "best_order_blocks":[asdict(x) for x in blocks[:12]],
        "best_sweeps":[asdict(x) for x in sweeps[:12]],
    }
