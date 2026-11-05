"""The store behind बड़े पैसे की चाल: every session's close, previous close, volume and delivery % of every EQ and SM
share, as arrays lined up with the session dates, and the bulk and block deals by symbol.

{"v": 1, "dates": [iso...], "stocks": {symbol: {"s": series, "c": [...], "p": [...], "v": [...], "d": [...]}},
 "deals": {symbol: [[date, side, rupees, shares, client, kind], ...]}}
"""
from __future__ import annotations

import bisect

from bigmoney.rules import AVG_SESSIONS, Day

FIELDS = ("c", "p", "v", "d")  # close, previous close, volume, delivery %
KEEP = 270  # a year of sessions for the track record, and 20 before it for the averages


def empty_store() -> dict:
    return {"v": 1, "dates": [], "stocks": {}, "deals": {}}


def add_session(store: dict, day: str, rows: dict) -> bool:
    """Puts one session's rows (`nse_files.Row` by symbol) in its place among the dates; False when it is in already.
    Shares missing that day read None, and a share new to the store reads None for the days before it."""
    dates = store["dates"]
    if day in dates:
        return False
    at = bisect.bisect_left(dates, day)
    dates.insert(at, day)
    stocks = store["stocks"]
    for symbol, values in stocks.items():
        r = rows.get(symbol)
        for field, value in zip(FIELDS, (r.close, r.prev_close, r.volume, r.deliv_pct) if r else (None,) * 4):
            values[field].insert(at, value)
    for symbol, r in rows.items():
        if symbol not in stocks:
            fresh = {field: [None] * len(dates) for field in FIELDS}
            for field, value in zip(FIELDS, (r.close, r.prev_close, r.volume, r.deliv_pct)):
                fresh[field][at] = value
            stocks[symbol] = {"s": r.series, **fresh}
        elif at == len(dates) - 1:
            stocks[symbol]["s"] = r.series
    return True


def prune(store: dict, keep: int = KEEP) -> None:
    """Keeps the latest `keep` sessions; a share with nothing left, and deals before the first session, go."""
    cut = len(store["dates"]) - keep
    if cut > 0:
        store["dates"] = store["dates"][cut:]
        for values in store["stocks"].values():
            for field in FIELDS:
                values[field] = values[field][cut:]
    store["stocks"] = {s: v for s, v in store["stocks"].items() if any(x is not None for x in v["c"])}
    first = store["dates"][0] if store["dates"] else ""
    store["deals"] = {s: kept for s, rows in store["deals"].items() if (kept := [r for r in rows if r[0] >= first])}


def add_deals(store: dict, by_symbol: dict[str, list]) -> int:
    """Adds deal rows not already kept; returns how many were new."""
    added = 0
    for symbol, rows in by_symbol.items():
        kept = store["deals"].setdefault(symbol, [])
        seen = {tuple(r) for r in kept}
        for r in rows:
            if tuple(r) not in seen:
                kept.append(list(r))
                seen.add(tuple(r))
                added += 1
        kept.sort(key=lambda r: r[0])
    return added


def history(store: dict, symbol: str, upto: int) -> list[Day]:
    """The share's sessions from 20 before `upto` (an index into the dates) to `upto` itself."""
    values = store["stocks"][symbol]
    start = max(0, upto - AVG_SESSIONS)
    return [Day(store["dates"][i], values["c"][i], values["p"][i], values["v"][i], values["d"][i])
            for i in range(start, upto + 1)]
