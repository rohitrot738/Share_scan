import numpy as np
import pandas as pd


def true_range(df: pd.DataFrame) -> pd.Series:
    prev_close = df['close'].shift(1)
    a = df['high'] - df['low']
    b = (df['high'] - prev_close).abs()
    c = (df['low'] - prev_close).abs()
    return pd.concat([a, b, c], axis=1).max(axis=1)


def atr(df: pd.DataFrame, window: int = 14) -> pd.Series:
    return true_range(df).rolling(window).mean()


def rvol(df: pd.DataFrame, window: int = 20) -> pd.Series:
    base = df['volume'].rolling(window).mean().replace(0, np.nan)
    return df['volume'] / base


def ema(series: pd.Series, span: int) -> pd.Series:
    return series.ewm(span=span, adjust=False).mean()


def add_basic_indicators(df: pd.DataFrame, atr_window: int = 14) -> pd.DataFrame:
    out = df.copy()
    out['atr'] = atr(out, atr_window)
    out['rvol20'] = rvol(out, 20)
    out['ema9'] = ema(out['close'], 9)
    out['ema20'] = ema(out['close'], 20)
    out['ema50'] = ema(out['close'], 50)
    out['body'] = (out['close'] - out['open']).abs()
    out['range'] = (out['high'] - out['low']).replace(0, np.nan)
    out['upper_wick'] = out['high'] - out[['open', 'close']].max(axis=1)
    out['lower_wick'] = out[['open', 'close']].min(axis=1) - out['low']
    return out
