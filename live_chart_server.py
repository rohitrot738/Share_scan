from __future__ import annotations

import json
import os
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pandas as pd

from market_data import fetch_history, resample_ohlcv

HOST = os.getenv("LIVE_CHART_HOST", "0.0.0.0")
PORT = int(os.getenv("LIVE_CHART_PORT", "8787"))
ROOT = Path(__file__).resolve().parent
PUBLIC = ROOT / "live_chart"
SUPPORTED = ("1m", "2m", "3m", "5m", "10m", "15m", "30m", "1h", "4h", "1d", "1w")

_HISTORY = {
    "1m": ("7d", "1m", None),
    "2m": ("7d", "1m", "2min"),
    "3m": ("7d", "1m", "3min"),
    "5m": ("30d", "5m", None),
    "10m": ("30d", "5m", "10min"),
    "15m": ("60d", "15m", None),
    "30m": ("60d", "30m", None),
    "1h": ("730d", "60m", None),
    "4h": ("730d", "60m", "4h"),
    "1d": ("5y", "1d", None),
    "1w": ("10y", "1wk", None),
}


def _symbol(raw: str) -> str:
    s = str(raw or "").upper().strip().replace("NSE:", "")
    if s.endswith(".NS"):
        s = s[:-3]
    return "".join(ch for ch in s if ch.isalnum() or ch in "-&")[:32]


def _jsonable_frame(df: pd.DataFrame, limit: int = 1200) -> list[dict]:
    if df is None or df.empty:
        return []
    x = df.tail(limit).copy()
    rows = []
    for idx, r in x.iterrows():
        try:
            ts = int(pd.Timestamp(idx).timestamp())
            rows.append({
                "time": ts,
                "open": float(r["open"]),
                "high": float(r["high"]),
                "low": float(r["low"]),
                "close": float(r["close"]),
                "volume": float(r.get("volume", 0) or 0),
            })
        except Exception:
            continue
    return rows


def history(symbol: str, timeframe: str) -> dict:
    if timeframe not in _HISTORY:
        raise ValueError(f"Unsupported timeframe: {timeframe}")
    period, source_interval, rule = _HISTORY[timeframe]
    yahoo_symbol = f"{symbol}.NS"
    df = fetch_history(yahoo_symbol, period, source_interval, retries=1)
    if rule:
        df = resample_ohlcv(df, rule)
    return {"symbol": symbol, "timeframe": timeframe, "source": "Yahoo OHLC + Groww live LTP when token is configured", "candles": _jsonable_frame(df)}


class GrowwLiveFeed:
    """Small live-LTP bridge. Token stays server-side; browser never receives credentials."""

    def __init__(self):
        self.token = os.getenv("GROWW_ACCESS_TOKEN", "").strip()
        self._lock = threading.Lock()
        self._feed = None
        self._api = None
        self._subscribed: set[str] = set()

    @property
    def enabled(self) -> bool:
        return bool(self.token)

    def _ensure(self):
        if self._feed is not None:
            return
        from growwapi import GrowwAPI, GrowwFeed
        self._api = GrowwAPI(self.token)
        try:
            self._feed = GrowwFeed(self._api)
        except Exception:
            self._feed = GrowwFeed(self.token)

    def ltp(self, symbol: str) -> dict:
        if not self.enabled:
            return {"live": False, "reason": "GROWW_ACCESS_TOKEN missing"}
        with self._lock:
            try:
                self._ensure()
                if symbol not in self._subscribed:
                    self._feed.subscribe_live_data(self._api.SEGMENT_CASH, symbol)
                    self._subscribed.add(symbol)
                    time.sleep(0.15)
                value = self._feed.get_stocks_ltp(symbol, timeout=2)
                if isinstance(value, dict):
                    for key in ("ltp", "last_price", "lastPrice", "price"):
                        if key in value:
                            value = value[key]
                            break
                return {"live": True, "symbol": symbol, "ltp": float(value), "time": int(time.time()), "provider": "Groww"}
            except Exception as exc:
                return {"live": False, "reason": str(exc)[:180]}


LIVE = GrowwLiveFeed()


def scanner_symbols() -> list[str]:
    paths = [ROOT / "scan_output" / "top100_by_volume.csv", ROOT / ".scan_cache" / "nse_stage1.csv"]
    for path in paths:
        try:
            if path.exists():
                df = pd.read_csv(path)
                if "symbol" in df.columns:
                    return [_symbol(x) for x in df["symbol"].dropna().astype(str).head(100).tolist() if _symbol(x)]
        except Exception:
            pass
    return ["RELIANCE", "HDFCBANK", "ICICIBANK", "SBIN", "INFY", "TCS"]


class Handler(SimpleHTTPRequestHandler):
    def translate_path(self, path):
        rel = urlparse(path).path.lstrip("/") or "index.html"
        return str((PUBLIC / rel).resolve())

    def _send_json(self, payload, status=200):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        parsed = urlparse(self.path)
        q = parse_qs(parsed.query)
        try:
            if parsed.path == "/api/timeframes":
                return self._send_json({"timeframes": SUPPORTED})
            if parsed.path == "/api/symbols":
                return self._send_json({"symbols": scanner_symbols()})
            if parsed.path == "/api/history":
                symbol = _symbol(q.get("symbol", [""])[0])
                timeframe = q.get("tf", ["5m"])[0]
                if not symbol:
                    return self._send_json({"error": "symbol required"}, 400)
                return self._send_json(history(symbol, timeframe))
            if parsed.path == "/api/ltp":
                symbol = _symbol(q.get("symbol", [""])[0])
                if not symbol:
                    return self._send_json({"error": "symbol required"}, 400)
                return self._send_json(LIVE.ltp(symbol))
        except Exception as exc:
            return self._send_json({"error": type(exc).__name__, "message": str(exc)[:300]}, 500)
        return super().do_GET()


if __name__ == "__main__":
    PUBLIC.mkdir(parents=True, exist_ok=True)
    print(f"Live chart: http://127.0.0.1:{PORT}")
    print("Groww live feed:", "enabled" if LIVE.enabled else "disabled (set GROWW_ACCESS_TOKEN)")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
