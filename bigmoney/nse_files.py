"""NSE's daily delivery file (sec_bhavdata_full): every share's close, traded and deliverable quantity.

Only the NSE main board (series EQ) and SME (SM) are kept. Trade-to-trade shares (BE, BZ, ST) carry no delivery
figure or a forced 100%, and the other series (bonds, gold bonds, InvITs, REITs, funds) are not company shares."""
from __future__ import annotations

import csv
import io
from collections import namedtuple
from datetime import date

from cr360.build_app_cache import BROWSER_UA, http_get, iso_date

SEC_URL = "https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_{}.csv"
SERIES = ("EQ", "SM")

Row = namedtuple("Row", "series close prev_close volume deliv_pct")


def _number(v: str | None) -> float | None:
    try:
        return float(str(v).replace(",", "").strip())
    except (TypeError, ValueError):
        return None


def parse_delivery(text: str) -> tuple[str | None, dict[str, Row]]:
    """The file's session as an ISO date and its EQ and SM rows by symbol; a delivery of "-" reads as None."""
    day, rows = None, {}
    if not text.lstrip().startswith("SYMBOL"):
        return None, {}
    for raw in csv.DictReader(io.StringIO(text)):
        r = {k.strip(): (v or "").strip() for k, v in raw.items() if k}
        series = r.get("SERIES")
        symbol = r.get("SYMBOL", "").upper()
        if series not in SERIES or not symbol:
            continue
        day = day or iso_date(r.get("DATE1"))
        volume = _number(r.get("TTL_TRD_QNTY"))
        rows[symbol] = Row(series, _number(r.get("CLOSE_PRICE")), _number(r.get("PREV_CLOSE")),
                           int(volume) if volume is not None else None, _number(r.get("DELIV_PER")))
    return day, rows


def fetch_delivery(day: date, get=http_get) -> str | None:
    """The file for `day`; None when NSE has none (a holiday, not out yet) or answers with something else."""
    status, text, _ = get(SEC_URL.format(day.strftime("%d%m%Y")), "text/csv", BROWSER_UA, 60, "https://www.nseindia.com/")
    return text if status == 200 and text.lstrip().startswith("SYMBOL") else None
