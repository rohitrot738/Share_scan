from __future__ import annotations

import io
import os
import time
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional

import pandas as pd
import requests
import yfinance as yf
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

NSE_EQUITY_CSV = "https://archives.nseindia.com/content/equities/EQUITY_L.csv"
BSE_ACTIVE_API = "https://api.bseindia.com/BseIndiaAPI/api/ListofScripData/w"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36",
    "Accept": "application/json,text/plain,*/*",
    "Referer": "https://www.bseindia.com/",
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
        total=4,
        connect=4,
        read=4,
        status=4,
        backoff_factor=1.0,
        status_forcelist=(429, 500, 502, 503, 504),
        allowed_methods=frozenset(["GET"]),
        raise_on_status=False,
    )
    adapter = HTTPAdapter(max_retries=retry, pool_connections=20, pool_maxsize=20)
    s.mount("https://", adapter)
    s.mount("http://", adapter)
    s.headers.update(HEADERS)
    return s


def _clean_ohlcv(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    x = df.copy()
    if isinstance(x.columns, pd.MultiIndex):
        if len(x.columns.levels[-1]) == 1:
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
        if not r.text.strip():
            return []
        df = pd.read_csv(io.StringIO(r.text))
    except Exception as exc:
        print(f"[WARN] NSE universe unavailable: {exc}")
        return []
    df.columns = [str(c).strip() for c in df.columns]
    if "SYMBOL" not in df.columns:
        print("[WARN] NSE universe format changed: SYMBOL column missing")
        return []
    out: List[Instrument] = []
    for _, row in df.iterrows():
        sym = str(row.get("SYMBOL", "")).strip()
        if not sym or sym.lower() == "nan":
            continue
        name = str(row.get("NAME OF COMPANY", "")).strip()
        out.append(Instrument(sym, "NSE", f"{sym}.NS", name))
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
        r = s.get(BSE_ACTIVE_API, params=params, timeout=(10, 35))
        r.raise_for_status()
        payload = r.json()
    except Exception as exc:
        print(f"[WARN] BSE universe unavailable: {exc}")
        return []

    rows = _normalize_bse_rows(payload)
    out: List[Instrument] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        code = str(row.get("SCRIP_CD") or row.get("scrip_cd") or row.get("ScripCode") or row.get("SCRIPCODE") or "").strip()
        name = str(row.get("SCRIP_NAME") or row.get("scrip_name") or row.get("ScripName") or row.get("SCRIPNAME") or "").strip()
        if code.isdigit():
            out.append(Instrument(code, "BSE", f"{code}.BO", name))
    if not out:
        print("[WARN] BSE universe returned no usable equity rows")
    return out


def load_extra_symbols(path: Optional[str] = None) -> List[Instrument]:
    path = path or os.getenv("EXTRA_SYMBOLS_FILE", "")
    if not path or not os.path.exists(path):
        return []
    try:
        df = pd.read_csv(path)
    except Exception as exc:
        print(f"[WARN] EXTRA_SYMBOLS_FILE unreadable: {exc}")
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
    items: List[Instrument] = []
    if include_nse:
        items.extend(fetch_nse_universe(s))
    if include_bse:
        items.extend(fetch_bse_universe(s))
    items.extend(load_extra_symbols())
    dedup = {}
    for inst in items:
        dedup[(inst.exchange, inst.yahoo_symbol)] = inst
    return list(dedup.values())


def _yf_download(symbols: List[str], period: str, interval: str, threads: bool, timeout: int = 30):
    return yf.download(
        tickers=" ".join(symbols), period=period, interval=interval,
        group_by="ticker", auto_adjust=False, threads=threads,
        progress=False, timeout=timeout,
    )


def download_batch(symbols: Iterable[str], period: str, interval: str, retries: int = 2) -> Dict[str, pd.DataFrame]:
    symbols = list(dict.fromkeys(str(s).strip() for s in symbols if str(s).strip()))
    result: Dict[str, pd.DataFrame] = {s: pd.DataFrame() for s in symbols}
    if not symbols:
        return result

    raw = None
    for attempt in range(retries + 1):
        try:
            raw = _yf_download(symbols, period, interval, threads=True, timeout=30)
            if raw is not None and not raw.empty:
                break
        except Exception as exc:
            print(f"[WARN] Yahoo batch attempt {attempt + 1} failed ({len(symbols)} symbols): {exc}")
        if attempt < retries:
            time.sleep(2.0 * (attempt + 1))

    if raw is not None and not raw.empty:
        if len(symbols) == 1:
            result[symbols[0]] = _clean_ohlcv(raw)
        else:
            for sym in symbols:
                try:
                    result[sym] = _clean_ohlcv(raw[sym])
                except Exception:
                    pass

    # Recover missing symbols individually so one bad ticker/batch cannot kill the scan.
    missing = [s for s, df in result.items() if df.empty]
    if missing and len(missing) <= 40:
        for sym in missing:
            result[sym] = fetch_history(sym, period, interval, retries=1)
    return result


def fetch_history(symbol: str, period: str, interval: str, retries: int = 3) -> pd.DataFrame:
    last = pd.DataFrame()
    for attempt in range(retries + 1):
        try:
            raw = yf.download(symbol, period=period, interval=interval, auto_adjust=False,
                              progress=False, threads=False, timeout=30)
            last = _clean_ohlcv(raw)
            if not last.empty:
                return last
        except Exception as exc:
            if attempt == retries:
                print(f"[WARN] Yahoo history failed {symbol} {interval}: {exc}")
        if attempt < retries:
            time.sleep(min(8.0, 1.5 * (2 ** attempt)))
    return last


def resample_ohlcv(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    if df is None or df.empty or not isinstance(df.index, pd.DatetimeIndex):
        return pd.DataFrame()
    try:
        agg = {"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"}
        return df.resample(rule).agg(agg).dropna(subset=["open", "high", "low", "close"])
    except Exception as exc:
        print(f"[WARN] resample {rule} failed: {exc}")
        return pd.DataFrame()


def fetch_multitimeframe(symbol: str) -> Dict[str, pd.DataFrame]:
    data: Dict[str, pd.DataFrame] = {}
    for tf, period, interval in [
        ("15m", "60d", "15m"),
        ("30m", "60d", "30m"),
        ("1h", "730d", "60m"),
        ("1d", "2y", "1d"),
        ("1w", "5y", "1wk"),
    ]:
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
