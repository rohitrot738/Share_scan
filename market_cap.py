from __future__ import annotations

import io
import math
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Iterable
from zoneinfo import ZoneInfo

import pandas as pd
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry


CRORE_RUPEES = 10_000_000.0
DEFAULT_MIN_MARKET_CAP_CR = 1000.0
NSE_SECURITY_FILE_URL = (
    "https://nsearchives.nseindia.com/content/cm/"
    "NSE_CM_security_{date_ddmmyyyy}.csv.gz"
)


@dataclass(frozen=True)
class NSEIssuedCapitalSnapshot:
    as_of: date
    issued_shares: dict[str, float]
    source_url: str


def _positive_float(value) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number > 0 else None


def parse_nse_security_file(
    payload: bytes,
    *,
    as_of: date,
    source_url: str = "",
) -> NSEIssuedCapitalSnapshot:
    """Parse the official daily NSE MII security file.

    `IssdCptl` is the issued number of securities.  Only live `EQ` rows are
    suitable for the ordinary-equity scanner; alternate settlement series and
    deleted instruments are deliberately ignored.
    """
    if not payload:
        raise ValueError("NSE security file is empty")
    try:
        frame = pd.read_csv(
            io.BytesIO(payload),
            compression="gzip",
            usecols=["TckrSymb", "SctySrs", "IssdCptl", "DelFlg"],
            low_memory=False,
        )
    except Exception as exc:
        raise ValueError(f"invalid NSE security file: {exc}") from exc

    frame["TckrSymb"] = frame["TckrSymb"].astype(str).str.strip().str.upper()
    frame["SctySrs"] = frame["SctySrs"].astype(str).str.strip().str.upper()
    frame["DelFlg"] = frame["DelFlg"].astype(str).str.strip().str.upper()
    frame["IssdCptl"] = pd.to_numeric(frame["IssdCptl"], errors="coerce")
    frame = frame[
        (frame["SctySrs"] == "EQ")
        & (frame["DelFlg"] != "Y")
        & frame["TckrSymb"].ne("")
        & frame["IssdCptl"].gt(0)
    ]
    frame = frame.drop_duplicates("TckrSymb", keep="last")
    issued = {
        str(row.TckrSymb): float(row.IssdCptl)
        for row in frame.itertuples(index=False)
    }
    if not issued:
        raise ValueError("NSE security file has no live EQ issued-capital rows")
    return NSEIssuedCapitalSnapshot(as_of=as_of, issued_shares=issued, source_url=source_url)


def _session() -> requests.Session:
    retry = Retry(
        total=2,
        connect=2,
        read=2,
        status=2,
        backoff_factor=0.6,
        status_forcelist=(429, 500, 502, 503, 504),
        allowed_methods=frozenset(["GET"]),
        raise_on_status=False,
    )
    adapter = HTTPAdapter(max_retries=retry, pool_connections=4, pool_maxsize=4)
    session = requests.Session()
    session.mount("https://", adapter)
    session.headers.update(
        {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 Chrome/124 Safari/537.36"
            ),
            "Accept": "text/csv,application/gzip,application/octet-stream,*/*",
            "Referer": "https://www.nseindia.com/all-reports",
        }
    )
    return session


def fetch_nse_issued_capital(
    *,
    as_of: date | None = None,
    lookback_days: int = 10,
    session: requests.Session | None = None,
) -> NSEIssuedCapitalSnapshot:
    """Fetch the newest available official NSE security file.

    NSE publishes the file for trading days, so weekends and exchange holidays
    are handled by checking recent calendar dates.  A suspiciously small file
    is rejected instead of allowing an incomplete market-cap universe.
    """
    anchor = as_of or datetime.now(ZoneInfo("Asia/Kolkata")).date()
    client = session or _session()
    failures: list[str] = []
    for offset in range(max(1, int(lookback_days))):
        candidate = anchor - timedelta(days=offset)
        url = NSE_SECURITY_FILE_URL.format(date_ddmmyyyy=candidate.strftime("%d%m%Y"))
        try:
            response = client.get(url, timeout=(8, 30))
            if response.status_code == 404:
                failures.append(f"{candidate}:404")
                continue
            response.raise_for_status()
            snapshot = parse_nse_security_file(
                response.content,
                as_of=candidate,
                source_url=url,
            )
            if len(snapshot.issued_shares) < 500:
                raise ValueError(
                    f"only {len(snapshot.issued_shares)} live EQ rows in NSE security file"
                )
            return snapshot
        except Exception as exc:
            failures.append(f"{candidate}:{type(exc).__name__}")
    detail = ", ".join(failures[-5:])
    raise RuntimeError(
        "No valid recent NSE issued-capital snapshot; market-cap filter fails closed"
        + (f" ({detail})" if detail else "")
    )


def apply_market_cap_filter(
    rows: Iterable[dict],
    snapshot: NSEIssuedCapitalSnapshot,
    *,
    min_market_cap_cr: float = DEFAULT_MIN_MARKET_CAP_CR,
) -> tuple[list[dict], dict[str, int]]:
    """Attach market cap and keep only NSE equities strictly above the limit."""
    limit = float(min_market_cap_cr)
    if not math.isfinite(limit) or limit < 0:
        raise ValueError("min_market_cap_cr must be a finite non-negative number")

    eligible: list[dict] = []
    input_rows = 0
    below = 0
    missing = 0
    for original in rows:
        input_rows += 1
        symbol = str(original.get("symbol", "")).strip().upper()
        exchange = str(original.get("exchange", "")).strip().upper()
        price = _positive_float(original.get("price"))
        if price is None:
            price = _positive_float(original.get("close"))
        issued_shares = _positive_float(snapshot.issued_shares.get(symbol))
        if exchange != "NSE" or price is None or issued_shares is None:
            missing += 1
            continue
        market_cap_cr = price * issued_shares / CRORE_RUPEES
        if market_cap_cr <= limit:
            below += 1
            continue
        row = dict(original)
        row["issued_shares"] = int(round(issued_shares))
        row["market_cap_cr"] = round(market_cap_cr, 4)
        row["market_cap_source"] = "NSE_MII_SECURITY_FILE"
        row["market_cap_source_date"] = snapshot.as_of.isoformat()
        eligible.append(row)

    stats = {
        "input_rows": input_rows,
        "eligible_rows": len(eligible),
        "at_or_below_limit": below,
        "missing_market_cap": missing,
    }
    return eligible, stats
