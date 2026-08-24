"""Expanded timeframe matrix for Share_scan.

Direct provider intervals are used where supported. Synthetic intervals such as
3m, 10m and 4h are resampled from a finer supported feed so the scoring engine
can evaluate the same OHLCV logic consistently across nine horizons.
"""
from __future__ import annotations

from typing import Dict, Tuple
import pandas as pd

# target_tf -> (provider period, provider interval, optional pandas resample rule)
TIMEFRAME_SPECS: Dict[str, Tuple[str, str, str | None]] = {
    "1m":  ("7d",  "1m",  None),
    "3m":  ("7d",  "1m",  "3min"),
    "5m":  ("30d", "5m",  None),
    "10m": ("30d", "5m",  "10min"),
    "15m": ("60d", "15m", None),
    "30m": ("60d", "30m", None),
    "1h":  ("1y",  "60m", None),
    "4h":  ("1y",  "60m", "4h"),
    "1d":  ("5y",  "1d",  None),
}

# More weight on 5m-1h for timing, with higher-timeframe trend confirmation.
TIMEFRAME_WEIGHTS = {
    "1m": 0.05,
    "3m": 0.07,
    "5m": 0.12,
    "10m": 0.10,
    "15m": 0.16,
    "30m": 0.12,
    "1h": 0.16,
    "4h": 0.12,
    "1d": 0.10,
}


def _timestamp_col(df: pd.DataFrame) -> str | None:
    for c in ("timestamp", "datetime", "date"):
        if c in df.columns:
            return c
    return None


def resample_ohlcv(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    """Resample OHLCV without fabricating price or volume observations."""
    if df is None or df.empty:
        return df
    tcol = _timestamp_col(df)
    if tcol is None:
        raise ValueError("timestamp column missing for resample")
    d = df.copy()
    d[tcol] = pd.to_datetime(d[tcol], errors="coerce")
    d = d.dropna(subset=[tcol]).sort_values(tcol).set_index(tcol)
    agg = {
        "open": "first",
        "high": "max",
        "low": "min",
        "close": "last",
        "volume": "sum",
    }
    extra = {c: "last" for c in d.columns if c not in agg}
    out = d.resample(rule).agg({**agg, **extra})
    out = out.dropna(subset=["open", "high", "low", "close"])
    out = out[out["volume"].fillna(0) >= 0]
    return out.reset_index().rename(columns={tcol: "timestamp"})


def fetch_all_timeframes(collector, symbol: str, exchange: str, min_candles: int = 60):
    frames = {}
    warnings = []
    for tf, (period, interval, rule) in TIMEFRAME_SPECS.items():
        try:
            raw = collector.collect_price_history(symbol, exchange, period=period, interval=interval)
            data = resample_ohlcv(raw, rule) if rule else raw
            if data is not None and len(data) >= min_candles:
                frames[tf] = data.tail(2000).reset_index(drop=True)
            else:
                warnings.append(f"{tf}: fewer than {min_candles} candles")
        except Exception as exc:
            warnings.append(f"{tf}: {type(exc).__name__}: {exc}")
    return frames, warnings
