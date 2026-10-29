"""Builds the app's 360CR cache (run daily by .github/workflows/cr360-cache.yml).

    python -m cr360.build_app_cache --store cr360-store.json.gz --out cr360.json.gz --minutes 280

Reads the previous store when there is one, drops what aged out of 360CR's ranges, reads the Screener page of every
stock whose new results may be out (all of them the first time, paced so Screener does not refuse), adds NSE's
insider trades and bulk/block deals, prices every stock from Yahoo, runs 360CR and writes the store and the app file.

The stocks are the app's: NSE's main board, NSE SME (Emerge) and the BSE shares that do not trade on NSE, chosen by
the same rules as NseScanner, so every card the app can show has its row under the same symbol.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import io
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone

from . import app_store, screener_page, valuation
from .nse_public_adapter import NSEPublicAdapter

IST = timezone(timedelta(hours=5, minutes=30))
APP_UA = "ShareScan (Android)"
BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
UNIVERSE_URL = "https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv"
SME_URLS = ("https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv",
            "https://archives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv")
BSE_BHAVCOPY = "https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_{}_F_0000.CSV"
SYMBOL = re.compile(r"[A-Z0-9&-]+")
BSE_NON_EQUITY_GROUPS = {"E", "F", "G", "GS", "IF", "IT", "DT"}  # ETFs, debt and government securities
ETF_NAME = re.compile(r"\bETF\b|\bBEES\b", re.IGNORECASE)
SECTORS_FILE = os.path.join(os.path.dirname(__file__), "..", "data", "sectors.json")
FINANCIAL_SECTOR = "Financial Services"  # banks, NBFCs, insurers, brokers: debt and cash flow not judged (engine.py)
SCREENER_GAP = float(os.environ.get("CR360_SCREENER_GAP", "6"))
PRICE_RESERVE_MIN = 30  # minutes kept for prices, 360CR and writing the files
INSIDER_EVERY_DAYS = 7  # a stock's insider trades are topped up weekly
INSIDER_MINUTES = 60    # the most of a run the insider top-up may take, before the pages
INSIDER_GAP = 1.0       # seconds between two stocks' insider requests to NSE
INSIDER_FAILURES = 5    # NSE refusing this many stocks in a row ends the top-up for the run
OLD_QUARTER_DAYS = 200  # a page this far behind the due quarter is checked against the other one
INSIDER_INDEX = {"nse": "equities", "sme": "sme"}  # NSE's insider filings; BSE-only companies file with BSE
BSE_ROUNDS = 3          # BSE's site answers 404 for every file for minutes at a time, so the week is asked for again
BSE_ROUND_GAP = 120     # seconds between those rounds
NSE_INDEX = {"equities": "nse", "sme": "sme"}  # NSE's index of each market's filings
AUDITOR_CHUNK_DAYS = 7  # market-wide announcements asked for a week at a time (about 2 MB)
AUDITOR_MINUTES = 15    # the most of a run reading announcements may take (the first run reads a year)
RESIGNED = re.compile(r"\bresign", re.IGNORECASE)
OTHER_AUDITOR = re.compile(r"\b(internal|cost|secretarial|tax|branch)\s+auditor", re.IGNORECASE)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def http_get(url: str, accept: str = "text/html", ua: str = APP_UA, timeout: int = 25,
             referer: str | None = None) -> tuple[int, str, dict]:
    headers = {"User-Agent": ua, "Accept": accept, "Accept-Language": "en-IN,en;q=0.9"}
    if referer:
        headers["Referer"] = referer
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace"), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, "", dict(e.headers or {})
    except Exception as e:  # network trouble reads as a failed request
        return -1, repr(e), {}


def iso_date(v) -> str | None:
    """NSE writes dates several ways ('01-Oct-2026', '01-10-2026', '2026-10-01 10:00'); all become '2026-10-01'."""
    s = str(v or "").strip()
    for c in dict.fromkeys((s, s.split(" ")[0], s[:10], s[:11])):
        for fmt in ("%d-%b-%Y", "%d-%m-%Y", "%Y-%m-%d", "%d %b %Y", "%d-%B-%Y", "%d/%m/%Y"):
            try:
                return datetime.strptime(c, fmt).date().isoformat()
            except ValueError:
                continue
    return None


# --- sources -------------------------------------------------------------------------------------------------------

def _csv_rows(text: str):
    for row in csv.DictReader(io.StringIO(text.lstrip("\ufeff"))):
        yield {k.strip().upper().replace("_", " "): (v or "").strip() for k, v in row.items() if k}


def nse_listings(text: str, market: str) -> dict[str, dict]:
    """EQUITY_L.csv or SME_EQUITY_L.csv (which writes NAME_OF_COMPANY for NAME OF COMPANY)."""
    out = {}
    for row in _csv_rows(text):
        sym = row.get("SYMBOL", "")
        if SYMBOL.fullmatch(sym) and sym not in out:
            out[sym] = {"name": row.get("NAME OF COMPANY", ""), "isin": row.get("ISIN NUMBER", ""), "market": market, "code": ""}
    return out


def bse_listings(text: str) -> dict[str, dict]:
    """Company shares from a BSE bhavcopy (UDiFF): ETFs, debt and government securities left out by group and name."""
    out = {}
    for row in _csv_rows(text):
        sym = row.get("TCKRSYMB", "").upper()
        if not SYMBOL.fullmatch(sym) or sym in out or row.get("SCTYSRS", "").upper() in BSE_NON_EQUITY_GROUPS:
            continue
        if ETF_NAME.search(row.get("FININSTRMNM", "")) or not row.get("FININSTRMID"):
            continue
        out[sym] = {"name": row.get("FININSTRMNM", ""), "isin": row.get("ISIN", ""), "market": "bse", "code": row["FININSTRMID"]}
    return out


def combine(main: dict, sme: dict, bse: dict) -> dict[str, dict]:
    """NSE's main board, then SME, then the BSE shares not on NSE by ISIN or by symbol (as the app's scan does)."""
    nse = {**sme, **main}
    isins = {u["isin"] for u in nse.values()} - {""}
    return {**nse, **{s: u for s, u in bse.items() if s not in nse and u["isin"] not in isins}}


def load_universe(today: date) -> tuple[dict[str, dict], set[str]]:
    """Every stock the app can show and the markets whose lists loaded. NSE's list is required; SME and BSE are
    added when their files answer (a market that did not keeps its stocks in the store until it does)."""
    status, text, _ = http_get(UNIVERSE_URL, "text/csv", BROWSER_UA)
    main = nse_listings(text, "nse") if status == 200 else {}
    if len(main) < 1000:
        raise SystemExit(f"NSE equity list unavailable: {status}, {len(main)} rows")
    sme = {}
    for url in SME_URLS:
        status, text, _ = http_get(url, "text/csv", BROWSER_UA)
        sme = nse_listings(text, "sme") if status == 200 else {}
        if len(sme) >= 50:
            break
    bse, tried = latest_bse(today)
    markets = {"nse"} | ({"sme"} if len(sme) >= 50 else set()) | ({"bse"} if len(bse) >= 500 else set())
    log(f"lists: nse {len(main)}, sme {len(sme)}, bse {len(bse)}" + ("" if "bse" in markets else f" (BSE files {' '.join(tried)})"))
    return combine(main, sme if "sme" in markets else {}, bse if "bse" in markets else {}), markets


def latest_bse(today: date, get=None, wait=time.sleep) -> tuple[dict[str, dict], list[str]]:
    """The latest trading day's BSE shares from the last week's bhavcopies (on holidays BSE answers with a web page),
    asking again after a pause when none loads, as BSE's site at times answers 404 for every file. Also returns each
    day tried as "MMDD:status/rows", the rounds apart by "|", for the log."""
    get = get or http_get
    tried: list[str] = []
    for round_ in range(BSE_ROUNDS):
        if round_:
            tried.append("|")
            wait(BSE_ROUND_GAP)
        for back in range(8):
            day = (today - timedelta(days=back)).strftime("%Y%m%d")
            status, text, _ = get(BSE_BHAVCOPY.format(day), "text/csv", BROWSER_UA, 60, "https://www.bseindia.com/")
            bse = bse_listings(text) if status == 200 else {}
            tried.append(f"{day[4:]}:{status}/{len(bse)}")
            if len(bse) >= 500:
                return bse, tried
    return {}, tried


def load_sectors(universe: dict[str, dict]) -> dict[str, str]:
    """Each stock's industry from data/sectors.json, whose maps go by market and ISIN."""
    try:
        data = json.load(open(SECTORS_FILE))
    except OSError:
        return {}
    names = data.get("sectors", [])
    out = {}
    for s, u in universe.items():
        i = (data.get(u.get("market", "nse")) or {}).get(u["isin"])
        if i is not None:
            out[s] = names[i]
    return out


