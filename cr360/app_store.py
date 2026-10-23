"""The 360CR cache behind the app: every NSE stock's results, shareholding and deals, gathered once and then only
topped up. Past quarters do not change, so a stock's page is read again only when its next results are due; what
falls outside the ranges 360CR looks at is dropped, so the cache stays the same size year after year.

The store is plain JSON (see `empty`); `app_record` turns one stock's 360CR into the compact row the app reads."""
from __future__ import annotations

import zlib
from datetime import date, timedelta
from statistics import median

from .engine import analyse_360cr
from .models import ResearchInput

VERSION = 1
QUARTERS_KEPT = 20          # quarterly results and shareholding: 5 years
YEARS_KEPT = 5              # yearly results, balance sheet and cash flow
EVENT_YEARS = 5             # insider trades and bulk/block deals
CHECK_EVERY_DAYS = 7        # a stock waiting for its next results is looked at once a week
LATE_DAYS = 120             # results this late (or never coming) ...
LATE_CHECK_DAYS = 30        # ... are looked for once a month
REFRESH_DAYS = 100          # and every page is read again at least this often (restated numbers, book value)
RESULTS_GRACE_DAYS = 20     # results are not expected sooner than this after a quarter ends
RESULTS_LAG_DAYS = 45       # a period's results are public by this many days after it ends (for past P/Es)
HISTORY_PE_MONTHS = 12      # month-ends with a known P/E needed before the company's own multiple counts
MIN_PE = 3.0                # earnings priced under this many times (a one-off still in the year, stale figures) value nothing
ONE_OFF = 10.0              # a quarter earning over this many times the year's middle quarter is a one-off
HISTORY_SHARDS = 32         # the app's history files (cr360-h-00 ... cr360-h-31), fetched one at a time


def empty() -> dict:
    return {"version": VERSION, "updated": None, "stocks": {}}


def _d(s) -> date:
    return date.fromisoformat(str(s)[:10])


def merge_periods(old: list[dict], new: list[dict], keep: int) -> list[dict]:
    """Rows of both by period, the new reading winning, oldest first, at most the last `keep`."""
    rows = {r["period"]: r for r in old or []}
    rows.update({r["period"]: r for r in new or []})
    return [rows[p] for p in sorted(rows)][-keep:]


def merge_events(old: list, new: list) -> list:
    """[date, side, value] rows of both without repeats, oldest first."""
    seen, out = set(), []
    for e in sorted((old or []) + (new or []), key=lambda e: (e[0], e[1], e[2] or 0)):
        key = (e[0], e[1], round(e[2] or 0, 2))
        if key not in seen:
            seen.add(key)
            out.append(e)
    return out


def latest_due_quarter(today: date) -> date:
    """The last quarter end whose results can be out by today."""
    d = today - timedelta(days=RESULTS_GRACE_DAYS)
    q_month = (d.month - 1) // 3 * 3  # 0, 3, 6, 9: the month before the quarter that d falls in
    y = d.year if q_month else d.year - 1
    m = q_month or 12
    nxt = date(y + (m == 12), m % 12 + 1, 1)
    return nxt - timedelta(days=1)


def half_yearly(quarters: list[dict]) -> bool:
    """Whether the company reports every six months (most SMEs do); Screener then lists half-years as its quarters."""
    return len(quarters) >= 2 and (_d(quarters[-1]["period"]) - _d(quarters[-2]["period"])).days > 150


def next_results(quarters: list[dict]) -> date | None:
    """When the period after the latest one ends: a quarter on, or half a year for half-yearly reporters."""
    if not quarters:
        return None
    return _d(quarters[-1]["period"]) + timedelta(days=182 if half_yearly(quarters) else 91)


def is_due(stock: dict | None, today: date) -> bool:
    """Whether the stock's page should be read: never read, its next results can be out, or a long time passed.
    A company far behind with its results is looked at monthly rather than weekly."""
    if not stock or not stock.get("checked"):
        return True
    age = (today - _d(stock["checked"])).days
    if age >= REFRESH_DAYS:
        return True
    nxt = next_results(stock.get("quarters") or [])
    if nxt is None:  # no results on Screener (or no page): a new listing's first results take a while
        return age >= LATE_CHECK_DAYS
    expected = nxt + timedelta(days=RESULTS_GRACE_DAYS)
    if today < expected:
        return False
    return age >= (CHECK_EVERY_DAYS if (today - expected).days <= LATE_DAYS else LATE_CHECK_DAYS)


