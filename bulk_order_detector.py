import pandas as pd
import numpy as np


def detect_abnormal_activity(df: pd.DataFrame, volume_window: int = 20, range_window: int = 14):
    d = df.copy()
    avg_vol = d['volume'].rolling(volume_window).mean()
    avg_rng = (d['high']-d['low']).rolling(range_window).mean()
    last = d.iloc[-1]
    vol_ratio = float(last['volume']/avg_vol.iloc[-1]) if avg_vol.iloc[-1] else 0.0
    range_ratio = float((last['high']-last['low'])/avg_rng.iloc[-1]) if avg_rng.iloc[-1] else 0.0
    body = abs(float(last['close']-last['open']))
    rng = max(float(last['high']-last['low']), 1e-9)
    body_efficiency = body/rng
    direction = 'BUY' if last['close'] > last['open'] else 'SELL' if last['close'] < last['open'] else 'NEUTRAL'
    score = min(100.0, 35*min(vol_ratio/2.5,1)+25*min(range_ratio/2.0,1)+40*body_efficiency)
    return {
        'abnormal_volume_ratio': round(vol_ratio,2),
        'range_expansion_ratio': round(range_ratio,2),
        'direction': direction,
        'activity_score': round(score,2),
        'possible_large_participation': bool(vol_ratio >= 1.8 and score >= 65)
    }
