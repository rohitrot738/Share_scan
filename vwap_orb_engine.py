import pandas as pd
import numpy as np


def session_vwap(df: pd.DataFrame):
    typical = (df['high']+df['low']+df['close'])/3.0
    cum_vol = df['volume'].cumsum().replace(0,np.nan)
    return (typical*df['volume']).cumsum()/cum_vol


def opening_range(df: pd.DataFrame, bars: int = 3):
    d = df.head(bars)
    return float(d['high'].max()), float(d['low'].min())


def analyse_vwap_orb(df: pd.DataFrame, opening_bars: int = 3):
    d = df.copy()
    d['vwap'] = session_vwap(d)
    or_high, or_low = opening_range(d, opening_bars)
    last = d.iloc[-1]
    above_vwap = bool(last['close'] > last['vwap'])
    above_or = bool(last['close'] > or_high)
    recent = d.tail(5)
    pullback_low_vol = bool(recent['volume'].iloc[-2] < d['volume'].tail(20).mean()) if len(recent)>=2 else False
    green_confirm = bool(last['close'] > last['open'] and last['close'] > recent['high'].iloc[-2]) if len(recent)>=2 else False
    return {
        'vwap': round(float(last['vwap']),2),
        'opening_range_high': round(or_high,2),
        'opening_range_low': round(or_low,2),
        'above_vwap': above_vwap,
        'orb_breakout': above_or,
        'low_volume_pullback': pullback_low_vol,
        'green_confirmation': green_confirm,
    }
