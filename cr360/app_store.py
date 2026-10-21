"""The 360CR cache behind the app: every NSE stock's results, shareholding and deals, gathered once and then only
topped up. Past quarters do not change, so a stock's page is read again only when its next results are due; what
falls outside the ranges 360CR looks at is dropped, so the cache stays the same size year after year.

The store is plain JSON (see `empty`); `app_record` turns one stock's 360CR into the compact row the app reads."""
from __future__ import annotations

from datetime import date, timedelta
from statistics import median

from .engine import analyse_360cr
from .models import ResearchInput

VERSION = 1
QUARTERS_KEPT = 20          # quarterly results and shareholding: 5 years
YEARS_KEPT = 5              # yearly results, balance sheet and cash flow
EVENT_YEARS = 5             # insider trades and bulk/block deals
CHECK_EVERY_DAYS = 7        # a stock waiting for its next results is looked at once a week
REFRESH_DAYS = 100          # and every page is read again at least this often (restated numbers, book value)
RESULTS_GRACE_DAYS = 20     # results are not expected sooner than this after a quarter ends


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


def is_due(stock: dict | None, today: date) -> bool:
    """Whether the stock's page should be read: never read, a quarter's results may be out, or a long time passed."""
    if not stock or not stock.get("checked"):
        return True
    age = (today - _d(stock["checked"])).days
    if age >= REFRESH_DAYS:
        return True
    if age < CHECK_EVERY_DAYS:
        return False
    quarters = stock.get("quarters") or []
    return not quarters or _d(quarters[-1]["period"]) < latest_due_quarter(today)


def due_order(store: dict, universe: dict[str, dict], today: date) -> list[str]:
    """Stocks to read, those never read first, then the longest unread."""
    stocks = store["stocks"]
    due = [s for s in universe if is_due(stocks.get(s), today)]
    return sorted(due, key=lambda s: (stocks.get(s, {}).get("checked") or "", s))


def put_page(store: dict, symbol: str, page: dict, today: date, source: str) -> None:
    """Adds one page's reading to the stock, keeping the quarters and years it already had."""
    s = store["stocks"].setdefault(symbol, {})
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


def prune(store: dict, universe: dict[str, dict], today: date) -> dict:
    """Drops stocks no longer listed and every row older than the ranges 360CR reads; returns what went."""
    gone = [s for s in store["stocks"] if s not in universe]
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
    eps = [q.get("eps") for q in (stock.get("quarters") or [])[-4:]]
    return sum(eps) if len(eps) == 4 and all(e is not None for e in eps) else None


def sector_anchors(store: dict, prices: dict[str, float], sectors: dict[str, str]) -> dict[str, tuple[float, float]]:
    """Each industry's median P/E and P/B over stocks with positive earnings and book value; '' is the market's."""
    by_sector: dict[str, list[tuple[float, float]]] = {}
    for sym, s in store["stocks"].items():
        price, eps, book = prices.get(sym), eps_ttm(s), (s.get("ratios") or {}).get("book_value")
        if not price or not eps or eps <= 0 or not book or book <= 0:
            continue
        pair = (price / eps, price / book)
        by_sector.setdefault(sectors.get(sym, ""), []).append(pair)
        if sectors.get(sym):
            by_sector.setdefault("", []).append(pair)
    return {k: (median(p for p, _ in v), median(b for _, b in v)) for k, v in by_sector.items() if len(v) >= 5}


def research_input(symbol: str, stock: dict, price_rows: list[dict], anchor: tuple[float, float] | None) -> ResearchInput:
    price = price_rows[-1]["close"] if price_rows else None
    valuation = {}
    eps, book = eps_ttm(stock), (stock.get("ratios") or {}).get("book_value")
    if anchor:
        pe, pb = anchor
        if eps and eps > 0:
            valuation["earnings_fair_value"] = eps * min(45.0, max(8.0, pe))
        if book and book > 0:
            valuation["book_fair_value"] = book * min(10.0, max(0.5, pb))
    events = lambda key: [{"date": d, "side": side, "value": v} for d, side, v in stock.get(key) or []]
    return ResearchInput(
        symbol=symbol, price=price, price_history=price_rows,
        quarterly_financials=stock.get("quarters") or [], balance_sheet_quarters=stock.get("balance") or [],
        cashflow_quarters=stock.get("cashflow") or [], shareholding_quarters=stock.get("shareholding") or [],
        insider_transactions=events("insider"), bulk_block_deals=events("deals"), valuation=valuation,
        metadata={"source": "screener+nse+yahoo", "checked": stock.get("checked")},
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
        "ib": crore(events.get("insider_buy")), "is": crore(events.get("insider_sell")),
        "bb": crore(events.get("bulk_block_buy")), "bsl": crore(events.get("bulk_block_sell")),
        "pe": _r(research.price / eps_ttm(stock)) if research.price and eps_ttm(stock) and eps_ttm(stock) > 0 else None,
        "fv": r.fair_value.get("base"), "up": r.fair_value.get("upside_pct"),
        "pos": r.risk.get("position_156w_pct"), "vol": r.risk.get("annualized_volatility_pct"),
        "de": _r(bal.get("total_debt") / bal["equity"], 2) if bal.get("equity") and bal.get("total_debt") is not None else None,
    }
    return {
        "s": r.score, "st": r.state, "c": r.confidence,
        "q": (stock.get("quarters") or [{}])[-1].get("period"), "d": stock.get("checked"),
        "sec": {SECTION_KEYS[k]: _r(v, 0) for k, v in r.sections.items() if k in SECTION_KEYS},
        "f": {k: v for k, v in facts.items() if v is not None},
    }
