from __future__ import annotations

import io
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Dict, Iterable, List, Optional

import pandas as pd
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

NSE_EQUITY_CSV = "https://archives.nseindia.com/content/equities/EQUITY_L.csv"
BSE_ACTIVE_API = "https://api.bseindia.com/BseIndiaAPI/api/ListofScripData/w"
YAHOO_CHART = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36",
    "Accept": "application/json,text/plain,*/*",
    "Referer": "https://finance.yahoo.com/",
}


@dataclass(frozen=True)
class Instrument:
    symbol: str
    exchange: str
    yahoo_symbol: str
    name: str = ""


def _session() -> requests.Session:
    s = requests.Session()
    retry = Retry(
        total=3, connect=3, read=3, status=3, backoff_factor=0.8,
        status_forcelist=(429, 500, 502, 503, 504),
        allowed_methods=frozenset(["GET"]), raise_on_status=False,
    )
    adapter = HTTPAdapter(max_retries=retry, pool_connections=12, pool_maxsize=12)
    s.mount("https://", adapter)
    s.mount("http://", adapter)
    s.headers.update(HEADERS)
    return s


def _clean_ohlcv(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    x = df.copy()
    if isinstance(x.columns, pd.MultiIndex) and len(x.columns.levels[-1]) == 1:
        x.columns = x.columns.get_level_values(0)
    x.columns = [str(c).lower().replace(" ", "_") for c in x.columns]
    required = ["open", "high", "low", "close", "volume"]
    if not set(required).issubset(x.columns):
        return pd.DataFrame()
    x = x[required].apply(pd.to_numeric, errors="coerce").dropna(subset=["open", "high", "low", "close"])
    x = x[(x["open"] > 0) & (x["high"] > 0) & (x["low"] > 0) & (x["close"] > 0)]
    x["volume"] = x["volume"].fillna(0).clip(lower=0)
    return x


def fetch_nse_universe(session: Optional[requests.Session] = None) -> List[Instrument]:
    s = session or _session()
    try:
        r = s.get(NSE_EQUITY_CSV, timeout=(10, 30))
        r.raise_for_status()
        df = pd.read_csv(io.StringIO(r.text))
    except Exception as exc:
        print(f"[WARN] NSE universe unavailable: {exc}")
        return []
    df.columns = [str(c).strip() for c in df.columns]
    if "SYMBOL" not in df.columns:
        return []
    out = []
    for _, row in df.iterrows():
        sym = str(row.get("SYMBOL", "")).strip()
        if sym and sym.lower() != "nan":
            out.append(Instrument(sym, "NSE", f"{sym}.NS", str(row.get("NAME OF COMPANY", "")).strip()))
    return out


def _normalize_bse_rows(payload) -> list:
    if isinstance(payload, list):
        return payload
    if not isinstance(payload, dict):
        return []
    rows = payload.get("Table") or payload.get("table") or payload.get("data") or payload.get("Data") or []
    for _ in range(3):
        if isinstance(rows, list):
            return rows
        if isinstance(rows, dict):
            rows = rows.get("Table") or rows.get("table") or rows.get("data") or rows.get("Data") or []
        else:
            break
    return rows if isinstance(rows, list) else []


def fetch_bse_universe(session: Optional[requests.Session] = None) -> List[Instrument]:
    s = session or _session()
    params = {"Group": "", "Scripcode": "", "industry": "", "segment": "Equity", "status": "Active"}
    try:
        r = s.get(BSE_ACTIVE_API, params=params, timeout=(10, 30))
        r.raise_for_status()
        payload = r.json()
    except Exception as exc:
        print(f"[WARN] BSE universe unavailable: {exc}")
        return []
    out = []
    for row in _normalize_bse_rows(payload):
        if not isinstance(row, dict):
            continue
        code = str(row.get("SCRIP_CD") or row.get("scrip_cd") or row.get("ScripCode") or "").strip()
        name = str(row.get("SCRIP_NAME") or row.get("scrip_name") or row.get("ScripName") or "").strip()
        if code.isdigit():
            out.append(Instrument(code, "BSE", f"{code}.BO", name))
    return out


def load_extra_symbols(path: Optional[str] = None) -> List[Instrument]:
    path = path or os.getenv("EXTRA_SYMBOLS_FILE", "")
    if not path or not os.path.exists(path):
        return []
    try:
        df = pd.read_csv(path)
    except Exception:
        return []
    df.columns = [str(c).strip().lower() for c in df.columns]
    out = []
    for _, row in df.iterrows():
        exchange = str(row.get("exchange", "NSE")).upper().strip()
        symbol = str(row.get("symbol", "")).strip()
        if not symbol or symbol.lower() == "nan":
            continue
        yahoo = str(row.get("yahoo_symbol", "")).strip()
        if not yahoo or yahoo.lower() == "nan":
            yahoo = f"{symbol}.NS" if exchange == "NSE" else f"{symbol}.BO"
        out.append(Instrument(symbol, exchange, yahoo, str(row.get("name", ""))))
    return out


def build_universe(include_nse: bool = True, include_bse: bool = True) -> List[Instrument]:
    s = _session()
    items = []
    if include_nse:
        items.extend(fetch_nse_universe(s))
    if include_bse:
        items.extend(fetch_bse_universe(s))
    items.extend(load_extra_symbols())
    dedup = {(inst.exchange, inst.yahoo_symbol): inst for inst in items}
    return list(dedup.values())


def _period_to_days(period: str) -> int:
    p = str(period).lower().strip()
    if p.endswith("d"):
        return max(1, int(float(p[:-1])))
    if p.endswith("mo"):
        return max(1, int(float(p[:-2]) * 31))
    if p.endswith("y"):
        return max(1, int(float(p[:-1]) * 365))
    return 365


def _interval_seconds(interval: str) -> int:
    i = str(interval).lower().strip()
    if i.endswith("m"):
        return max(60, int(i[:-1]) * 60)
    if i.endswith("h"):
        return max(3600, int(i[:-1]) * 3600)
    if i in ("1d", "1day"):
        return 86400
    if i in ("1wk", "1w"):
        return 604800
    return 86400


def _yahoo_history(symbol: str, period: str, interval: str, session: requests.Session) -> pd.DataFrame:
    days = _period_to_days(period)
    end = datetime.now(timezone.utc)
    start = end - timedelta(days=days + 2)
    params = {
        "period1": int(start.timestamp()),
        "period2": int(end.timestamp()),
        "interval": interval,
        "events": "history",
        "includeAdjustedClose": "true",
        "includePrePost": "false",
    }
    r = session.get(YAHOO_CHART.format(symbol=symbol), params=params, timeout=(10, 25))
    r.raise_for_status()
    payload = r.json()
    result = (payload.get("chart") or {}).get("result") or []
    if not result:
        return pd.DataFrame()
    data = result[0]
    timestamps = data.get("timestamp") or []
    quote = ((data.get("indicators") or {}).get("quote") or [{}])[0]
    if not timestamps:
        return pd.DataFrame()
    frame = pd.DataFrame({
        "open": quote.get("open", []),
        "high": quote.get("high", []),
        "low": quote.get("low", []),
        "close": quote.get("close", []),
        "volume": quote.get("volume", []),
    }, index=pd.to_datetime(timestamps, unit="s", utc=True))
    frame.index.name = "datetime"
    return _clean_ohlcv(frame)


def fetch_history(symbol: str, period: str, interval: str, retries: int = 2) -> pd.DataFrame:
    last = pd.DataFrame()
    session = _session()
    for attempt in range(retries + 1):
        try:
            last = _yahoo_history(symbol, period, interval, session)
            if not last.empty:
                return last
        except Exception as exc:
            if attempt == retries:
                print(f"[WARN] Yahoo history failed {symbol} {interval}: {exc}")
        if attempt < retries:
            time.sleep(min(4.0, 1.0 * (2 ** attempt)))
    return last


def download_batch(symbols: Iterable[str], period: str, interval: str, retries: int = 2) -> Dict[str, pd.DataFrame]:
    symbols = list(dict.fromkeys(str(s).strip() for s in symbols if str(s).strip()))
    result = {s: pd.DataFrame() for s in symbols}
    if not symbols:
        return result
    workers = min(8, max(1, len(symbols)))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fetch_history, sym, period, interval, retries): sym for sym in symbols}
        for future in as_completed(futures):
            sym = futures[future]
            try:
                result[sym] = future.result()
            except Exception as exc:
                print(f"[WARN] Yahoo batch failed {sym}: {exc}")
    return result


