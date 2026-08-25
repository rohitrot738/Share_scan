from __future__ import annotations

import io
import os
import time
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional

import pandas as pd
import requests
import yfinance as yf

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


def _clean_ohlcv(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    x = df.copy()
    if isinstance(x.columns, pd.MultiIndex):
        # Single-ticker downloads can still arrive as MultiIndex.
        if len(x.columns.levels[-1]) == 1:
            x.columns = x.columns.get_level_values(0)
    x.columns = [str(c).lower().replace(" ", "_") for c in x.columns]
    rename = {"adj_close": "adj_close"}
    x = x.rename(columns=rename)
    required = ["open", "high", "low", "close", "volume"]
    if not set(required).issubset(x.columns):
        return pd.DataFrame()
    x = x[required].apply(pd.to_numeric, errors="coerce").dropna(subset=["open", "high", "low", "close"])
    x["volume"] = x["volume"].fillna(0)
    return x


def fetch_nse_universe(session: Optional[requests.Session] = None) -> List[Instrument]:
    s = session or requests.Session()
    r = s.get(NSE_EQUITY_CSV, headers=HEADERS, timeout=20)
    r.raise_for_status()
    df = pd.read_csv(io.StringIO(r.text))
    df.columns = [str(c).strip() for c in df.columns]
    out: List[Instrument] = []
    for _, row in df.iterrows():
        sym = str(row.get("SYMBOL", "")).strip()
        if not sym or sym == "nan":
            continue
        name = str(row.get("NAME OF COMPANY", "")).strip()
        out.append(Instrument(sym, "NSE", f"{sym}.NS", name))
    return out


def fetch_bse_universe(session: Optional[requests.Session] = None) -> List[Instrument]:
    """Fetch active BSE equity scrips. Returns [] if BSE blocks the request.

    BSE occasionally changes/guards this endpoint, so failure is non-fatal; users can
    supply EXTRA_SYMBOLS_FILE as a fallback.
    """
    s = session or requests.Session()
    params = {"Group": "", "Scripcode": "", "industry": "", "segment": "Equity", "status": "Active"}
    try:
        r = s.get(BSE_ACTIVE_API, params=params, headers=HEADERS, timeout=25)
        r.raise_for_status()
        payload = r.json()
    except Exception:
        return []

    rows = payload.get("Table", payload if isinstance(payload, list) else [])
    out: List[Instrument] = []
    for row in rows:
        code = str(row.get("SCRIP_CD") or row.get("scrip_cd") or row.get("ScripCode") or "").strip()
        name = str(row.get("SCRIP_NAME") or row.get("scrip_name") or row.get("ScripName") or "").strip()
        if code.isdigit():
            out.append(Instrument(code, "BSE", f"{code}.BO", name))
    return out


def load_extra_symbols(path: Optional[str] = None) -> List[Instrument]:
    path = path or os.getenv("EXTRA_SYMBOLS_FILE", "")
    if not path or not os.path.exists(path):
        return []
    df = pd.read_csv(path)
    df.columns = [str(c).strip().lower() for c in df.columns]
    out = []
    for _, row in df.iterrows():
        exchange = str(row.get("exchange", "NSE")).upper().strip()
        symbol = str(row.get("symbol", "")).strip()
        if not symbol:
            continue
        yahoo = str(row.get("yahoo_symbol", "")).strip()
        if not yahoo:
            yahoo = f"{symbol}.NS" if exchange == "NSE" else f"{symbol}.BO"
        out.append(Instrument(symbol, exchange, yahoo, str(row.get("name", ""))))
    return out


def build_universe(include_nse: bool = True, include_bse: bool = True) -> List[Instrument]:
    s = requests.Session()
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


def download_batch(symbols: Iterable[str], period: str, interval: str) -> Dict[str, pd.DataFrame]:
    symbols = list(symbols)
    if not symbols:
        return {}
    raw = yf.download(
        tickers=" ".join(symbols),
        period=period,
        interval=interval,
        group_by="ticker",
        auto_adjust=False,
        threads=True,
        progress=False,
        timeout=20,
    )
    result: Dict[str, pd.DataFrame] = {}
    if raw is None or raw.empty:
        return result

    if len(symbols) == 1:
        result[symbols[0]] = _clean_ohlcv(raw)
        return result

    for sym in symbols:
        try:
            result[sym] = _clean_ohlcv(raw[sym])
        except Exception:
            result[sym] = pd.DataFrame()
    return result


def fetch_history(symbol: str, period: str, interval: str, retries: int = 2) -> pd.DataFrame:
    last = pd.DataFrame()
    for attempt in range(retries + 1):
        try:
            raw = yf.download(symbol, period=period, interval=interval, auto_adjust=False,
                              progress=False, threads=False, timeout=20)
            last = _clean_ohlcv(raw)
            if not last.empty:
                return last
        except Exception:
            pass
        time.sleep(1.5 * (attempt + 1))
    return last


def resample_ohlcv(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    agg = {"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"}
    return df.resample(rule).agg(agg).dropna(subset=["open", "high", "low", "close"])


def fetch_multitimeframe(symbol: str) -> Dict[str, pd.DataFrame]:
    """Fetch timeframes appropriate for a 1-2 week swing scanner."""
    data: Dict[str, pd.DataFrame] = {}
    for tf, period, interval in [
        ("15m", "60d", "15m"),
        ("30m", "60d", "30m"),
        ("1h", "2y", "60m"),
        ("1d", "2y", "1d"),
        ("1w", "5y", "1wk"),
    ]:
        df = fetch_history(symbol, period, interval)
        if len(df) >= 60:
            data[tf] = df
    if "1h" in data:
        four_h = resample_ohlcv(data["1h"], "4h")
        if len(four_h) >= 60:
            data["4h"] = four_h
    return data
