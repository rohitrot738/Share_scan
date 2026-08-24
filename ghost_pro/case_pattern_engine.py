"""Case-driven pattern features for Ghost Move Pro.

This module converts recurring behaviours discovered from labelled chart cases
into numerical features. It is deliberately generic: no stock name, absolute
price, or fixed rupee level is hard-coded.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict
import numpy as np
import pandas as pd


@dataclass
class CasePatternSnapshot:
    impulse_strength: float
    impulse_retention: float
    base_tightness: float
    volume_contraction: float
    resistance_test_quality: float
    rejection_decay: float
    pullback_depth_decay: float
    top_half_acceptance: float
    breakout_proximity: float
    breakout_confirmation: float
    exhaustion_penalty: float
    score: float
    state: str


def _clip01(x: float) -> float:
    return float(np.clip(float(x), 0.0, 1.0))


def _atr(df: pd.DataFrame, n: int = 14) -> pd.Series:
    pc = df['close'].shift(1)
    tr = pd.concat([
        df['high'] - df['low'],
        (df['high'] - pc).abs(),
        (df['low'] - pc).abs(),
    ], axis=1).max(axis=1)
    return tr.rolling(n).mean()


def _rolling_resistance_tests(d: pd.DataFrame, resistance: float, tolerance_pct: float = 0.35) -> float:
    if resistance <= 0 or len(d) == 0:
        return 0.0
    tol = resistance * tolerance_pct / 100.0
    tests = ((d['high'] >= resistance - tol) & (d['close'] <= resistance + tol)).sum()
    # More than five tests is not automatically better; cap useful persistence.
    return _clip01(tests / 5.0)


def _rejection_decay(d: pd.DataFrame, resistance: float) -> float:
    """1.0 means later rejections from resistance are materially smaller."""
    if len(d) < 6:
        return 0.5
    near = d[d['high'] >= resistance * 0.996].copy()
    if len(near) < 3:
        return 0.5
    rejection = (near['high'] - near['close']).clip(lower=0).values
    split = max(1, len(rejection)//2)
    early = float(np.mean(rejection[:split]))
    late = float(np.mean(rejection[split:])) if len(rejection[split:]) else early
    if early <= 1e-9:
        return 0.5
    return _clip01(1.0 - late / (early + 1e-9))


def _pullback_depth_decay(d: pd.DataFrame) -> float:
    """Measures whether successive local drawdowns are getting shallower."""
    if len(d) < 8:
        return 0.5
    c = d['close'].values
    half = len(c)//2
    def avg_down(arr):
        diff = np.diff(arr)
        downs = np.abs(diff[diff < 0])
        return float(np.mean(downs)) if len(downs) else 0.0
    a = avg_down(c[:half+1]); b = avg_down(c[half:])
    if a <= 1e-9:
        return 0.5
    return _clip01(1.0 - b/(a+1e-9))


def _exhaustion_penalty(d: pd.DataFrame, atr_now: float) -> float:
    """Penalty for late-stage spike/reject/lower-high behaviour."""
    if len(d) < 10 or atr_now <= 0 or np.isnan(atr_now):
        return 0.0
    x = d.tail(10).copy()
    ranges = (x['high'] - x['low']) / atr_now
    upper_wick = x['high'] - x[['open','close']].max(axis=1)
    body = (x['close'] - x['open']).abs().replace(0, np.nan)
    wick_ratio = (upper_wick / body).replace([np.inf,-np.inf], np.nan).fillna(0)
    spike_reject = ((ranges > 1.4) & (wick_ratio > 1.2)).mean()
    highs = x['high'].values
    lower_high_ratio = float((np.diff(highs) < 0).mean()) if len(highs) > 1 else 0.0
    return _clip01(0.55*spike_reject + 0.45*lower_high_ratio)


def analyse_case_pattern(df: pd.DataFrame, impulse_window: int = 20, base_window: int = 12) -> Dict[str, object]:
    required = {'open','high','low','close','volume'}
    if not required.issubset(df.columns):
        raise ValueError(f"required columns: {sorted(required)}")
    if len(df) < impulse_window + base_window + 20:
        raise ValueError('insufficient candles for case-pattern analysis')

    d = df.copy().reset_index(drop=True)
    d['atr'] = _atr(d, 14)
    atr_now = float(d['atr'].iloc[-1])
    base = d.tail(base_window)
    impulse = d.iloc[-(impulse_window+base_window):-base_window]

    impulse_low = float(impulse['low'].min())
    impulse_high = float(impulse['high'].max())
    impulse_move = max(impulse_high - impulse_low, 1e-9)
    impulse_pct = impulse_move / max(impulse_low, 1e-9)
    atr_ref = float(impulse['atr'].dropna().mean()) if impulse['atr'].notna().any() else impulse_move
    impulse_atr = impulse_move / max(atr_ref, 1e-9)
    impulse_strength = _clip01(0.55*(impulse_pct/0.06) + 0.45*(impulse_atr/6.0))

    base_low = float(base['low'].min()); base_high = float(base['high'].max())
    base_range = max(base_high-base_low, 1e-9)
    close_now = float(base['close'].iloc[-1])

    retrace = max(0.0, impulse_high - base_low)
    impulse_retention = _clip01(1.0 - retrace/impulse_move)

    base_pct = base_range/max(float(base['close'].mean()),1e-9)
    base_atr = base_range/max(atr_now,1e-9)
    base_tightness = _clip01(1.0 - 0.55*(base_pct/0.035) - 0.45*(base_atr/4.0))

    imp_vol = float(impulse['volume'].mean())
    base_vol = float(base['volume'].mean())
    vol_ratio = base_vol/max(imp_vol,1e-9)
    volume_contraction = _clip01((1.15-vol_ratio)/0.75)

    resistance = base_high
    resistance_test_quality = _rolling_resistance_tests(base, resistance)
    rejection_decay = _rejection_decay(base, resistance)
    pullback_depth_decay = _pullback_depth_decay(base)

    top_half = base_low + 0.5*base_range
    top_half_acceptance = float((base['close'] >= top_half).mean())

    distance = max(0.0, resistance-close_now)/max(close_now,1e-9)
    breakout_proximity = _clip01(1.0-distance/0.012)

    latest = d.iloc[-1]
    vol20 = float(d['volume'].tail(20).mean())
    rvol = float(latest['volume'])/max(vol20,1e-9)
    range_now = float(latest['high']-latest['low'])/max(atr_now,1e-9)
    bull_close = _clip01((float(latest['close'])-float(latest['low']))/max(float(latest['high']-latest['low']),1e-9))
    breakout_confirmation = _clip01(0.45*(rvol/2.0)+0.35*(range_now/1.6)+0.20*bull_close)

    exhaustion_penalty = _exhaustion_penalty(d, atr_now)

    score = 100.0 * (
        0.13*impulse_strength +
        0.14*impulse_retention +
        0.14*base_tightness +
        0.12*volume_contraction +
        0.09*resistance_test_quality +
        0.08*rejection_decay +
        0.08*pullback_depth_decay +
        0.08*top_half_acceptance +
        0.08*breakout_proximity +
        0.06*breakout_confirmation
    )
    score *= (1.0 - 0.35*exhaustion_penalty)
    score = float(np.clip(score,0,100))

    if score >= 88 and breakout_confirmation >= 0.62:
        state='CONFIRMED'
    elif score >= 78:
        state='READY'
    elif score >= 66:
        state='EARLY'
    elif score >= 55:
        state='WATCH'
    else:
        state='IGNORE'

    snap = CasePatternSnapshot(
        impulse_strength=round(100*impulse_strength,2),
        impulse_retention=round(100*impulse_retention,2),
        base_tightness=round(100*base_tightness,2),
        volume_contraction=round(100*volume_contraction,2),
        resistance_test_quality=round(100*resistance_test_quality,2),
        rejection_decay=round(100*rejection_decay,2),
        pullback_depth_decay=round(100*pullback_depth_decay,2),
        top_half_acceptance=round(100*top_half_acceptance,2),
        breakout_proximity=round(100*breakout_proximity,2),
        breakout_confirmation=round(100*breakout_confirmation,2),
        exhaustion_penalty=round(100*exhaustion_penalty,2),
        score=round(score,2),
        state=state,
    )
    return {
        'pattern_family':'PRE_BREAKOUT_COMPRESSION_AFTER_IMPULSE',
        'snapshot':asdict(snap),
        'levels':{'base_low':base_low,'resistance':resistance,'last_close':close_now},
    }
