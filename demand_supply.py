import pandas as pd
import numpy as np


def detect_zones(df: pd.DataFrame, lookback: int = 80, pivot: int = 3, max_zones: int = 8):
    d = df.tail(lookback).copy().reset_index(drop=True)
    zones = []
    for i in range(pivot, len(d)-pivot):
        lo = d['low'].iloc[i]
        hi = d['high'].iloc[i]
        vol = d['volume'].iloc[i]
        avg_vol = d['volume'].iloc[max(0,i-20):i+1].mean()
        is_pivot_low = lo <= d['low'].iloc[i-pivot:i+pivot+1].min()
        is_pivot_high = hi >= d['high'].iloc[i-pivot:i+pivot+1].max()
        strength = float(vol / avg_vol) if avg_vol and not np.isnan(avg_vol) else 1.0
        if is_pivot_low:
            zones.append({'type':'demand','price':float(lo),'strength':strength,'index':i})
        if is_pivot_high:
            zones.append({'type':'supply','price':float(hi),'strength':strength,'index':i})
    zones = sorted(zones, key=lambda x:(x['strength'], x['index']), reverse=True)
    return zones[:max_zones]


def nearest_zone(df: pd.DataFrame, zone_type: str):
    price = float(df['close'].iloc[-1])
    zones = [z for z in detect_zones(df) if z['type'] == zone_type]
    if not zones:
        return None
    return min(zones, key=lambda z: abs(z['price']-price))
