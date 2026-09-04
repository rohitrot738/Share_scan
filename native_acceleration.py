from __future__ import annotations

import ctypes
import platform
from pathlib import Path

import numpy as np
import pandas as pd


_ROOT = Path(__file__).with_name("native")
_NAME = "libshare_scan_native.dylib" if platform.system().lower() == "darwin" else "libshare_scan_native.so"
_PATH = _ROOT / _NAME
_LIB = None


def _library():
    global _LIB
    if _LIB is False:
        return None
    if _LIB is None:
        if not _PATH.is_file():
            _LIB = False
            return None
        lib = ctypes.CDLL(str(_PATH))
        pointer = np.ctypeslib.ndpointer(dtype=np.float64, ndim=1, flags="C_CONTIGUOUS")
        lib.share_scan_supertrend.argtypes = [pointer, pointer, pointer, ctypes.c_int, ctypes.c_int, ctypes.c_double, pointer, pointer]
        lib.share_scan_supertrend.restype = ctypes.c_int
        lib.share_scan_rolling_mad.argtypes = [pointer, ctypes.c_int, ctypes.c_int, pointer]
        lib.share_scan_rolling_mad.restype = ctypes.c_int
        _LIB = lib
    return _LIB


def native_available() -> bool:
    return _library() is not None


def supertrend_native(df: pd.DataFrame, window: int, multiplier: float):
    lib = _library()
    if lib is None or df.empty:
        return None
    high = np.ascontiguousarray(df["high"].to_numpy(dtype=np.float64))
    low = np.ascontiguousarray(df["low"].to_numpy(dtype=np.float64))
    close = np.ascontiguousarray(df["close"].to_numpy(dtype=np.float64))
    trend = np.empty(len(df), dtype=np.float64)
    direction = np.empty(len(df), dtype=np.float64)
    status = lib.share_scan_supertrend(high, low, close, len(df), int(window), float(multiplier), trend, direction)
    if status != 0:
        return None
    return pd.Series(trend, index=df.index, dtype=float), pd.Series(direction, index=df.index, dtype=float)


def rolling_mad_native(values: pd.Series, window: int) -> pd.Series | None:
    lib = _library()
    if lib is None or values.empty or values.isna().any():
        return None
    source = np.ascontiguousarray(values.to_numpy(dtype=np.float64))
    output = np.empty(len(source), dtype=np.float64)
    status = lib.share_scan_rolling_mad(source, len(source), int(window), output)
    if status != 0:
        return None
    return pd.Series(output, index=values.index, dtype=float)