def screener_slug(symbol: str, stock: dict) -> str:
    """Screener knows NSE and SME companies by symbol and BSE-only ones by their BSE code."""
    return stock["code"] if stock.get("market") == "bse" and stock.get("code") else symbol


def yahoo_tickers(symbol: str, stock: dict) -> list[str]:
    """Yahoo tickers to try, best first: NSE and SME stocks as SYMBOL.NS, BSE ones by symbol and then by code."""
    if stock.get("market") == "bse":
        return [f"{symbol}.BO"] + ([f"{stock['code']}.BO"] if stock.get("code") else [])
    return [f"{symbol}.NS"]


class Screener:
    """One page every `gap` seconds; a refusal (429) waits and slows down, five in a row end the reading for today."""

    def __init__(self, gap: float = SCREENER_GAP):
        self.gap, self.last, self.refusals = gap, 0.0, 0

    def _get(self, path: str) -> tuple[int, str]:
        wait = self.gap - (time.time() - self.last)
        if wait > 0:
            time.sleep(wait)
        status, html, headers = http_get("https://www.screener.in/company/" + path)
        self.last = time.time()
        if status == 429:
            self.refusals += 1
            pause = min(600, int(headers.get("Retry-After") or 0) or 60 * self.refusals)
            log(f"  screener refused ({self.refusals}), waiting {pause}s")
            time.sleep(pause)
            self.gap = min(20.0, self.gap + 1)
        elif status == 200:
            self.refusals = 0
        return status, html

    @property
    def blocked(self) -> bool:
        return self.refusals >= 5

    def page(self, symbol: str, due_quarter: date) -> tuple[dict, str] | None:
        """The consolidated page, or the standalone one when the consolidated has no results or stopped two quarters
        or more before `due_quarter` (some groups stop filing consolidated results); the newer of the two wins."""
        q = urllib.parse.quote(symbol)
        best = None
        for source, path in (("consolidated", f"{q}/consolidated/"), ("standalone", f"{q}/")):
            status, html = self._get(path)
            if status == 429:
                status, html = self._get(path)
            if status != 200:
                continue
            page = screener_page.parse(html)
            if not page["quarters"]:
                continue
            latest = page["quarters"][-1]["period"]
            if best is None or latest > best[0]["quarters"][-1]["period"]:
                best = (page, source)
            if date.fromisoformat(latest) >= due_quarter - timedelta(days=OLD_QUARTER_DAYS):
                break
        return best


