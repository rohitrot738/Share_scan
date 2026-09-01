from __future__ import annotations

import unittest
from unittest.mock import patch

import pandas as pd

import live_chart_server as chart


class LiveChartTests(unittest.TestCase):
    def test_all_requested_timeframes_are_supported(self):
        self.assertEqual(
            chart.SUPPORTED,
            ("1m", "2m", "3m", "5m", "10m", "15m", "30m", "1h", "4h", "1d", "1w"),
        )
        self.assertEqual(set(chart.SUPPORTED), set(chart._HISTORY))

    def test_symbol_normalization(self):
        self.assertEqual(chart._symbol("NSE:RELIANCE.NS"), "RELIANCE")
        self.assertEqual(chart._symbol("  m&m.ns  "), "M&M")
        self.assertEqual(chart._symbol("../../etc/passwd"), "ETCPASSWD")

    def test_native_history(self):
        index = pd.date_range("2026-09-01 09:15", periods=2, freq="5min", tz="Asia/Kolkata")
        frame = pd.DataFrame(
            {"open": [100, 101], "high": [102, 103], "low": [99, 100], "close": [101, 102], "volume": [10, 20]},
            index=index,
        )
        with patch.object(chart, "fetch_history", return_value=frame) as fetch:
            payload = chart.history("RELIANCE", "5m")
        fetch.assert_called_once_with("RELIANCE.NS", "30d", "5m", retries=1)
        self.assertEqual(payload["timeframe"], "5m")
        self.assertEqual(len(payload["candles"]), 2)

    def test_synthetic_timeframe_is_resampled(self):
        index = pd.date_range("2026-09-01 09:15", periods=4, freq="1min", tz="Asia/Kolkata")
        frame = pd.DataFrame(
            {"open": [100, 101, 102, 103], "high": [102, 103, 104, 105], "low": [99, 100, 101, 102], "close": [101, 102, 103, 104], "volume": [10, 20, 30, 40]},
            index=index,
        )
        with patch.object(chart, "fetch_history", return_value=frame):
            payload = chart.history("INFY", "2m")
        self.assertEqual(len(payload["candles"]), 2)
        self.assertEqual(payload["candles"][0]["volume"], 30.0)

    def test_empty_history_is_an_error(self):
        with patch.object(chart, "fetch_history", return_value=pd.DataFrame()):
            with self.assertRaises(LookupError):
                chart.history("BAD", "1m")

    def test_unknown_timeframe_is_rejected(self):
        with self.assertRaises(ValueError):
            chart.history("RELIANCE", "6h")


if __name__ == "__main__":
    unittest.main()
