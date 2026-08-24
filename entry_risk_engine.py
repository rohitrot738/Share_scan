import pandas as pd
import numpy as np


def trade_plan(df: pd.DataFrame, resistance: float, support: float, atr_value: float | None = None):
    last = float(df['close'].iloc[-1])
    if atr_value is None:
        tr = pd.concat([
            df['high']-df['low'],
            (df['high']-df['close'].shift(1)).abs(),
            (df['low']-df['close'].shift(1)).abs()
        ],axis=1).max(axis=1)
        atr_value = float(tr.rolling(14).mean().iloc[-1])
    atr_value = atr_value if atr_value and not np.isnan(atr_value) else max(last*0.01,0.01)

    early_entry = max(support + 0.30*(resistance-support), last-0.35*atr_value)
    confirmation_entry = resistance + 0.10*atr_value
    stop = min(support-0.15*atr_value, early_entry-0.80*atr_value)
    risk = max(early_entry-stop, 1e-9)
    t1 = early_entry + 1.5*risk
    t2 = early_entry + 2.5*risk
    t3 = early_entry + 4.0*risk

    return {
        'early_entry_zone': [round(early_entry-0.10*atr_value,2), round(early_entry+0.10*atr_value,2)],
        'confirmation_entry': round(confirmation_entry,2),
        'invalidation_stop': round(stop,2),
        'targets': [round(t1,2),round(t2,2),round(t3,2)],
        'risk_per_share': round(risk,2),
        'support': round(support,2),
        'resistance': round(resistance,2)
    }
