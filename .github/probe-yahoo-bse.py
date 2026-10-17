"""Temporary probe: replays a full app scan (NSE, SME, BSE-only, then BSE again) over keep-alive connections from
4 threads, and reports Yahoo's status codes per 500 requests."""
import csv, datetime, http.client, io, threading, time, urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

def download(url, referer="https://www.nseindia.com/all-reports"):
    req = urllib.request.Request(url, headers={"User-Agent": BROWSER, "Referer": referer})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace").lstrip("﻿")

def rows(text):
    return list(csv.reader(io.StringIO(text)))

nse = rows(download("https://archives.nseindia.com/content/equities/EQUITY_L.csv"))
head = [h.strip().upper() for h in nse[0]]
nse_isins = {r[head.index("ISIN NUMBER")].strip() for r in nse[1:] if len(r) > 6}
nse_syms = [r[0].strip() for r in nse[1:] if r]
sme = rows(download("https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv"))
sme_syms = [r[0].strip() for r in sme[1:] if r]
bse = None
for back in range(8):
    day = (datetime.date.today() - datetime.timedelta(days=back)).strftime("%Y%m%d")
    try:
        text = download("https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_%s_F_0000.CSV" % day, "https://www.bseindia.com/")
        if "TckrSymb" in text[:500]: bse = rows(text); break
    except Exception:
        pass
h = [x.strip() for x in bse[0]]
at = {k: h.index(k) for k in ("TckrSymb", "FinInstrmId", "ISIN", "SctySrs", "FinInstrmNm")}
bse_all = []
for r in bse[1:]:
    if len(r) < len(h) or r[at["SctySrs"]].strip().upper() in {"E", "F", "G", "GS", "IF", "IT", "DT"}: continue
    if "ETF" in r[at["FinInstrmNm"]].upper().split(): continue
    bse_all.append((r[at["TckrSymb"]].strip().upper(), r[at["FinInstrmId"]].strip(), r[at["ISIN"]].strip()))
nse_set = set(nse_syms) | set(sme_syms)
bse_only = [(s, c) for s, c, i in bse_all if i not in nse_isins and s not in nse_set]
print("nse", len(nse_syms), "sme", len(sme_syms), "bse-only", len(bse_only), "bse all", len(bse_all))

local = threading.local()
log, lock = [], threading.Lock()
start = time.time()

def chart(ticker):
    for attempt in range(2):
        conn = getattr(local, "conn", None)
        if conn is None:
            conn = local.conn = http.client.HTTPSConnection("query1.finance.yahoo.com", timeout=25)
        try:
            conn.request("GET", "/v8/finance/chart/%s?range=3mo&interval=1d" % ticker,
                         headers={"User-Agent": "ShareScan/1.0 (Android)", "Accept": "application/json,text/plain,*/*"})
            r = conn.getresponse()
            body = r.read()
            with lock:
                log.append((time.time() - start, r.status, r.getheader("Retry-After"), body[:120] if r.status != 200 else b""))
            if r.getheader("Connection", "").lower() == "close":
                conn.close(); local.conn = None
            return r.status
        except Exception as e:
            conn.close(); local.conn = None
            if attempt: 
                with lock: log.append((time.time() - start, -1, None, str(e)[:120].encode()))
                return -1

def stock(listing):
    for ticker in listing:
        if chart(ticker) == 200: return

jobs = [[s + ".NS"] for s in nse_syms] + [[s + ".NS"] for s in sme_syms] + \
       [[s + ".BO", c + ".BO"] for s, c in bse_only]
with ThreadPoolExecutor(4) as pool:
    list(pool.map(stock, jobs))
print("scan 1 done after", round(time.time() - start), "s,", len(log), "requests")
mark = len(log)
with ThreadPoolExecutor(4) as pool:
    list(pool.map(stock, [[s + ".BO", c + ".BO"] for s, c, i in bse_all]))
print("scan 2 done after", round(time.time() - start), "s,", len(log) - mark, "requests")
for i in range(0, len(log), 500):
    window = log[i:i + 500]
    print("requests %5d-%5d t=%4ds %s" % (i, i + len(window), window[0][0], Counter(x[1] for x in window).most_common()))
bad = [x for x in log if x[1] != 200 and x[1] != 404]
print("first non-200/404:", bad[:5])
