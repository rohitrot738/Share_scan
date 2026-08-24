import pandas as pd
import numpy as np


def false_breakout_risk(df: pd.DataFrame, resistance: float | None = None, support: float | None = None, window: int = 20):
    d = df.tail(window).copy()
    last = d.iloc[-1]
    if resistance is None:
        resistance = float(d['high'].iloc[:-1].max())
    if support is None:
        support = float(d['low'].iloc[:-1].min())

    rng = max(float(last['high']-last['low']), 1e-9)
    upper_wick = float(last['high'] - max(last['open'], last['close'])) / rng
    lower_wick = float(min(last['open'], last['close']) - last['low']) / rng
    avg_vol = float(d['volume'].iloc[:-1].mean()) or 1e-9
    rvol = float(last['volume']/avg_vol)

    failed_up = last['high'] > resistance and last['close'] < resistance
    failed_down = last['low'] < support and last['close'] > support

    risk = 25.0
    if failed_up:
        risk += 35
    if upper_wick > 0.45:
        risk += 20
    if rvol < 1.15 and last['close'] > resistance:
        risk += 15
    if last['close'] < last['open']:
        risk += 10
    if failed_down or lower_wick > 0.45:
        risk -= 10

    return {
        'risk': round(max(0.0, min(100.0, risk)), 2),
        'failed_up_breakout': bool(failed_up),
        'failed_down_breakout': bool(failed_down),
        'upper_wick_ratio': round(upper_wick,3),
        'rvol': round(rvol,2),
    }
