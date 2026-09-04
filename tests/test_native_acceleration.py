import numpy as np
import pandas as pd

from indicators import cci, supertrend, supertrend_python
from native_acceleration import native_available


def sample(size=500):
    rng = np.random.default_rng(17)
    close = 100 + np.cumsum(rng.normal(0, 0.6, size))
    opened = close + rng.normal(0, 0.2, size)
    return pd.DataFrame({
        "open": opened,
        "high": np.maximum(opened, close) + rng.random(size),
        "low": np.minimum(opened, close) - rng.random(size),
        "close": close,
        "volume": rng.integers(10_000, 1_000_000, size),
    })


def test_native_library_is_built_in_ci():
    assert native_available()


def test_native_supertrend_matches_python_fallback():
    frame = sample()
    native_trend, native_direction = supertrend(frame)
    python_trend, python_direction = supertrend_python(frame)
    np.testing.assert_allclose(native_trend, python_trend, equal_nan=True, rtol=1e-12, atol=1e-12)
    np.testing.assert_allclose(native_direction, python_direction, equal_nan=True, rtol=0, atol=0)


def test_native_cci_matches_reference_formula():
    frame = sample()
    actual = cci(frame, 20)
    tp = (frame["high"] + frame["low"] + frame["close"]) / 3
    md = tp.rolling(20).apply(lambda x: np.mean(np.abs(x - np.mean(x))), raw=True)
    expected = (tp - tp.rolling(20).mean()) / (0.015 * md.replace(0, np.nan))
    np.testing.assert_allclose(actual, expected, equal_nan=True, rtol=1e-12, atol=1e-12)