def resample_ohlcv(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    if df is None or df.empty or not isinstance(df.index, pd.DatetimeIndex):
        return pd.DataFrame()
    try:
        return df.resample(rule).agg({"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"}).dropna(subset=["open", "high", "low", "close"])
    except Exception as exc:
        print(f"[WARN] resample {rule} failed: {exc}")
        return pd.DataFrame()


def fetch_superfast_multitimeframe(symbol: str) -> Dict[str, pd.DataFrame]:
    data = {}
    for tf, period, interval in [("15m", "30d", "15m"), ("1h", "180d", "60m"), ("1d", "6mo", "1d")]:
        df = fetch_history(symbol, period, interval, retries=1)
        if len(df) >= 60:
            data[tf] = df
    return data


def fetch_multitimeframe(symbol: str) -> Dict[str, pd.DataFrame]:
    data = {}
    for tf, period, interval in [("15m", "60d", "15m"), ("30m", "60d", "30m"), ("1h", "730d", "60m"), ("1d", "2y", "1d"), ("1w", "5y", "1wk")]:
        try:
            df = fetch_history(symbol, period, interval)
            if len(df) >= 60:
                data[tf] = df
        except Exception as exc:
            print(f"[WARN] timeframe {tf} skipped for {symbol}: {exc}")
    if "1h" in data:
        four_h = resample_ohlcv(data["1h"], "4h")
        if len(four_h) >= 60:
            data["4h"] = four_h
    return data
