"""Temporary probe for the "बड़े पैसे की चाल" feature: what NSE's public files and filings look like from GitHub's
servers. It only reads and prints; nothing is stored or published. Removed before merging."""
from __future__ import annotations

import collections
import csv
import gzip
import io
import json
import os
import sys
import time
from datetime import date, timedelta

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from cr360.build_app_cache import BROWSER_UA, http_get  # noqa: E402
from cr360.nse_public_adapter import NSEPublicAdapter  # noqa: E402

HOSTS = ("https://nsearchives.nseindia.com", "https://archives.nseindia.com")
SEC = "/products/content/sec_bhavdata_full_{}.csv"


def head(title):
    print("\n=====", title, "=====", flush=True)


def weekday_back(days: int) -> date:
    d = date.today() - timedelta(days=days)
    while d.weekday() >= 5:
        d -= timedelta(days=1)
    return d


def sec_file(d: date, host: str = HOSTS[0]):
    t = time.time()
    status, text, headers = http_get(host + SEC.format(d.strftime("%d%m%Y")), "text/csv", BROWSER_UA, 60,
                                     "https://www.nseindia.com/")
    return status, text, headers, time.time() - t


def describe(text: str):
    rows = list(csv.DictReader(io.StringIO(text)))
    rows = [{k.strip(): (v or "").strip() for k, v in r.items() if k} for r in rows]
    print("columns:", list(rows[0].keys()) if rows else None)
    print("rows:", len(rows))
    series = collections.Counter(r.get("SERIES") for r in rows)
    print("series:", series.most_common(25))
    for s in ("EQ", "BE", "BZ", "SM", "ST", "SZ"):
        sample = [r for r in rows if r.get("SERIES") == s][:400]
        if not sample:
            continue
        per = [r.get("DELIV_PER") for r in sample]
        dash = sum(1 for p in per if p in ("-", "", None))
        nums = [float(p) for p in per if p not in ("-", "", None)]
        full = sum(1 for n in nums if n >= 99.99)
        print(f"  {s}: {len(sample)} rows, DELIV_PER '-' {dash}, ==100% {full}, "
              f"min {min(nums) if nums else None} max {max(nums) if nums else None}")
    for sym in ("RELIANCE", "TCS", "SUZLON", "YESBANK"):
        r = next((r for r in rows if r.get("SYMBOL") == sym and r.get("SERIES") == "EQ"), None)
        print(" ", sym, r)
    one = next((r for r in rows if r.get("SERIES") in ("SM", "ST")), None)
    print("  an SME row:", one)
    be = next((r for r in rows if r.get("SERIES") == "BE"), None)
    print("  a BE row:", be)


def main() -> int:
    head("1. Latest delivery files (both hosts)")
    latest = None
    for back in range(0, 8):
        d = date.today() - timedelta(days=back)
        if d.weekday() >= 5:
            continue
        for host in HOSTS:
            status, text, headers, took = sec_file(d, host)
            print(d, host, status, len(text), f"{took:.1f}s", "Last-Modified:", headers.get("Last-Modified"),
                  flush=True)
            if status == 200 and latest is None and text.startswith("SYMBOL"):
                latest = (d, text)
            time.sleep(0.5)
    if latest:
        head(f"2. Shape of {latest[0]}")
        describe(latest[1])

    head("3. How far back the files go")
    for days in (30, 90, 180, 250, 365, 400, 550, 730, 1100):
        d = weekday_back(days)
        status, text, headers, took = sec_file(d)
        print(f"{days:>5} days back {d}: {status} {len(text)} bytes {took:.1f}s", flush=True)
        time.sleep(0.5)

    head("4. Twenty files in a row, to time a year's back-load")
    t = time.time()
    got = 0
    for days in range(200, 228):
        d = date.today() - timedelta(days=days)
        if d.weekday() >= 5:
            continue
        status, text, _, _ = sec_file(d)
        got += status == 200 and text.startswith("SYMBOL")
        time.sleep(0.3)
    print(f"{got} files in {time.time() - t:.1f}s", flush=True)

    head("5. Daily bulk and block deal files")
    for kind in ("bulk", "block"):
        status, text, headers = http_get(f"https://archives.nseindia.com/content/equities/{kind}.csv", "text/csv",
                                         BROWSER_UA)
        lines = text.splitlines()
        print(kind, status, len(lines), "lines; header:", lines[:1], "Last-Modified:", headers.get("Last-Modified"))
        print("  first rows:", lines[1:3])

    nse = NSEPublicAdapter()
    end = date.today()
    start = end - timedelta(days=7)
    span = {"from_date": start.strftime("%d-%m-%Y"), "to_date": end.strftime("%d-%m-%Y")}
    for title, path, params in (
        ("6. Insider (PIT) filings of the whole market, last 7 days", "/api/corporates-pit",
         {"index": "equities", **span}),
        ("6b. SME insider filings, last 7 days", "/api/corporates-pit", {"index": "sme", **span}),
        ("7. SAST Reg 29 filings, last 7 days", "/api/corporate-sast-reg29", {"index": "equities", **span}),
        ("8. Bulk deals of the whole market, last 7 days", "/api/historical/bulk-deals",
         {"from": span["from_date"], "to": span["to_date"]}),
    ):
        head(title)
        try:
            rows = nse._rows(nse._get(path, params))
            print("rows:", len(rows))
            if rows:
                print("keys:", list(rows[0].keys()))
                print("first:", json.dumps(rows[0])[:900])
                for key in ("personCategory", "acqMode", "tdpTransactionType", "acquirerName", "buySell"):
                    if key in rows[0]:
                        print(f"  {key}:", collections.Counter(str(r.get(key)) for r in rows).most_common(12))
        except Exception as e:
            print("failed:", e)
        time.sleep(1.5)

    head("9. Insider and deal rows in the R/S store")
    path = "cr360-store.json.gz"
    if os.path.exists(path):
        store = json.load(gzip.open(path, "rt"))
        stocks = store["stocks"]
        year = (date.today() - timedelta(days=365)).isoformat()
        cats, modes = collections.Counter(), collections.Counter()
        deals_year = insider_year = with_deals = 0
        newest_insider = newest_deal = ""
        for v in stocks.values():
            d = [e for e in v.get("deals") or [] if e[0] >= year]
            i = [e for e in v.get("insider") or [] if e[0] >= year]
            deals_year += len(d)
            insider_year += len(i)
            with_deals += bool(d)
            for e in i:
                if len(e) > 6:
                    cats[e[5]] += 1
                    modes[e[6]] += 1
            newest_insider = max([newest_insider] + [e[0] for e in v.get("insider") or []])
            newest_deal = max([newest_deal] + [e[0] for e in v.get("deals") or []])
        print("stocks:", len(stocks), "| deals in a year:", deals_year, "in", with_deals, "stocks | insider rows:",
              insider_year)
        print("newest insider:", newest_insider, "| newest deal:", newest_deal)
        print("categories:", cats.most_common(15))
        print("modes:", modes.most_common(15))
        sample = next((v for v in stocks.values() if v.get("deals")), None)
        print("a deal row:", (sample or {}).get("deals", [None])[-1])
    else:
        print("no store downloaded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