def yahoo_prices(tickers: list[str]) -> list[dict]:
    """Three years of daily prices from the first ticker Yahoo has; [] when none."""
    for ticker in tickers:
        rows = _yahoo_chart(ticker)
        if rows:
            return rows
    return []


def _yahoo_chart(ticker: str) -> list[dict]:
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(ticker)}?range=3y&interval=1d"
    for _ in range(2):
        status, body, _ = http_get(url, "application/json")
        if status in (200, 404):  # 404: Yahoo has no such ticker, and asking again will not change that
            break
        time.sleep(2)
    if status != 200:
        return []
    try:
        res = json.loads(body)["chart"]["result"][0]
        q = res["indicators"]["quote"][0]
        rows = []
        for t, h, l, c in zip(res["timestamp"], q["high"], q["low"], q["close"]):
            if c is not None:
                rows.append({"date": datetime.fromtimestamp(t, IST).date().isoformat(), "interval": "1d",
                             "high": h if h is not None else c, "low": l if l is not None else c, "close": c})
        return rows
    except (KeyError, IndexError, TypeError, ValueError):
        return []


def insider_events(nse: NSEPublicAdapter, symbol: str, start: date, end: date, index: str = "equities") -> list | None:
    """The stock's insider trades filed between `start` and `end` as `parse_insider` rows; None when NSE failed.
    NSE files SME companies' trades under index "sme"."""
    try:
        rows = nse._rows(nse._get("/api/corporates-pit", {"index": index, "symbol": symbol,
                                                          "from_date": start.strftime("%d-%m-%Y"), "to_date": end.strftime("%d-%m-%Y")}))
    except Exception as e:
        log(f"  nse insider {symbol} failed: {e}")
        return None
    return [e for e in (parse_insider(nse, r) for r in rows) if e]


