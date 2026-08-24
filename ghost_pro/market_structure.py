"""Advanced market-structure engine for Share_scan / Ghost Trade Pro.

This module intentionally keeps logic explicit and inspectable.  It works on
OHLCV data and does not pretend to have true exchange order-book information.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict, List, Optional, Tuple
import math
import numpy as np
import pandas as pd


@dataclass
class SwingPoint:
    index: int
    kind: str
    price: float
    strength: float


@dataclass
class Zone:
    kind: str
    low: float
    high: float
    touches: int
    freshness: float
    strength: float


@dataclass
class StructureSnapshot:
    trend: str
    bos_up: bool
    bos_down: bool
    choch_up: bool
    choch_down: bool
    hh_count: int
    hl_count: int
    lh_count: int
    ll_count: int
    support: float
    resistance: float
    compression: float
    expansion: float
    score: float


def _safe_div(a: float, b: float, default: float = 0.0) -> float:
    if b == 0 or not np.isfinite(b):
        return default
    return float(a / b)


def _pct(a: float, b: float) -> float:
    return _safe_div(a - b, abs(b), 0.0) * 100.0


def rolling_atr(df: pd.DataFrame, length: int = 14) -> pd.Series:
    prev = df["close"].shift(1)
    tr = pd.concat([
        df["high"] - df["low"],
        (df["high"] - prev).abs(),
        (df["low"] - prev).abs(),
    ], axis=1).max(axis=1)
    return tr.rolling(length, min_periods=max(2, length // 3)).mean()


def pivot_highs(df: pd.DataFrame, left: int = 3, right: int = 3) -> List[SwingPoint]:
    highs = df["high"].to_numpy(float)
    out: List[SwingPoint] = []
    for i in range(left, len(df) - right):
        window = highs[i-left:i+right+1]
        if highs[i] >= np.nanmax(window):
            local_min = float(np.nanmin(window))
            strength = _safe_div(highs[i] - local_min, max(abs(highs[i]), 1e-9)) * 100
            out.append(SwingPoint(i, "H", float(highs[i]), strength))
    return out


def pivot_lows(df: pd.DataFrame, left: int = 3, right: int = 3) -> List[SwingPoint]:
    lows = df["low"].to_numpy(float)
    out: List[SwingPoint] = []
    for i in range(left, len(df) - right):
        window = lows[i-left:i+right+1]
        if lows[i] <= np.nanmin(window):
            local_max = float(np.nanmax(window))
            strength = _safe_div(local_max - lows[i], max(abs(lows[i]), 1e-9)) * 100
            out.append(SwingPoint(i, "L", float(lows[i]), strength))
    return out


def merge_swings(highs: List[SwingPoint], lows: List[SwingPoint]) -> List[SwingPoint]:
    pts = sorted(highs + lows, key=lambda x: x.index)
    if not pts:
        return []
    result = [pts[0]]
    for p in pts[1:]:
        last = result[-1]
        if p.kind != last.kind:
            result.append(p)
            continue
        if p.kind == "H" and p.price >= last.price:
            result[-1] = p
        elif p.kind == "L" and p.price <= last.price:
            result[-1] = p
    return result


def classify_swings(swings: List[SwingPoint]) -> Dict[str, int]:
    hh = hl = lh = ll = 0
    last_h: Optional[float] = None
    last_l: Optional[float] = None
    for p in swings:
        if p.kind == "H":
            if last_h is not None:
                if p.price > last_h:
                    hh += 1
                elif p.price < last_h:
                    lh += 1
            last_h = p.price
        else:
            if last_l is not None:
                if p.price > last_l:
                    hl += 1
                elif p.price < last_l:
                    ll += 1
            last_l = p.price
    return {"hh": hh, "hl": hl, "lh": lh, "ll": ll}


def structure_trend(swings: List[SwingPoint]) -> str:
    c = classify_swings(swings)
    bull = c["hh"] + c["hl"]
    bear = c["lh"] + c["ll"]
    if bull >= bear + 2:
        return "BULL"
    if bear >= bull + 2:
        return "BEAR"
    return "RANGE"


def break_of_structure(df: pd.DataFrame, swings: List[SwingPoint], lookback: int = 30) -> Tuple[bool, bool]:
    if len(df) < 2:
        return False, False
    cutoff = max(0, len(df) - lookback)
    hs = [p.price for p in swings if p.kind == "H" and p.index >= cutoff and p.index < len(df)-1]
    ls = [p.price for p in swings if p.kind == "L" and p.index >= cutoff and p.index < len(df)-1]
    close = float(df["close"].iloc[-1])
    prev = float(df["close"].iloc[-2])
    bos_up = bool(hs and prev <= max(hs) and close > max(hs))
    bos_down = bool(ls and prev >= min(ls) and close < min(ls))
    return bos_up, bos_down


def change_of_character(df: pd.DataFrame, swings: List[SwingPoint]) -> Tuple[bool, bool]:
    trend = structure_trend(swings[:-1] if len(swings) > 1 else swings)
    bos_up, bos_down = break_of_structure(df, swings)
    return trend == "BEAR" and bos_up, trend == "BULL" and bos_down


def nearest_levels(df: pd.DataFrame, swings: List[SwingPoint], max_age: int = 80) -> Tuple[float, float]:
    close = float(df["close"].iloc[-1])
    cutoff = max(0, len(df) - max_age)
    supports = [p.price for p in swings if p.index >= cutoff and p.price <= close]
    resistances = [p.price for p in swings if p.index >= cutoff and p.price >= close]
    support = max(supports) if supports else float(df["low"].tail(max_age).min())
    resistance = min(resistances) if resistances else float(df["high"].tail(max_age).max())
    return float(support), float(resistance)


def zone_from_cluster(points: List[SwingPoint], atr_now: float, kind: str) -> Optional[Zone]:
    if not points:
        return None
    prices = np.array([p.price for p in points], dtype=float)
    center = float(np.median(prices))
    width = max(float(atr_now) * 0.35, center * 0.001)
    low, high = center - width, center + width
    touches = len(points)
    age = max(p.index for p in points) - min(p.index for p in points) if touches > 1 else 0
    freshness = 1.0 / (1.0 + age / 25.0)
    strength = min(100.0, touches * 12.0 + np.mean([p.strength for p in points]) * 5.0)
    return Zone(kind, low, high, touches, freshness, strength)


def cluster_zones(df: pd.DataFrame, swings: List[SwingPoint], tolerance_atr: float = 0.7) -> List[Zone]:
    atr = rolling_atr(df).iloc[-1]
    if not np.isfinite(atr):
        atr = float((df["high"] - df["low"]).tail(20).mean())
    remaining = list(swings)
    zones: List[Zone] = []
    while remaining:
        seed = remaining.pop(0)
        cluster = [seed]
        leftovers = []
        for p in remaining:
            if abs(p.price - seed.price) <= atr * tolerance_atr and p.kind == seed.kind:
                cluster.append(p)
            else:
                leftovers.append(p)
        remaining = leftovers
        kind = "SUPPLY" if seed.kind == "H" else "DEMAND"
        z = zone_from_cluster(cluster, float(atr), kind)
        if z:
            zones.append(z)
    return sorted(zones, key=lambda z: z.strength, reverse=True)


def candle_compression(df: pd.DataFrame, fast: int = 6, slow: int = 24) -> float:
    rng = (df["high"] - df["low"]).abs()
    f = float(rng.tail(fast).mean())
    s = float(rng.tail(slow).mean())
    if s <= 0:
        return 0.0
    ratio = f / s
    return float(np.clip(1.0 - ratio, 0.0, 1.0))


def volatility_expansion(df: pd.DataFrame, fast: int = 3, slow: int = 20) -> float:
    ret = df["close"].pct_change().abs()
    f = float(ret.tail(fast).mean())
    s = float(ret.tail(slow).mean())
    if s <= 0:
        return 0.0
    return float(np.clip((f / s - 1.0) / 2.0, 0.0, 1.0))


def range_position(df: pd.DataFrame, length: int = 20) -> float:
    lo = float(df["low"].tail(length).min())
    hi = float(df["high"].tail(length).max())
    cl = float(df["close"].iloc[-1])
    return float(np.clip(_safe_div(cl - lo, hi - lo, 0.5), 0.0, 1.0))


def slope(series: pd.Series, length: int = 20) -> float:
    y = series.tail(length).to_numpy(float)
    if len(y) < 3 or np.allclose(y, y[0]):
        return 0.0
    x = np.arange(len(y), dtype=float)
    m = np.polyfit(x, y, 1)[0]
    base = np.nanmean(np.abs(y))
    return _safe_div(float(m), float(base), 0.0) * 100.0


def trend_quality(df: pd.DataFrame) -> float:
    s5 = slope(df["close"], 5)
    s10 = slope(df["close"], 10)
    s20 = slope(df["close"], 20)
    agreement = np.mean([s5 > 0, s10 > 0, s20 > 0])
    magnitude = min(1.0, (abs(s5) + abs(s10) + abs(s20)) / 3.0 / 0.15)
    return float(100.0 * (0.6 * agreement + 0.4 * magnitude))


def shallow_pullback_score(df: pd.DataFrame, impulse_len: int = 20, pullback_len: int = 8) -> float:
    if len(df) < impulse_len + pullback_len:
        return 0.0
    pre = df.iloc[-(impulse_len + pullback_len):-pullback_len]
    pb = df.iloc[-pullback_len:]
    impulse_low = float(pre["low"].min())
    impulse_high = float(pre["high"].max())
    impulse = impulse_high - impulse_low
    if impulse <= 0:
        return 0.0
    pb_low = float(pb["low"].min())
    retrace = (impulse_high - pb_low) / impulse
    return float(np.clip(1.0 - retrace / 0.65, 0.0, 1.0) * 100.0)


def support_rejection_score(df: pd.DataFrame, support: float, length: int = 12) -> float:
    recent = df.tail(length)
    atr = rolling_atr(df).iloc[-1]
    if not np.isfinite(atr) or atr <= 0:
        atr = max(float((recent["high"] - recent["low"]).mean()), 1e-9)
    near = (recent["low"] - support).abs() <= atr * 0.6
    touched = recent[near]
    if touched.empty:
        return 0.0
    closes_above = float((touched["close"] > support).mean())
    lower_wicks = (touched[["open", "close"]].min(axis=1) - touched["low"]).clip(lower=0)
    ranges = (touched["high"] - touched["low"]).replace(0, np.nan)
    wick_ratio = float((lower_wicks / ranges).fillna(0).mean())
    return float(np.clip(0.65 * closes_above + 0.35 * wick_ratio * 2, 0, 1) * 100)


def resistance_pressure_score(df: pd.DataFrame, resistance: float, length: int = 12) -> float:
    recent = df.tail(length)
    atr = rolling_atr(df).iloc[-1]
    if not np.isfinite(atr) or atr <= 0:
        atr = max(float((recent["high"] - recent["low"]).mean()), 1e-9)
    dist = (resistance - recent["close"]).clip(lower=0)
    near = dist <= atr
    if near.sum() == 0:
        return 0.0
    closeness = 1.0 - float((dist[near] / atr).mean())
    lows_rising = slope(recent["low"], min(length, len(recent))) > 0
    return float(np.clip(0.7 * closeness + 0.3 * float(lows_rising), 0, 1) * 100)


def build_structure_snapshot(df: pd.DataFrame, left: int = 3, right: int = 3) -> StructureSnapshot:
    highs = pivot_highs(df, left, right)
    lows = pivot_lows(df, left, right)
    swings = merge_swings(highs, lows)
    counts = classify_swings(swings)
    trend = structure_trend(swings)
    bos_up, bos_down = break_of_structure(df, swings)
    choch_up, choch_down = change_of_character(df, swings)
    support, resistance = nearest_levels(df, swings)
    compression = candle_compression(df)
    expansion = volatility_expansion(df)
    tq = trend_quality(df)
    sr = support_rejection_score(df, support)
    rp = resistance_pressure_score(df, resistance)
    bull_bias = (counts["hh"] + counts["hl"]) / max(1, sum(counts.values()))
    score = 0.28 * tq + 0.20 * sr + 0.18 * rp + 18 * compression + 10 * expansion + 6 * bull_bias
    score += 8 if bos_up else 0
    score += 5 if choch_up else 0
    score -= 10 if bos_down else 0
    score -= 6 if choch_down else 0
    score = float(np.clip(score, 0, 100))
    return StructureSnapshot(
        trend=trend,
        bos_up=bos_up,
        bos_down=bos_down,
        choch_up=choch_up,
        choch_down=choch_down,
        hh_count=counts["hh"],
        hl_count=counts["hl"],
        lh_count=counts["lh"],
        ll_count=counts["ll"],
        support=support,
        resistance=resistance,
        compression=compression,
        expansion=expansion,
        score=score,
    )


def market_structure_report(df: pd.DataFrame) -> Dict[str, object]:
    highs = pivot_highs(df)
    lows = pivot_lows(df)
    swings = merge_swings(highs, lows)
    zones = cluster_zones(df, swings)
    snap = build_structure_snapshot(df)
    return {
        "snapshot": asdict(snap),
        "swings": [asdict(s) for s in swings[-20:]],
        "zones": [asdict(z) for z in zones[:12]],
        "range_position": range_position(df),
        "trend_quality": trend_quality(df),
        "shallow_pullback_score": shallow_pullback_score(df),
    }