MARKET_ORDER = {"nse": 0, "sme": 1, "bse": 2}


def due_order(store: dict, universe: dict[str, dict], today: date) -> list[str]:
    """Stocks to read: those never read first, NSE before SME before BSE, then the longest unread."""
    stocks = store["stocks"]
    due = [s for s in universe if is_due(stocks.get(s), today)]
    return sorted(due, key=lambda s: (stocks.get(s, {}).get("checked") or "", MARKET_ORDER.get(universe[s].get("market"), 3), s))


def put_page(store: dict, symbol: str, page: dict, today: date, source: str) -> None:
    """Adds one page's reading to the stock, keeping the quarters and years it already had."""
    s = store["stocks"].setdefault(symbol, {})
    if s.get("source") and s["source"] != source:
        # Consolidated and standalone figures do not mix: the other page's rows give way.
        for key in ("quarters", "annual", "balance", "cashflow"):
            s.pop(key, None)
    s["checked"] = today.isoformat()
    s["source"] = source
    s["quarters"] = merge_periods(s.get("quarters"), page["quarters"], QUARTERS_KEPT)
    s["shareholding"] = merge_periods(s.get("shareholding"), page["shareholding"], QUARTERS_KEPT)
    for key in ("annual", "balance", "cashflow"):
        s[key] = merge_periods(s.get(key), page[key], YEARS_KEPT)
    if any(v is not None for v in page["ratios"].values()):
        s["ratios"] = page["ratios"]


def put_events(store: dict, symbol: str, kind: str, events: list) -> None:
    s = store["stocks"].setdefault(symbol, {})
    s[kind] = merge_events(s.get(kind), events)


def prune(store: dict, universe: dict[str, dict], today: date, markets: set[str] | None = None) -> dict:
    """Drops stocks no longer listed and every row older than the ranges 360CR reads; returns what went. With
    `markets`, only stocks of those markets can be found delisted (a market whose list failed keeps its stocks)."""
    gone = [s for s, v in store["stocks"].items()
            if s not in universe and (markets is None or v.get("market", "nse") in markets)]
    for s in gone:
        del store["stocks"][s]
    cutoff = (today - timedelta(days=365 * EVENT_YEARS)).isoformat()
    dropped = 0
    for s in store["stocks"].values():
        for key, keep in (("quarters", QUARTERS_KEPT), ("shareholding", QUARTERS_KEPT),
                          ("annual", YEARS_KEPT), ("balance", YEARS_KEPT), ("cashflow", YEARS_KEPT)):
            rows = s.get(key) or []
            if len(rows) > keep:
                dropped += len(rows) - keep
                s[key] = rows[-keep:]
        for key in ("insider", "deals"):
            rows = s.get(key) or []
            kept = [e for e in rows if e[0] >= cutoff]
            dropped += len(rows) - len(kept)
            s[key] = kept
    return {"stocks_removed": len(gone), "rows_removed": dropped}


# --- 360CR ---------------------------------------------------------------------------------------------------------

def eps_ttm(stock: dict) -> float | None:
    """The last twelve months' EPS: four quarters, or two half-years."""
    return _ttm(stock.get("quarters") or [])


def _ttm(quarters: list[dict]) -> float | None:
    n = 2 if half_yearly(quarters) else 4
    eps = [q.get("eps") for q in quarters[-n:]]
    if len(eps) != n or any(e is None for e in eps):
        return None
    # A one-off gain in one quarter (an asset sold, a debt written off, a misprinted figure) would make the whole year
    # look far better than the business earns: a quarter over ONE_OFF times the middle one counts as the middle one.
    mid = median(eps)
    return sum(mid if mid > 0 and e > ONE_OFF * mid else e for e in eps)


def history_pe(stock: dict, price_rows: list[dict]) -> float | None:
    """The company's own usual P/E: the median, over the month-ends of its price history, of the close over the
    twelve months' EPS public by then. None with fewer than HISTORY_PE_MONTHS such months."""
    quarters = stock.get("quarters") or []
    month_end: dict[str, tuple[str, float]] = {}
    for row in price_rows:
        month_end[row["date"][:7]] = (row["date"], row["close"])
    pes = []
    for day, close in month_end.values():
        known = [q for q in quarters if _d(q["period"]) + timedelta(days=RESULTS_LAG_DAYS) <= _d(day)]
        ttm = _ttm(known)
        if ttm and ttm > 0 and close:
            pes.append(close / ttm)
    return min(100.0, max(3.0, median(pes))) if len(pes) >= HISTORY_PE_MONTHS else None


