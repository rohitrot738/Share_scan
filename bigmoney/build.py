"""The evening job of बड़े पैसे की चाल: tops up the store with every NSE session it lacks (a year of them the first
time), adds the day's bulk and block deals, works out each signal's track record and writes the app's file.

Prints PUBLISH when a new session went in (or --rebuild asked for the files again) and both files were written,
NOTHING NEW when there was nothing to add (a holiday, or NSE's file not out yet); exits 1, writing nothing, when NSE's
file is too thin to be whole.

    python -m bigmoney.build --store big-money-store.json.gz --cr360-store cr360-store.json.gz --out big-money.json.gz
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import sys
import time
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from bigmoney import rules
from bigmoney.nse_files import fetch_delivery, parse_delivery
from bigmoney.record import signals_at, track_record
from bigmoney.store import add_deals, add_session, empty_store, history, prune

MIN_ROWS = 1500  # NSE's file holds about 3,100 EQ and SM shares; far fewer is a broken file
FIRST_DAYS = 380  # the first run reads a year of sessions, and the 20 before them
SERIES_SESSIONS = 20  # sessions of delivery and volume the app draws
PAUSE = 0.3  # seconds between two of NSE's files


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def read_json_gz(path: str) -> dict | None:
    if not path or not os.path.exists(path):
        return None
    with gzip.open(path, "rt") as f:
        return json.load(f)


def write_json_gz(path: str, data) -> None:
    temp = path + ".tmp"
    with gzip.open(temp, "wt", compresslevel=9) as f:
        json.dump(data, f, separators=(",", ":"), ensure_ascii=False)
    os.replace(temp, path)


def _r(v, digits=2):
    return None if v is None else round(v, digits)


def app_file(store: dict, insider: dict[str, list], record: dict | None = None) -> dict:
    """The app's file: the latest session's date, the dates of its last 20 sessions, each signal's track record, and
    every share traded that session with its flags, score and parts, the figures behind them and its 20 sessions."""
    i = len(store["dates"]) - 1
    start = max(0, i - SERIES_SESSIONS + 1)
    stocks = {}
    for symbol, v in store["stocks"].items():
        if v["c"][i] is None:
            continue
        days = history(store, symbol, i)
        avg_d, avg_v, recent = rules.averages(days)
        found, total, parts = signals_at(store, insider, symbol, i)
        sessions = store["dates"][max(0, i - rules.DEAL_SESSIONS + 1):i + 1]
        deal = rules.deal_net(store["deals"].get(symbol, []), sessions)
        promoter = rules.promoter_net(insider.get(symbol, []), date.fromisoformat(store["dates"][i]))
        stocks[symbol] = {
            "s": total, "p": parts, "f": found,
            "d": _r(v["d"][i]), "da": _r(avg_d), "d5": _r(recent),
            "v": v["v"][i], "va": _r(avg_v, 0), "c": _r(rules.change(days[-1])),
            "dq": [_r(x) for x in v["d"][start:i + 1]], "vq": v["v"][start:i + 1],
            "dn": [deal.shares, round(deal.rupees, 2), deal.days, deal.streak] if deal else None,
            "cc": deal.circular if deal else [],
            "pn": [round(promoter.rupees, 2), promoter.buys, promoter.sells] if promoter else None,
        }
    return {"v": 1, "date": store["dates"][i], "dates": store["dates"][start:i + 1],
            "record": record if record is not None else track_record(store, insider), "stocks": stocks}


def _weekdays(start: date, end: date):
    d = start
    while d <= end:
        if d.weekday() < 5:
            yield d
        d += timedelta(days=1)


def main(argv=None, fetch=fetch_delivery, deals=None, pause: float = PAUSE) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--store", default="big-money-store.json.gz")
    ap.add_argument("--cr360-store", default="cr360-store.json.gz")
    ap.add_argument("--out", default="big-money.json.gz")
    ap.add_argument("--today", default=None)
    ap.add_argument("--rebuild", action="store_true",
                    help="write the app's file from the store even with no new session (after a change to the rules)")
    args = ap.parse_args(argv)
    today = date.fromisoformat(args.today) if args.today else datetime.now(ZoneInfo("Asia/Kolkata")).date()

    store = read_json_gz(args.store) or empty_store()
    cr360 = read_json_gz(args.cr360_store) or {"stocks": {}}
    insider = {s: v["insider"] for s, v in cr360["stocks"].items() if v.get("insider")}
    first = not store["dates"]
    start = today - timedelta(days=FIRST_DAYS) if first else max(
        date.fromisoformat(store["dates"][-1]) + timedelta(days=1), today - timedelta(days=FIRST_DAYS))

    added, thin = [], []
    for day in _weekdays(start, today):
        text = fetch(day)
        if pause:
            time.sleep(pause)
        if text is None:
            continue
        session, rows = parse_delivery(text)
        if session is None or len(rows) < MIN_ROWS:
            log(f"{day}: only {len(rows)} shares in NSE's file, not taken")
            thin.append(day)
            continue
        if add_session(store, session, rows):
            added.append(session)
    log(f"sessions added: {len(added)} ({added[0] if added else '-'} .. {added[-1] if added else '-'})")
    if not added and not (args.rebuild and store["dates"]):
        if thin:
            log("NSE's newest file is not whole; nothing written")
            return 1
        print("NOTHING NEW", flush=True)
        return 0

    if first:
        log("deals from the R/S store:", add_deals(store, {s: v["deals"] for s, v in cr360["stocks"].items()
                                                           if v.get("deals")}))
    log("deals from today's files:", add_deals(store, (deals or _archive_deals)()))
    prune(store)
    started = time.time()
    record = track_record(store, insider)
    log(f"track record in {time.time() - started:.0f}s:", {k: v["n"] for k, v in record.items()})
    app = app_file(store, insider, record)
    write_json_gz(args.store, store)
    write_json_gz(args.out, app)
    log(f"app file: {app['date']}, {len(app['stocks'])} shares, "
        f"{sum('acc' in s['f'] for s in app['stocks'].values())} accumulating, "
        f"{sum('churn' in s['f'] for s in app['stocks'].values())} churning")
    print("PUBLISH", flush=True)
    return 0


def _archive_deals() -> dict[str, list]:
    from cr360.build_app_cache import archive_deals
    return archive_deals()


if __name__ == "__main__":
    sys.exit(main())
