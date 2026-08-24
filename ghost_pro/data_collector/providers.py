from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict
import math
import pandas as pd


class ProviderError(RuntimeError):
    pass


def _clean_number(v):
    try:
        if v is None or (isinstance(v, float) and math.isnan(v)):
            return None
        return float(v)
    except Exception:
        return None


@dataclass
class PriceSnapshot:
    symbol: str
    price: float | None
    market_cap: float | None
    trailing_pe: float | None
    price_to_book: float | None
    fifty_two_week_high: float | None
    fifty_two_week_low: float | None


class YahooIndiaProvider:
    """CPU-light public-market provider using yfinance.

    This adapter is intentionally isolated so another broker/official exchange
    provider can replace it later without changing the 360CR engine.
    """

    def __init__(self):
        try:
            import yfinance as yf
        except Exception as e:
            raise ProviderError("yfinance is not installed") from e
        self.yf = yf

    @staticmethod
    def canonical(symbol: str, exchange: str = "NSE") -> str:
        s = symbol.strip().upper()
        if "." in s:
            return s
        return f"{s}.NS" if exchange.upper() == "NSE" else f"{s}.BO"

    def ticker(self, symbol: str, exchange: str = "NSE"):
        return self.yf.Ticker(self.canonical(symbol, exchange))

    def price_snapshot(self, symbol: str, exchange: str = "NSE") -> Dict[str, Any]:
        t = self.ticker(symbol, exchange)
        fi = getattr(t, "fast_info", {}) or {}
        try:
            info = t.info or {}
        except Exception:
            info = {}
        price = fi.get("last_price") or info.get("currentPrice") or info.get("regularMarketPrice")
        return {
            "symbol": symbol.upper(),
            "exchange": exchange.upper(),
            "price": _clean_number(price),
            "market_cap": _clean_number(fi.get("market_cap") or info.get("marketCap")),
            "trailing_pe": _clean_number(info.get("trailingPE")),
            "forward_pe": _clean_number(info.get("forwardPE")),
            "price_to_book": _clean_number(info.get("priceToBook")),
            "enterprise_value": _clean_number(info.get("enterpriseValue")),
            "ev_to_ebitda": _clean_number(info.get("enterpriseToEbitda")),
            "dividend_yield": _clean_number(info.get("dividendYield")),
            "fifty_two_week_high": _clean_number(fi.get("year_high") or info.get("fiftyTwoWeekHigh")),
            "fifty_two_week_low": _clean_number(fi.get("year_low") or info.get("fiftyTwoWeekLow")),
            "shares_outstanding": _clean_number(info.get("sharesOutstanding")),
            "book_value_per_share": _clean_number(info.get("bookValue")),
            "sector": info.get("sector"),
            "industry": info.get("industry"),
        }

    @staticmethod
    def _statement_to_records(df: pd.DataFrame, max_periods: int = 20):
        if df is None or df.empty:
            return []
        out = []
        cols = list(df.columns)[:max_periods]
        for c in cols:
            rec = {"period": str(c)}
            for idx in df.index:
                val = df.loc[idx, c]
                cleaned = _clean_number(val)
                if cleaned is not None:
                    rec[str(idx)] = cleaned
            out.append(rec)
        return out

    def financials(self, symbol: str, exchange: str = "NSE", max_periods: int = 20) -> Dict[str, Any]:
        t = self.ticker(symbol, exchange)
        return {
            "quarterly_income_statement": self._statement_to_records(getattr(t, "quarterly_income_stmt", pd.DataFrame()), max_periods),
            "quarterly_balance_sheet": self._statement_to_records(getattr(t, "quarterly_balance_sheet", pd.DataFrame()), max_periods),
            "quarterly_cash_flow": self._statement_to_records(getattr(t, "quarterly_cashflow", pd.DataFrame()), max_periods),
            "annual_income_statement": self._statement_to_records(getattr(t, "income_stmt", pd.DataFrame()), 8),
            "annual_balance_sheet": self._statement_to_records(getattr(t, "balance_sheet", pd.DataFrame()), 8),
            "annual_cash_flow": self._statement_to_records(getattr(t, "cashflow", pd.DataFrame()), 8),
        }

    def price_history(self, symbol: str, exchange: str = "NSE", period: str = "5y", interval: str = "1d") -> pd.DataFrame:
        t = self.ticker(symbol, exchange)
        h = t.history(period=period, interval=interval, auto_adjust=False)
        if h is None or h.empty:
            raise ProviderError(f"No price history for {symbol}")
        h = h.reset_index()
        h.columns = [str(c).lower().replace(" ", "_") for c in h.columns]
        rename = {"date":"timestamp", "datetime":"timestamp"}
        h = h.rename(columns=rename)
        keep = [c for c in ["timestamp","open","high","low","close","volume","dividends","stock_splits"] if c in h.columns]
        return h[keep]

    def holders(self, symbol: str, exchange: str = "NSE") -> Dict[str, Any]:
        t = self.ticker(symbol, exchange)
        out: Dict[str, Any] = {}
        for attr in ["major_holders", "institutional_holders", "mutualfund_holders", "insider_transactions", "insider_purchases", "insider_roster_holders"]:
            try:
                df = getattr(t, attr)
                if isinstance(df, pd.DataFrame) and not df.empty:
                    out[attr] = df.reset_index(drop=True).astype(object).where(pd.notna(df), None).to_dict("records")
            except Exception:
                continue
        return out