def sector_anchors(store: dict, prices: dict[str, float], sectors: dict[str, str]) -> dict[str, tuple[float, float]]:
    """Each industry's median P/E and P/B over stocks with believable earnings and positive book value; '' is the
    market's."""
    by_sector: dict[str, list[tuple[float, float]]] = {}
    for sym, s in store["stocks"].items():
        price, eps, book = prices.get(sym), eps_ttm(s), (s.get("ratios") or {}).get("book_value")
        if not price or not eps or eps <= 0 or price / eps < MIN_PE or not book or book <= 0:
            continue
        pair = (price / eps, price / book)
        by_sector.setdefault(sectors.get(sym, ""), []).append(pair)
        if sectors.get(sym):
            by_sector.setdefault("", []).append(pair)
    return {k: (median(p for p, _ in v), median(b for _, b in v)) for k, v in by_sector.items() if len(v) >= 5}


def research_input(symbol: str, stock: dict, price_rows: list[dict], anchor: tuple[float, float] | None,
                   financial: bool = False) -> ResearchInput:
    """`financial`: a lender or other financial services company, whose debt and cash flow are not judged."""
    # Without a chart (many SME and BSE-only stocks are not on Yahoo) Screener's price still values the company.
    price = price_rows[-1]["close"] if price_rows else (stock.get("ratios") or {}).get("price")
    valuation = {}
    eps, book = eps_ttm(stock), (stock.get("ratios") or {}).get("book_value")
    own_pe = history_pe(stock, price_rows)
    # Only earnings the market believes give a fair value: none for a loss (its book is worth less than profitable
    # peers' P/B says) or for earnings priced under MIN_PE times, which gave fair values hundreds of times the price.
    believable = bool(price and eps and eps > 0 and price / eps >= MIN_PE)
    if believable and own_pe:
        valuation["history_fair_value"] = eps * own_pe
    if believable and anchor:
        pe, pb = anchor
        valuation["earnings_fair_value"] = eps * min(45.0, max(8.0, pe))
        if book and book > 0:
            valuation["book_fair_value"] = book * min(10.0, max(0.5, pb))
    events = lambda key: [{"date": d, "side": side, "value": v} for d, side, v in stock.get(key) or []]
    return ResearchInput(
        symbol=symbol, price=price, price_history=price_rows,
        quarterly_financials=stock.get("quarters") or [], balance_sheet_quarters=stock.get("balance") or [],
        cashflow_quarters=stock.get("cashflow") or [], shareholding_quarters=stock.get("shareholding") or [],
        insider_transactions=events("insider"), bulk_block_deals=events("deals"), valuation=valuation,
        metadata={"source": "screener+nse+yahoo", "checked": stock.get("checked"), "history_pe": own_pe, "financial": financial},
    )


SECTION_KEYS = {"financial_quality": "fq", "growth": "gr", "cashflow": "cf", "balance_sheet": "bs", "ownership": "ow",
                "insider_deals": "ev", "valuation": "va", "price_156w": "pr", "stability": "sb", "technical_risk": "tr"}


def _r(v, n=1):
    return None if v is None else round(v, n)


