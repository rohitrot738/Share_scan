"""Temporary probe: sizes and status of Yahoo chart responses for the BSE-only stocks the app scans."""
import csv, datetime, io, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
from collections import Counter

BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

def fetch(url, agent="ShareScan/1.0 (Android)", referer=None):
    req = urllib.request.Request(url, headers={"User-Agent": agent, **({"Referer": referer} if referer else {})})
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            body = r.read()
            return r.status, body, time.time() - start, r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, e.read(), time.time() - start, url
    except Exception as e:
        return -1, str(e).encode(), time.time() - start, url

nse = fetch("https://archives.nseindia.com/content/equities/EQUITY_L.csv", BROWSER)[1].decode("utf-8", "replace")
rows = list(csv.reader(io.StringIO(nse.lstrip("﻿"))))
head = [h.strip().upper() for h in rows[0]]
isins = {r[head.index("ISIN NUMBER")].strip() for r in rows[1:] if len(r) > 6}
syms = {r[0].strip() for r in rows[1:] if r}
print("nse rows", len(rows) - 1)

bse = None
today = datetime.date.today()
for back in range(8):
    day = (today - datetime.timedelta(days=back)).strftime("%Y%m%d")
    status, body, _, _ = fetch("https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_%s_F_0000.CSV" % day, BROWSER, "https://www.bseindia.com/")
    if status == 200 and body.lstrip(b"\xef\xbb\xbf").startswith(b"TradDt") or b"TckrSymb" in body[:500]:
        bse = body.decode("utf-8", "replace").lstrip("﻿"); print("bhavcopy", day); break
rows = list(csv.reader(io.StringIO(bse)))
head = [h.strip() for h in rows[0]]
at = {k: head.index(k) for k in ("TckrSymb", "FinInstrmId", "ISIN", "SctySrs", "FinInstrmNm")}
skip_groups = {"E", "F", "G", "GS", "IF", "IT", "DT"}
listings = []
for r in rows[1:]:
    if len(r) < len(head): continue
    sym = r[at["TckrSymb"]].strip().upper()
    if r[at["SctySrs"]].strip().upper() in skip_groups or "ETF" in r[at["FinInstrmNm"]].upper().split(): continue
    if r[at["ISIN"]].strip() in isins or sym in syms: continue
    listings.append((sym, r[at["FinInstrmId"]].strip()))
print("bse-only", len(listings))

def probe(listing):
    sym, code = listing
    out = []
    for ticker in (sym + ".BO", code + ".BO"):
        status, body, secs, final = fetch("https://query1.finance.yahoo.com/v8/finance/chart/%s?range=3mo&interval=1d" % ticker)
        out.append((ticker, status, len(body), round(secs, 2), final, body[:160]))
        if status == 200: break
    return out

results = []
start = time.time()
with ThreadPoolExecutor(8) as pool:
    for out in pool.map(probe, listings):
        results.extend(out)
print("requests", len(results), "in", round(time.time() - start), "s")
print("status", Counter(r[1] for r in results).most_common())
sizes = sorted(results, key=lambda r: -r[2])
print("largest responses:")
for r in sizes[:25]:
    print(r[0], r[1], r[2], r[3], r[4], r[5])
buckets = Counter(min(r[2] // 10000, 20) for r in results)
print("size buckets (10KB):", sorted(buckets.items()))
print("redirected:", [r[:5] for r in results if "chart/" + r[0] not in r[4]][:20])
print("slowest:", [r[:4] for r in sorted(results, key=lambda r: -r[3])[:10]])