def deal_rows(text: str, kind: str = "bulk") -> dict[str, list]:
    """One of NSE's daily bulk.csv / block.csv files as [date, side, rupees, shares, client, kind] rows by symbol.
    NSE's files carry no time of day."""
    out: dict[str, list] = {}
    for row in csv.DictReader(io.StringIO(text)):
        row = {k.strip(): (v or "").strip() for k, v in row.items() if k}
        sym, d, side = row.get("Symbol", "").upper(), iso_date(row.get("Date")), row.get("Buy/Sell", "").lower()
        qty = screener_page.number(row.get("Quantity Traded"))
        price = screener_page.number(row.get("Trade Price / Wght. Avg. Price"))
        if sym and d and side in ("buy", "sell") and qty and price:
            out.setdefault(sym, []).append([d, side, round(qty * price, 2), int(qty), row.get("Client Name") or None, kind])
    return out


def archive_deals() -> dict[str, list]:
    """The latest session's bulk and block deals of every stock, from NSE's daily files."""
    out: dict[str, list] = {}
    for kind in ("bulk", "block"):
        status, text, _ = http_get(f"https://archives.nseindia.com/content/equities/{kind}.csv", "text/csv", BROWSER_UA)
        if status != 200:
            log(f"  nse {kind}.csv: {status}")
            continue
        for sym, rows in deal_rows(text, kind).items():
            out.setdefault(sym, []).extend(rows)
    return out


def parse_insider(nse: NSEPublicAdapter, r: dict) -> list | None:
    """One PIT row as [date, side, rupees, shares, name, category, mode, filed, holding after %, last date]:
    'acqfromDt' to 'acqtoDt' the days traded, 'tdpTransactionType' Buy or Sell, 'secVal' in rupees, 'secAcq' the
    shares, 'acqName' and 'personCategory' who traded, 'acqMode' how (market, off market, ESOP...), 'date' when NSE
    published the filing ("18-Feb-2026 19:06" -> "2026-02-18 19:06") and 'afterAcqSharesPer' their holding after it."""
    side = str(nse._pick(r, "tdpTransactionType", "acqMode", "transactionType") or "").lower()
    side = "buy" if any(x in side for x in ("buy", "purchase")) else "sell" if any(x in side for x in ("sell", "sale")) else ""
    value = nse._num(nse._pick(r, "secVal", "value"))
    d = iso_date(nse._pick(r, "acqfromDt", "date", "intimDt"))
    if not (d and side and value):
        return None

    def text(key):
        v = nse._pick(r, key)
        return None if v is None or str(v).strip() in ("", "-") else str(v).strip()

    shares = nse._num(nse._pick(r, "secAcq"))
    filed = iso_date(text("date"))
    clock = re.search(r"\b(\d{1,2}):(\d{2})\b", text("date") or "")
    last = iso_date(nse._pick(r, "acqtoDt"))
    return [d, side, round(value, 2), int(shares) if shares else None, text("acqName"), text("personCategory"),
            text("acqMode"), f"{filed} {int(clock[1]):02d}:{clock[2]}" if filed and clock else filed,
            nse._num(nse._pick(r, "afterAcqSharesPer")), last if last and last != d else None]


