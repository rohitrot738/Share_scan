from __future__ import annotations

import json
import os
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

import pandas as pd
import requests

from market_data import fetch_history

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

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36",
    "Accept": "application/json,text/plain,*/*",
    "Accept-Language": "en-US,en;q=0.9",
}


def _symbol(raw: str) -> str:
    s = str(raw or "").upper().strip().replace("NSE:", "")
    if s.endswith(".NS"):
        s = s[:-3]
    return "".join(ch for ch in s if ch.isalnum() or ch in "-&")[:32]


def _jsonable_frame(df: pd.DataFrame, limit: int = 1200) -> list[dict]:
    if df is None or df.empty:
        return []
    rows = []
    for idx, r in df.tail(limit).iterrows():
        try:
            rows.append({
                "time": int(pd.Timestamp(idx).timestamp()),
                "open": float(r["open"]),
                "high": float(r["high"]),
                "low": float(r["low"]),
                "close": float(r["close"]),
                "volume": float(r.get("volume", 0) or 0),
            })
        except Exception:
            continue
    return rows


def _resample_for_chart(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    if df is None or df.empty or not isinstance(df.index, pd.DatetimeIndex):
        return pd.DataFrame()
    return (
        df.resample(rule, origin=df.index[0])
        .agg({"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"})
        .dropna(subset=["open", "high", "low", "close"])
    )


def history(symbol: str, timeframe: str) -> dict:
    if timeframe not in _HISTORY:
        raise ValueError(f"Unsupported timeframe: {timeframe}")
    period, source_interval, rule = _HISTORY[timeframe]
    df = fetch_history(f"{symbol}.NS", period, source_interval, retries=1)
    if rule:
        df = _resample_for_chart(df, rule)
    if df is None or df.empty:
        raise LookupError(f"No market candles available for {symbol} at {timeframe}")
    return {
        "symbol": symbol,
        "timeframe": timeframe,
        "source": "Auto public feed: Yahoo OHLC history + NSE/Yahoo live quote fallback",
        "candles": _jsonable_frame(df),
    }


class AutoPublicLiveFeed:
    """No-key live/near-live quote feed with automatic provider fallback."""

    def __init__(self):
        self._lock = threading.Lock()
        self._nse = requests.Session()
        self._nse.headers.update({**HEADERS, "Referer": "https://www.nseindia.com/"})
        self._web = requests.Session()
        self._web.headers.update(HEADERS)
        self._nse_ready = False
        self._cache: dict[str, tuple[float, dict]] = {}
        self.cache_seconds = float(os.getenv("PUBLIC_LTP_CACHE_SECONDS", "2.0"))

    def _nse_quote(self, symbol: str) -> dict:
        if not self._nse_ready:
            r = self._nse.get("https://www.nseindia.com/", timeout=(4, 7))
            r.raise_for_status()
            self._nse_ready = True
        url = "https://www.nseindia.com/api/quote-equity?symbol=" + quote(symbol, safe="")
        r = self._nse.get(url, timeout=(4, 7))
        if r.status_code in (401, 403):
            self._nse_ready = False
            raise RuntimeError(f"NSE HTTP {r.status_code}")
        r.raise_for_status()
        data = r.json()
        price = (data.get("priceInfo") or {}).get("lastPrice")
        if price is None:
            raise LookupError("NSE lastPrice missing")
        return {
            "live": True,
            "symbol": symbol,
            "ltp": float(price),
            "time": int(time.time()),
            "provider": "NSE Public",
            "mode": "AUTO_PUBLIC",
        }

    def _yahoo_quote(self, symbol: str) -> dict:
        ticker = f"{symbol}.NS"
        url = f"https://query1.finance.yahoo.com/v8/finance/chart/{quote(ticker, safe='')}?interval=1m&range=1d"
        r = self._web.get(url, timeout=(4, 7))
        r.raise_for_status()
        root = r.json().get("chart", {})
        if root.get("error"):
            raise LookupError(str(root["error"])[:160])
        result = (root.get("result") or [None])[0]
        if not result:
            raise LookupError("Yahoo quote result missing")
        meta = result.get("meta") or {}
        price = meta.get("regularMarketPrice")
        ts = meta.get("regularMarketTime")
        if price is None:
            quote_rows = (((result.get("indicators") or {}).get("quote") or [{}])[0]).get("close") or []
            prices = [x for x in quote_rows if x is not None]
            if prices:
                price = prices[-1]
        if price is None:
            raise LookupError("Yahoo price missing")
        return {
            "live": True,
            "symbol": symbol,
            "ltp": float(price),
            "time": int(ts or time.time()),
            "provider": "Yahoo Public",
            "mode": "AUTO_PUBLIC_FALLBACK",
            "market_state": str(meta.get("marketState") or ""),
        }

    def ltp(self, symbol: str) -> dict:
        now = time.time()
        cached = self._cache.get(symbol)
        if cached and now - cached[0] < self.cache_seconds:
            return dict(cached[1])
        errors = []
        with self._lock:
            cached = self._cache.get(symbol)
            if cached and now - cached[0] < self.cache_seconds:
                return dict(cached[1])
            for name, fn in (("NSE Public", self._nse_quote), ("Yahoo Public", self._yahoo_quote)):
                try:
                    payload = fn(symbol)
                    self._cache[symbol] = (time.time(), payload)
                    return payload
                except Exception as exc:
                    errors.append(f"{name}: {type(exc).__name__}: {str(exc)[:90]}")
            return {
                "live": False,
                "symbol": symbol,
                "provider": "none",
                "mode": "AUTO_PUBLIC",
                "reason": " | ".join(errors)[:300] or "No public quote provider available",
            }


LIVE = AutoPublicLiveFeed()


def scanner_symbols() -> list[str]:
    paths = [
        ROOT / "scan_output" / "top100_by_volume.csv",
        ROOT / "scan_results" / "latest.csv",
        ROOT / ".scan_cache" / "nse_stage1.csv",
    ]
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
        rel = unquote(urlparse(path).path).lstrip("/") or "index.html"
        candidate = (PUBLIC / rel).resolve()
        try:
            candidate.relative_to(PUBLIC.resolve())
        except ValueError:
            return str(PUBLIC / "__not_found__")
        return str(candidate)

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
            if parsed.path == "/api/feed-status":
                return self._send_json({"mode": "AUTO_PUBLIC", "providers": ["NSE Public", "Yahoo Public"], "credentials_required": False})
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
        except ValueError as exc:
            return self._send_json({"error": type(exc).__name__, "message": str(exc)[:300]}, 400)
        except LookupError as exc:
            return self._send_json({"error": type(exc).__name__, "message": str(exc)[:300]}, 502)
        except Exception as exc:
            return self._send_json({"error": type(exc).__name__, "message": str(exc)[:300]}, 500)
        return super().do_GET()


if __name__ == "__main__":
    PUBLIC.mkdir(parents=True, exist_ok=True)
    print(f"Live chart: http://127.0.0.1:{PORT}")
    print("Live quote mode: AUTO_PUBLIC (NSE -> Yahoo fallback); no API key required")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
