from dataclasses import dataclass
import numpy as np
import pandas as pd
from indicators import add_basic_indicators
from config import ScannerConfig

@dataclass
class PatternFeatures:
    impulse_pct: float
    consolidation_range_pct: float
    volume_dryup_ratio: float
    breakout_distance_pct: float
    higher_low_strength: float
    trend_alignment: float
    rvol_now: float
    support_hold_strength: float
    supply_exhaustion: float
    base_quality: float
    resistance: float
    support: float


def _pct(a, b):
    return 0.0 if b == 0 or np.isnan(b) else (a / b) * 100.0


def extract_features(df: pd.DataFrame, cfg: ScannerConfig) -> PatternFeatures:
    if len(df) < 60:
        raise ValueError('Need at least 60 candles.')

    d = add_basic_indicators(df, cfg.atr_window).dropna().copy()
    recent = d.iloc[-cfg.consolidation_lookback:]
    pre = d.iloc[-(cfg.impulse_lookback + cfg.consolidation_lookback):-cfg.consolidation_lookback]

    impulse_low = float(pre['low'].min())
    impulse_high = float(pre['high'].max())
    impulse_pct = _pct(impulse_high - impulse_low, impulse_low)

    base_high = float(recent['high'].max())
    base_low = float(recent['low'].min())
    base_mid = (base_high + base_low) / 2
    consolidation_range_pct = _pct(base_high - base_low, base_mid)

    short_vol = float(recent['volume'].tail(cfg.volume_short_window).mean())
    long_vol = float(d['volume'].tail(cfg.volume_long_window).mean())
    volume_dryup_ratio = 1.0 if long_vol == 0 else short_vol / long_vol

    close_now = float(d['close'].iloc[-1])
    breakout_distance_pct = max(0.0, _pct(base_high - close_now, close_now))

    lows = recent['low'].tail(5).values
    higher_low_strength = float((np.diff(lows) >= 0).mean()) if len(lows) >= 4 else 0.0

    ema9, ema20, ema50 = map(float, [d['ema9'].iloc[-1], d['ema20'].iloc[-1], d['ema50'].iloc[-1]])
    trend_alignment = sum([close_now > ema9, ema9 > ema20, ema20 > ema50]) / 3.0
    rvol_now = float(d['rvol20'].iloc[-1]) if not np.isnan(d['rvol20'].iloc[-1]) else 0.0

    support_hold_strength = float((recent['close'] >= base_low + 0.5 * (base_high - base_low)).mean())

    reds = recent[recent['close'] < recent['open']]
    if len(reds) >= 2:
        vol_fall = float(reds['volume'].iloc[-1] <= reds['volume'].iloc[0])
        body_fall = float(reds['body'].iloc[-1] <= reds['body'].iloc[0])
        supply_exhaustion = (vol_fall + body_fall) / 2
    else:
        supply_exhaustion = 0.5

    tightness = max(0.0, 1.0 - consolidation_range_pct / max(cfg.consolidation_max_range_pct, 0.1))
    shallow = max(0.0, min(1.0, (close_now - base_low) / max(base_high - base_low, 1e-9)))
    base_quality = 0.6 * tightness + 0.4 * shallow

    return PatternFeatures(
        impulse_pct, consolidation_range_pct, volume_dryup_ratio,
        breakout_distance_pct, higher_low_strength, trend_alignment,
        rvol_now, support_hold_strength, supply_exhaustion, base_quality,
        base_high, base_low
    )