def top_up_insiders(store: dict, nse: NSEPublicAdapter, today: date, until: float) -> None:
    """Each week's new insider filings of every stock already read, and the whole five years again for a stock whose
    trades were kept before names and times were; stale ones first, until `until`."""
    def unnamed(v):
        return any(len(e) < 5 for e in v.get("insider") or [])
    stale = sorted((s for s, v in store["stocks"].items() if v.get("insider_checked") and (
        unnamed(v) or (today - date.fromisoformat(v["insider_checked"])).days >= INSIDER_EVERY_DAYS)),
        key=lambda s: (not unnamed(store["stocks"][s]), store["stocks"][s]["insider_checked"]))
    topped = failures = 0
    for sym in stale:
        if time.time() > until or failures >= INSIDER_FAILURES:
            break
        stock = store["stocks"][sym]
        since = (today - timedelta(days=365 * app_store.EVENT_YEARS) if unnamed(stock)
                 else date.fromisoformat(stock["insider_checked"]) - timedelta(days=3))
        rows = insider_events(nse, sym, since, today, INSIDER_INDEX.get(stock.get("market", "nse"), "equities"))
        if rows is None:
            failures += 1
        else:
            failures = 0
            app_store.put_events(store, sym, "insider", rows)
            if rows and unnamed(stock):  # the five years read again: what NSE no longer lists goes
                stock["insider"] = [e for e in stock["insider"] if len(e) >= 5]
            stock["insider_checked"] = today.isoformat()
            topped += 1
        time.sleep(INSIDER_GAP)
    log(f"insider trades topped up for {topped}/{len(stale)} stocks")


def company_key(name: str) -> str:
    """A company's name as NSE's files are matched by: lower case, letters and digits only, no Limited or Ltd."""
    key = re.sub(r"[^a-z0-9]", "", str(name).lower().replace("&", "and"))
    return re.sub(r"(limited|ltd)$", "", key)


def pledge_rows(rows: list[dict], names: dict[str, str]) -> dict[str, float]:
    """NSE's pledge data as each stock's share of its promoters' holding pledged or otherwise encumbered, %
    ('percPromoterShares'; 'percSharesPledged' counts every holder's pledges, brokers' margin pledges among them).
    `names` maps company_key to symbol; a company listed twice keeps its latest shareholding ('shp')."""
    out: dict[str, tuple[str, float]] = {}
    for r in rows:
        sym = names.get(company_key(r.get("comName") or ""))
        pct = screener_page.number(r.get("percPromoterShares"))
        shp = iso_date(r.get("shp")) or ""
        if sym and pct is not None and (sym not in out or shp >= out[sym][0]):
            out[sym] = (shp, pct)
    return {s: pct for s, (_, pct) in out.items()}


def top_up_pledges(store: dict, nse: NSEPublicAdapter, universe: dict[str, dict]) -> None:
    """Every NSE and SME company's promoter pledge, from NSE's pledge data (one request a market)."""
    for index, market in NSE_INDEX.items():
        try:
            rows = nse._rows(nse._get("/api/corporate-pledgedata", {"index": index}))
        except Exception as e:
            log(f"  nse pledge data ({index}) failed: {e}")
            continue
        names = {company_key(u["name"]): s for s, u in universe.items() if u.get("market") == market}
        pledges = pledge_rows(rows, names)
        for sym, pct in pledges.items():
            store["stocks"].setdefault(sym, {})["pledge"] = pct
        log(f"promoter pledge ({index}): {len(pledges)} of {len(rows)} rows matched, "
            f"over {valuation.PLEDGE_REJECT:g}%: {sum(p > valuation.PLEDGE_REJECT for p in pledges.values())}")


