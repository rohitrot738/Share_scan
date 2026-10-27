"""Temporary review helper: the app's published R/S file with a few stocks valued again by the valuation framework
from fresh Screener pages and Yahoo prices, for the emulator screenshots. Removed before merging."""
import gzip
import json
import sys
from datetime import datetime

from cr360 import app_store, build_app_cache as b, valuation as v

SYMBOLS = sys.argv[1].split(",")
today = datetime.now(b.IST).date()
app = json.load(gzip.open("cr360.json.gz", "rt"))
store = json.load(gzip.open("cr360-store.json.gz", "rt"))
universe, _ = b.load_universe(today)
sectors = b.load_sectors(universe)
last = {s: (st.get("ratios") or {}).get("price") for s, st in store["stocks"].items()}
anchors = app_store.sector_anchors(store, last, sectors)
ranks = app_store.size_ranks(store, last)
screener = b.Screener()
due = app_store.latest_due_quarter(today)
for sym in SYMBOLS:
    got = screener.page(sym, due)
    if not got:
        print(sym, "no page", flush=True)
        continue
    app_store.put_page(store, sym, got[0], today, got[1])
    stock = store["stocks"][sym]
    prices = b.yahoo_prices(b.yahoo_tickers(sym, universe[sym]))
    anchor = anchors.get(sectors.get(sym, "")) or anchors.get("")
    research = app_store.research_input(sym, stock, prices, anchor, financial=sectors.get(sym) == b.FINANCIAL_SECTOR,
                                        sector=sectors.get(sym, ""), mos=v.size_mos(ranks.get(sym)), today=today)
    rec = app_store.app_record(stock, research)
    if rec:
        app["stocks"][sym] = rec
        print(sym, rec["st"], rec["s"], rec["f"], flush=True)
with gzip.open("ui-cr360.json.gz", "wt", encoding="utf-8") as f:
    json.dump(app, f, separators=(",", ":"))
