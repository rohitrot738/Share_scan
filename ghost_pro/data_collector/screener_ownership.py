from __future__ import annotations

"""Automatic Indian shareholding-history adapter using Screener.in public pages.

This is a best-effort public-web fallback, not an official exchange feed.
If the page layout changes or access is blocked, the collector returns no data
instead of fabricating values. Downstream 360CR therefore degrades confidence
rather than inventing promoter/FII/DII history.
"""

from io import StringIO
from typing import Any, Dict, List
import re

import pandas as pd
import requests

from .india_ownership import OwnershipProviderError


class ScreenerOwnershipProvider:
    BASE = "https://www.screener.in/company/{symbol}/"

    def __init__(self, timeout: int = 15):
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": "Mozilla/5.0 (compatible; Share_scan/1.0; research tool)",
            "Accept": "text/html,application/xhtml+xml",
        })

    @staticmethod
    def _clean_label(value: Any) -> str:
        return re.sub(r"\s+", " ", str(value or "").replace("+", "")).strip()

    @staticmethod
    def _pct(value: Any):
        if value is None:
            return None
        s = str(value).strip().replace("%", "").replace(",", "")
        if not s or s in {"-", "--", "nan", "NaN"}:
            return None
        try:
            return float(s)
        except Exception:
            return None

    def _url(self, symbol: str) -> str:
        # Screener resolves Indian listed-company symbols in the URL path.
        return self.BASE.format(symbol=symbol.strip().upper())

    def history(self, symbol: str, exchange: str = "NSE", max_quarters: int = 20) -> List[Dict[str, Any]]:
        url = self._url(symbol)
        r = self.session.get(url, timeout=self.timeout)
        if r.status_code != 200:
            raise OwnershipProviderError(f"Screener HTTP {r.status_code}")

        try:
            tables = pd.read_html(StringIO(r.text))
        except Exception as e:
            raise OwnershipProviderError(f"shareholding tables could not be parsed: {e}") from e

        candidate = None
        for t in tables:
            if t is None or t.empty or t.shape[1] < 3:
                continue
            first = t.iloc[:, 0].astype(str).map(self._clean_label).str.lower()
            joined = " | ".join(first.tolist())
            # Quarterly shareholding table normally contains these rows.
            if "promoters" in joined and "fiis" in joined and "diis" in joined and "public" in joined:
                # Prefer the table with more columns, usually quarterly vs yearly.
                if candidate is None or t.shape[1] > candidate.shape[1]:
                    candidate = t

        if candidate is None:
            raise OwnershipProviderError("shareholding pattern table not found")

        t = candidate.copy()
        labels = t.iloc[:, 0].astype(str).map(self._clean_label)
        t.index = labels
        t = t.iloc[:, 1:]
        periods = [self._clean_label(c) for c in t.columns]

        def row_matching(*needles: str):
            for idx in t.index:
                low = idx.lower()
                if any(n in low for n in needles):
                    return t.loc[idx]
            return None

        promoters = row_matching("promoter")
        fiis = row_matching("fii", "fpi")
        diis = row_matching("dii")
        public = row_matching("public")
        shareholders = row_matching("no. of shareholders", "number of shareholders")

        rows: List[Dict[str, Any]] = []
        for i, period in enumerate(periods):
            row = {
                "period": period,
                "promoter": self._pct(promoters.iloc[i]) if promoters is not None and i < len(promoters) else None,
                "fii": self._pct(fiis.iloc[i]) if fiis is not None and i < len(fiis) else None,
                "dii": self._pct(diis.iloc[i]) if diis is not None and i < len(diis) else None,
                "public": self._pct(public.iloc[i]) if public is not None and i < len(public) else None,
                "shareholders": self._pct(shareholders.iloc[i]) if shareholders is not None and i < len(shareholders) else None,
                "mutual_fund": None,
                "pledge": None,
                "source": url,
            }
            if any(row[k] is not None for k in ["promoter", "fii", "dii", "public"]):
                rows.append(row)

        if not rows:
            raise OwnershipProviderError("shareholding table contained no usable percentages")

        # Screener table is oldest->newest; retain latest max_quarters.
        return rows[-max_quarters:]