def auditor_resignations(rows: list[dict]) -> dict[str, set[str]]:
    """NSE announcements as each symbol's days a statutory auditor resigned: filed as "Resignation of Statutory
    Auditor", or as "Change in Auditors" whose text tells of a resignation (an internal, cost, secretarial, tax or
    branch auditor's left out unless the text names the statutory one)."""
    out: dict[str, set[str]] = {}
    for r in rows:
        desc, text = str(r.get("desc") or "").lower(), str(r.get("attchmntText") or "")
        if "resignation of statutory auditor" in desc:
            hit = True
        elif "change in auditor" in desc:
            hit = bool(RESIGNED.search(text)) and ("statutory" in text.lower() or not OTHER_AUDITOR.search(text))
        else:
            hit = False
        day = iso_date(r.get("sort_date") or r.get("an_dt"))
        sym = str(r.get("symbol") or "").upper()
        if hit and day and sym:
            out.setdefault(sym, set()).add(day)
    return out


def top_up_auditors(store: dict, nse: NSEPublicAdapter, universe: dict[str, dict], today: date, until: float) -> None:
    """Statutory auditors' resignations from NSE's market-wide announcements, a week at a time from where the last
    run stopped ("auditor_until"; a year back the first time), until `until`."""
    done = store.setdefault("auditor_until", {})
    for index, market in NSE_INDEX.items():
        start = (date.fromisoformat(done[index]) - timedelta(days=2) if done.get(index)
                 else today - timedelta(days=valuation.AUDITOR_DAYS))
        found = 0
        while start <= today and time.time() < until:
            end = min(today, start + timedelta(days=AUDITOR_CHUNK_DAYS - 1))
            try:
                rows = nse._rows(nse._get("/api/corporate-announcements", {
                    "index": index, "from_date": start.strftime("%d-%m-%Y"), "to_date": end.strftime("%d-%m-%Y")}))
            except Exception as e:
                log(f"  nse announcements ({index}) {start}..{end} failed: {e}")
                break
            for sym, days in auditor_resignations(rows).items():
                if universe.get(sym, {}).get("market") == market:
                    stock = store["stocks"].setdefault(sym, {})
                    stock["auditor_resigned"] = sorted(set(stock.get("auditor_resigned") or []) | days)
                    found += len(days)
            done[index] = end.isoformat()
            start = end + timedelta(days=1)
        log(f"auditor resignations ({index}): {found} found, read up to {done.get(index)}")


# --- run -----------------------------------------------------------------------------------------------------------

def read_store(path: str) -> dict:
    if path and os.path.exists(path):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            store = json.load(f)
        if store.get("version") == app_store.VERSION:
            return store
    return app_store.empty()