def app_record(stock: dict, research: ResearchInput) -> dict | None:
    """The row the app shows for one stock; None without results to judge it on."""
    if not research.quarterly_financials:
        return None
    r = analyse_360cr(research)
    fin, own, events = r.evidence.get("financials", {}), r.evidence.get("ownership", {}), r.evidence.get("events", {})
    bal = (stock.get("balance") or [{}])[-1]
    crore = lambda v: _r((v or 0) / 1e7, 1)  # NSE reports rupees
    facts = {
        "nq": fin.get("quarters"), "pq": fin.get("profitable_quarters"),
        "rv": _r(fin.get("revenue_20q_change_pct")), "np": _r(fin.get("profit_20q_change_pct")), "nm": _r(fin.get("net_margin_latest")),
        "pm": _r(own.get("promoter_latest"), 2), "pmc": _r(own.get("promoter_change_pp"), 2),
        "fic": _r(own.get("fii_fpi_change_pp"), 2), "dic": _r(own.get("dii_change_pp"), 2),
        "oq": own.get("quarters") or None,  # the quarters those changes span
        # Insider trades only where NSE's filings were read: a BSE-only company's ₹0 would mean "not looked".
        **({"ib": crore(events.get("insider_buy")), "is": crore(events.get("insider_sell"))}
           if stock.get("insider_checked") or stock.get("insider") else {}),
        "bb": crore(events.get("bulk_block_buy")), "bsl": crore(events.get("bulk_block_sell")),
        "pe": _r(research.price / eps_ttm(stock)) if research.price and eps_ttm(stock) and eps_ttm(stock) > 0 else None,
        "fv": r.fair_value.get("base"), "up": r.fair_value.get("upside_pct"),
        "hpe": _r(research.metadata.get("history_pe")),
        "fh": r.fair_value.get("history"), "uh": r.fair_value.get("history_upside_pct"),
        "pos": r.risk.get("position_156w_pct"), "vol": r.risk.get("annualized_volatility_pct"),
        "de": _r(bal.get("total_debt") / bal["equity"], 2) if bal.get("equity") and bal.get("total_debt") is not None else None,
        "hy": 1 if half_yearly(stock.get("quarters") or []) else None,  # nq and pq count half-years
    }
    return {
        "s": r.score, "st": r.state, "c": r.confidence,
        "q": (stock.get("quarters") or [{}])[-1].get("period"), "d": stock.get("checked"),
        "sec": {SECTION_KEYS[k]: _r(v, 0) for k, v in r.sections.items() if k in SECTION_KEYS},
        "f": {k: v for k, v in facts.items() if v is not None},
    }


# --- history for the app ---------------------------------------------------------------------------------------------

def _ym(period: str) -> str:
    """"2026-06-30" -> "2606", the app's short period."""
    return period[2:4] + period[5:7]


def _h(v):
    return None if v is None else round(v) if abs(v) >= 100 else round(v, 2)


def history_record(stock: dict) -> dict | None:
    """Everything the store keeps on a stock, compact, for the app's history tables: quarterly results
    [period, sales, profit, EPS], quarterly shareholding [period, promoter, FII, DII, public] and yearly figures
    [period, sales, profit, operating cash flow, free cash flow, debt]; None when there is nothing."""
    quarters = [[_ym(q["period"]), _h(q.get("revenue")), _h(q.get("net_profit")), _h(q.get("eps"))]
                for q in stock.get("quarters") or []]
    holders = [[_ym(h["period"]), h.get("promoter"), h.get("fii_fpi"), h.get("dii"), h.get("public")]
               for h in stock.get("shareholding") or []]
    years: dict[str, list] = {}
    for key, fields in (("annual", ("revenue", "net_profit")), ("cashflow", ("operating_cash_flow", "free_cash_flow")),
                        ("balance", ("total_debt",))):
        offset = {"annual": 1, "cashflow": 3, "balance": 5}[key]
        for row in stock.get(key) or []:
            y = years.setdefault(row["period"], [_ym(row["period"]), None, None, None, None, None])
            for i, f in enumerate(fields):
                y[offset + i] = _h(row.get(f))
    out = {"q": quarters, "s": holders, "y": [years[k] for k in sorted(years)]}
    return out if any(out.values()) else None


def monthly_closes(price_rows: list[dict]) -> dict | None:
    """Each month's last close as {"m": first month "2310", "c": [one close a month, None for a month without
    trading]} for the app's 3-year chart."""
    month_end: dict[str, float] = {}
    for row in price_rows:
        month_end[row["date"][:7]] = row["close"]
    if len(month_end) < 2:
        return None
    first, last = min(month_end), max(month_end)
    y, m, closes = int(first[:4]), int(first[5:7]), []
    while f"{y:04d}-{m:02d}" <= last:  # a month without trading (a suspension) stays as a gap
        c = month_end.get(f"{y:04d}-{m:02d}")
        closes.append(None if c is None else round(c, 2))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return {"m": first[2:4] + first[5:7], "c": closes}


def history_shard(symbol: str) -> int:
    """Which history file holds a stock: CRC-32 of its symbol, as the app computes it (Cr360History.shard)."""
    return zlib.crc32(symbol.encode("utf-8")) % HISTORY_SHARDS
