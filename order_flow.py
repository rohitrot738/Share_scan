import pandas as pd
import numpy as np


def order_flow_proxy(df: pd.DataFrame, window: int = 20):
    d = df.tail(window).copy()
    rng = (d['high']-d['low']).replace(0, np.nan)
    close_loc = ((d['close']-d['low'])/rng).clip(0,1)
    signed = (close_loc-0.5)*2
    pressure = (signed*d['volume']).sum()
    total = d['volume'].sum()
    imbalance = float(pressure/total) if total else 0.0
    buy_vol = float(((signed.clip(lower=0))*d['volume']).sum())
    sell_vol = float(((-signed.clip(upper=0))*d['volume']).sum())
    return {
        'imbalance': imbalance,
        'buy_pressure': buy_vol,
        'sell_pressure': sell_vol,
        'dominance': 'BUY' if imbalance > 0.15 else 'SELL' if imbalance < -0.15 else 'NEUTRAL'
    }


def absorption_score(df: pd.DataFrame, window: int = 12):
    d = df.tail(window).copy()
    down = d[d['close'] < d['open']]
    if len(down) < 2:
        return 50.0
    price_damage = abs(float(down['close'].iloc[-1]-down['close'].iloc[0]))
    vol = float(down['volume'].sum())
    avg_range = float((d['high']-d['low']).mean()) or 1e-9
    score = 100.0 * (1.0 - min(1.0, price_damage/(avg_range*2.5)))
    if vol > d['volume'].mean()*len(down):
        score = min(100.0, score+10)
    return round(score,2)