def write_json_gz(path: str, data) -> None:
    tmp = path + ".tmp"
    with gzip.open(tmp, "wt", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"), sort_keys=True)
    os.replace(tmp, path)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--store", default="cr360-store.json.gz")
    ap.add_argument("--out", default="cr360.json.gz")
    ap.add_argument("--history-dir", default=".", help="where the app's history files cr360-h-NN.json.gz go")
    ap.add_argument("--minutes", type=float, default=280)
    ap.add_argument("--limit", type=int, default=0, help="read at most this many pages (0: as many as time allows)")
    args = ap.parse_args(argv)
    started = time.time()
    deadline = started + (args.minutes - PRICE_RESERVE_MIN) * 60
    today = datetime.now(IST).date()

    store = read_store(args.store)
    universe, markets = load_universe(today)
    removed = app_store.prune(store, universe, today, markets)
    for sym, u in universe.items():  # a stock that moved from SME to the main board reads as main board from now on
        if sym in store["stocks"]:
            store["stocks"][sym].update(market=u["market"], code=u["code"])
    sectors = load_sectors(universe)
    log(f"store: {len(store['stocks'])} stocks, universe {len(universe)} ({', '.join(sorted(markets))}), pruned {removed}")

    nse = NSEPublicAdapter(timeout=15, retries=2, sleep=.5)
    deals = archive_deals()
    for sym, rows in deals.items():
        if universe.get(sym, {}).get("market") in ("nse", "sme"):  # NSE's files; a BSE symbol is another company
            app_store.put_events(store, sym, "deals", rows)
    log(f"deals from today's files: {sum(len(v) for v in deals.values())}")
    top_up_insiders(store, nse, today, min(deadline, time.time() + INSIDER_MINUTES * 60))
    top_up_pledges(store, nse, universe)
    top_up_auditors(store, nse, universe, today, min(deadline, time.time() + AUDITOR_MINUTES * 60))

    screener = Screener()
    due_quarter = app_store.latest_due_quarter(today)
    order = app_store.due_order(store, universe, today)
    read = failed = 0
    log(f"due pages: {len(order)}")
    for sym in order:
        if time.time() > deadline or screener.blocked or (args.limit and read >= args.limit):
            break
        u = universe[sym]
        stock = store["stocks"].setdefault(sym, {})
        stock.update(market=u["market"], code=u["code"])
        got = screener.page(screener_slug(sym, stock), due_quarter)
        if got is None:
            failed += 1
            if not screener.refusals:  # not on Screener: looked for again later rather than every day
                stock["checked"] = today.isoformat()
            continue
        page, source = got
        app_store.put_page(store, sym, page, today, source)
        if not stock.get("insider_checked") and stock["market"] in INSIDER_INDEX:
            rows = insider_events(nse, sym, today - timedelta(days=365 * app_store.EVENT_YEARS), today, INSIDER_INDEX[stock["market"]])
            if rows is not None:
                app_store.put_events(store, sym, "insider", rows)
                stock["insider_checked"] = today.isoformat()
        read += 1
        if read % 50 == 0:
            log(f"  pages read {read}/{len(order)} (failed {failed})")
            write_json_gz(args.store, store)  # progress survives a cancelled run
    log(f"pages read {read}, failed {failed}, screener blocked: {screener.blocked}")

    app_store.prune(store, universe, today, markets)

    priced = [s for s, v in store["stocks"].items() if v.get("quarters")]
    with ThreadPoolExecutor(6) as pool:
        prices = dict(zip(priced, pool.map(lambda s: yahoo_prices(yahoo_tickers(s, store["stocks"][s])), priced)))
    log(f"prices for {sum(1 for v in prices.values() if v)}/{len(priced)} stocks")
    # A stock Yahoo does not chart is valued at Screener's price.
    last = {s: rows[-1]["close"] if rows else (store["stocks"][s].get("ratios") or {}).get("price") for s, rows in prices.items()}
    anchors = app_store.sector_anchors(store, last, sectors)
    ranks = app_store.size_ranks(store, last)
    records = {}
    for sym in priced:
        stock = store["stocks"][sym]
        anchor = anchors.get(sectors.get(sym, "")) or anchors.get("")
        research = app_store.research_input(sym, stock, prices.get(sym) or [], anchor,
                                            financial=sectors.get(sym) == FINANCIAL_SECTOR, sector=sectors.get(sym, ""),
                                            mos=valuation.size_mos(ranks.get(sym)), today=today)
        rec = app_store.app_record(stock, research)
        if rec:
            records[sym] = rec
    store["updated"] = datetime.now(IST).isoformat(timespec="minutes")
    write_json_gz(args.store, store)
    write_json_gz(args.out, {"v": 1, "updated": store["updated"], "count": len(records), "stocks": records})
    # The history tables go in separate files, a share of the stocks each, so the app fetches only the one for the
    # stock that is opened rather than every stock's history with the scores.
    shards: list[dict] = [{} for _ in range(app_store.HISTORY_SHARDS)]
    for sym in records:
        history = app_store.history_record(store["stocks"][sym])
        chart = app_store.monthly_closes(prices.get(sym) or [])
        if history and chart:
            history["p"] = chart
        if history:
            shards[app_store.history_shard(sym)][sym] = history
    for i, stocks in enumerate(shards):
        write_json_gz(os.path.join(args.history_dir, f"cr360-h-{i:02d}.json.gz"), {"v": 1, "updated": store["updated"], "stocks": stocks})
    log(f"wrote {len(records)} records ({os.path.getsize(args.out) // 1024} KB), store {os.path.getsize(args.store) // 1024} KB, "
        f"{(time.time() - started) / 60:.1f} min")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
