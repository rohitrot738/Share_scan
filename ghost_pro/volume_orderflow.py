"""Volume and order-flow proxy analysis for Ghost Trade Pro.

True bid/ask queue, iceberg and hidden orders require market-depth/trade feed.
This module derives conservative proxies from OHLCV and optional aggressor data.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict, Optional
import numpy as np
import pandas as pd


@dataclass
class FlowSnapshot:
    rvol: float
    buy_pressure: float
    sell_pressure: float
    delta_proxy: float
    absorption_buy: float
    absorption_sell: float
    dryup: float
    climax: float
    effort_result: float
    accumulation: float
    distribution: float
    score: float


def safe_div(a, b, default=0.0):
    if b is None or b == 0 or not np.isfinite(b):
        return default
    return float(a / b)


def typical_price(df: pd.DataFrame) -> pd.Series:
    return (df["high"] + df["low"] + df["close"]) / 3.0


def vwap(df: pd.DataFrame) -> pd.Series:
    tp = typical_price(df)
    vol = df["volume"].fillna(0).clip(lower=0)
    den = vol.cumsum().replace(0, np.nan)
    return (tp * vol).cumsum() / den


def anchored_vwap(df: pd.DataFrame, anchor_index: int) -> pd.Series:
    out = pd.Series(np.nan, index=df.index, dtype=float)
    if anchor_index < 0:
        anchor_index = max(0, len(df) + anchor_index)
    part = df.iloc[anchor_index:]
    if part.empty:
        return out
    tp = typical_price(part)
    vol = part["volume"].fillna(0).clip(lower=0)
    den = vol.cumsum().replace(0, np.nan)
    out.iloc[anchor_index:] = ((tp * vol).cumsum() / den).to_numpy()
    return out


def relative_volume(df: pd.DataFrame, window: int = 20) -> pd.Series:
    avg = df["volume"].rolling(window, min_periods=max(3, window // 4)).mean()
    return df["volume"] / avg.replace(0, np.nan)


def volume_zscore(df: pd.DataFrame, window: int = 30) -> pd.Series:
    v = df["volume"].astype(float)
    mean = v.rolling(window, min_periods=max(5, window // 3)).mean()
    std = v.rolling(window, min_periods=max(5, window // 3)).std(ddof=0).replace(0, np.nan)
    return (v - mean) / std


def close_location_value(df: pd.DataFrame) -> pd.Series:
    rng = (df["high"] - df["low"]).replace(0, np.nan)
    return ((df["close"] - df["low"]) - (df["high"] - df["close"])) / rng


def money_flow_volume(df: pd.DataFrame) -> pd.Series:
    return close_location_value(df).fillna(0) * df["volume"].fillna(0)


def accumulation_distribution(df: pd.DataFrame) -> pd.Series:
    return money_flow_volume(df).cumsum()


def obv(df: pd.DataFrame) -> pd.Series:
    direction = np.sign(df["close"].diff()).fillna(0)
    return (direction * df["volume"].fillna(0)).cumsum()


def chaikin_money_flow(df: pd.DataFrame, length: int = 20) -> pd.Series:
    mfv = money_flow_volume(df)
    vol = df["volume"].rolling(length, min_periods=max(3, length // 4)).sum().replace(0, np.nan)
    return mfv.rolling(length, min_periods=max(3, length // 4)).sum() / vol


def signed_volume_proxy(df: pd.DataFrame) -> pd.Series:
    rng = (df["high"] - df["low"]).replace(0, np.nan)
    body_bias = (df["close"] - df["open"]) / rng
    location = (df["close"] - df["low"]) / rng - 0.5
    score = (0.65 * body_bias + 0.35 * location * 2).clip(-1, 1).fillna(0)
    return score * df["volume"].fillna(0)


def delta_proxy_series(df: pd.DataFrame) -> pd.Series:
    if "buy_volume" in df.columns and "sell_volume" in df.columns:
        return df["buy_volume"].fillna(0) - df["sell_volume"].fillna(0)
    return signed_volume_proxy(df)


def cumulative_delta_proxy(df: pd.DataFrame) -> pd.Series:
    return delta_proxy_series(df).cumsum()


def pressure_components(df: pd.DataFrame, length: int = 10):
    d = delta_proxy_series(df).tail(length)
    pos = float(d[d > 0].sum())
    neg = abs(float(d[d < 0].sum()))
    total = pos + neg
    if total <= 0:
        return 0.5, 0.5
    return pos / total, neg / total


def wick_absorption(df: pd.DataFrame, length: int = 12):
    r = df.tail(length).copy()
    rng = (r["high"] - r["low"]).replace(0, np.nan)
    upper = r["high"] - r[["open", "close"]].max(axis=1)
    lower = r[["open", "close"]].min(axis=1) - r["low"]
    vr = relative_volume(df, 20).tail(length).fillna(1.0)
    lower_signal = ((lower / rng).fillna(0) * vr).clip(0, 3)
    upper_signal = ((upper / rng).fillna(0) * vr).clip(0, 3)
    buy_abs = float(np.clip(lower_signal.mean() / 1.2, 0, 1))
    sell_abs = float(np.clip(upper_signal.mean() / 1.2, 0, 1))
    return buy_abs, sell_abs


def volume_dryup_score(df: pd.DataFrame, short: int = 5, long: int = 25) -> float:
    sv = float(df["volume"].tail(short).mean())
    lv = float(df["volume"].tail(long).mean())
    if lv <= 0:
        return 0.0
    ratio = sv / lv
    return float(np.clip((1.10 - ratio) / 0.70, 0, 1))


def red_volume_dryup(df: pd.DataFrame, length: int = 15) -> float:
    r = df.tail(length)
    red = r[r["close"] < r["open"]]
    if len(red) < 3:
        return 0.5
    first = float(red["volume"].head(max(1, len(red)//2)).mean())
    last = float(red["volume"].tail(max(1, len(red)//2)).mean())
    if first <= 0:
        return 0.0
    return float(np.clip(1.0 - last / first, 0, 1))


def green_volume_expansion(df: pd.DataFrame, length: int = 12) -> float:
    r = df.tail(length)
    green = r[r["close"] > r["open"]]
    if len(green) < 2:
        return 0.0
    recent = float(green["volume"].tail(2).mean())
    base = float(df["volume"].tail(30).mean())
    if base <= 0:
        return 0.0
    return float(np.clip((recent / base - 0.8) / 1.5, 0, 1))


def volume_climax_score(df: pd.DataFrame) -> float:
    z = volume_zscore(df, 30).iloc[-1]
    if not np.isfinite(z):
        return 0.0
    return float(np.clip((z - 1.5) / 2.5, 0, 1))


def effort_result_score(df: pd.DataFrame, length: int = 10) -> float:
    r = df.tail(length).copy()
    if len(r) < 3:
        return 0.0
    avg_vol = float(df["volume"].tail(30).mean())
    avg_rng = float((df["high"] - df["low"]).tail(30).mean())
    if avg_vol <= 0 or avg_rng <= 0:
        return 0.0
    vr = r["volume"] / avg_vol
    rr = (r["high"] - r["low"]) / avg_rng
    # High effort with narrow result can imply absorption.
    ineff = (vr / rr.replace(0, np.nan)).replace([np.inf, -np.inf], np.nan).fillna(0)
    return float(np.clip((ineff.mean() - 0.8) / 1.8, 0, 1))


def accumulation_score(df: pd.DataFrame, length: int = 30) -> float:
    r = df.tail(length)
    if len(r) < 10:
        return 0.0
    cmf = chaikin_money_flow(df, 20).iloc[-1]
    cmf = 0.0 if not np.isfinite(cmf) else float(cmf)
    ob = obv(df)
    ad = accumulation_distribution(df)
    px_ret = float(r["close"].iloc[-1] / r["close"].iloc[0] - 1)
    ob_ret = safe_div(float(ob.iloc[-1] - ob.iloc[-length]), float(r["volume"].sum()), 0)
    ad_ret = safe_div(float(ad.iloc[-1] - ad.iloc[-length]), float(r["volume"].sum()), 0)
    hidden = max(0.0, ob_ret + ad_ret - max(px_ret, 0.0))
    score = 0.35 * np.clip((cmf + 0.1) / 0.4, 0, 1)
    score += 0.30 * np.clip((ob_ret + 0.1) / 0.4, 0, 1)
    score += 0.20 * np.clip((ad_ret + 0.1) / 0.4, 0, 1)
    score += 0.15 * np.clip(hidden / 0.2, 0, 1)
    return float(np.clip(score, 0, 1))


def distribution_score(df: pd.DataFrame, length: int = 30) -> float:
    r = df.tail(length)
    if len(r) < 10:
        return 0.0
    cmf = chaikin_money_flow(df, 20).iloc[-1]
    cmf = 0.0 if not np.isfinite(cmf) else float(cmf)
    delta = delta_proxy_series(df).tail(length)
    neg = abs(float(delta[delta < 0].sum()))
    pos = float(delta[delta > 0].sum())
    pressure = safe_div(neg, pos + neg, 0.5)
    upper = r["high"] - r[["open", "close"]].max(axis=1)
    rng = (r["high"] - r["low"]).replace(0, np.nan)
    wick = float((upper / rng).fillna(0).mean())
    score = 0.45 * np.clip((-cmf + 0.1) / 0.4, 0, 1)
    score += 0.35 * pressure
    score += 0.20 * np.clip(wick * 2, 0, 1)
    return float(np.clip(score, 0, 1))


def abnormal_participation(df: pd.DataFrame) -> Dict[str, float]:
    rv = relative_volume(df, 20).iloc[-1]
    vz = volume_zscore(df, 30).iloc[-1]
    body = abs(float(df["close"].iloc[-1] - df["open"].iloc[-1]))
    rng = max(float(df["high"].iloc[-1] - df["low"].iloc[-1]), 1e-9)
    directional = body / rng
    rv = 0.0 if not np.isfinite(rv) else float(rv)
    vz = 0.0 if not np.isfinite(vz) else float(vz)
    large_proxy = float(np.clip(0.55 * max(0, rv - 1) / 2 + 0.30 * max(0, vz) / 4 + 0.15 * directional, 0, 1))
    return {"rvol": rv, "volume_z": vz, "large_participation_proxy": large_proxy}


def divergence_score(price: pd.Series, flow: pd.Series, length: int = 20) -> float:
    if len(price) < length or len(flow) < length:
        return 0.0
    p = price.tail(length).to_numpy(float)
    f = flow.tail(length).to_numpy(float)
    x = np.arange(length, dtype=float)
    ps = np.polyfit(x, p, 1)[0] / max(np.mean(abs(p)), 1e-9)
    fs = np.polyfit(x, f, 1)[0] / max(np.mean(abs(f)), 1e-9)
    # Positive flow while price is flat/down = bullish hidden demand.
    if ps <= 0 and fs > 0:
        return float(np.clip((fs - ps) * 200, 0, 1))
    return 0.0


def flow_snapshot(df: pd.DataFrame) -> FlowSnapshot:
    rv = relative_volume(df, 20).iloc[-1]
    rv = 0.0 if not np.isfinite(rv) else float(rv)
    buy, sell = pressure_components(df, 12)
    delta = delta_proxy_series(df).tail(12)
    delta_norm = safe_div(float(delta.sum()), float(df["volume"].tail(12).sum()), 0.0)
    ba, sa = wick_absorption(df, 12)
    dry = volume_dryup_score(df)
    climax = volume_climax_score(df)
    effort = effort_result_score(df)
    accum = accumulation_score(df)
    dist = distribution_score(df)
    bull = 0.22 * buy + 0.16 * max(delta_norm, 0) + 0.16 * ba + 0.14 * dry + 0.12 * accum
    bull += 0.10 * green_volume_expansion(df) + 0.10 * effort
    bull -= 0.15 * dist + 0.08 * sa
    score = float(np.clip(bull * 100, 0, 100))
    return FlowSnapshot(
        rvol=rv,
        buy_pressure=buy,
        sell_pressure=sell,
        delta_proxy=delta_norm,
        absorption_buy=ba,
        absorption_sell=sa,
        dryup=dry,
        climax=climax,
        effort_result=effort,
        accumulation=accum,
        distribution=dist,
        score=score,
    )


def volume_orderflow_report(df: pd.DataFrame) -> Dict[str, object]:
    snap = flow_snapshot(df)
    av = abnormal_participation(df)
    cvd = cumulative_delta_proxy(df)
    div = divergence_score(df["close"], cvd, 20)
    vw = vwap(df)
    close = float(df["close"].iloc[-1])
    vw_now = float(vw.iloc[-1]) if np.isfinite(vw.iloc[-1]) else close
    return {
        "snapshot": asdict(snap),
        "abnormal_participation": av,
        "cvd_bullish_divergence": div,
        "vwap": vw_now,
        "above_vwap": close > vw_now,
        "red_volume_dryup": red_volume_dryup(df),
        "green_volume_expansion": green_volume_expansion(df),
    }
