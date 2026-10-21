# Temporary: saves Screener/NSE samples as test fixtures and measures Screener's pacing. Remove before merging.
import json, os, re, time, urllib.error, urllib.parse, urllib.request
from http.cookiejar import CookieJar

UA = "ShareScan (Android)"
BROWSER = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
OUT = "tests/fixtures/cr360"
os.makedirs(OUT, exist_ok=True)

def get(url, ua=UA, accept="text/html", opener=None, headers=None):
    h = {"User-Agent": ua, "Accept": accept, "Accept-Language": "en-IN,en;q=0.9"}
    h.update(headers or {})
    try:
        with (opener.open if opener else urllib.request.urlopen)(urllib.request.Request(url, headers=h), timeout=25) as r:
            return r.status, r.read().decode("utf-8", "replace"), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:300], dict(e.headers)
    except Exception as e:
        return -1, repr(e), {}

def trim(html):
    keep = []
    for pat in (r"<h1[^>]*>.*?</h1>", r'<ul id="top-ratios".*?</ul>'):
        m = re.search(pat, html, re.S)
        if m:
            keep.append(m.group(0))
    for sid in ("quarters", "profit-loss", "balance-sheet", "cash-flow", "shareholding"):
        m = re.search(rf'<section[^>]*id="{sid}".*?</section>', html, re.S)
        if m:
            keep.append(m.group(0))
    return "<html><body>\n" + "\n".join(keep) + "\n</body></html>\n"

for name, path in [("reliance", "RELIANCE/consolidated/"), ("hdfcbank", "HDFCBANK/consolidated/"), ("irfc_consolidated", "IRFC/consolidated/"),
                   ("irfc", "IRFC/"), ("tsc_sme", "TSC/consolidated/"), ("bse_539410", "539410/")]:
    s, html, _ = get("https://www.screener.in/company/" + path)
    print("screener", name, s, len(html))
    if s == 200:
        open(f"{OUT}/screener_{name}.html", "w").write(trim(html))
    time.sleep(8)

jar = CookieJar(); op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
print("nse home", get("https://www.nseindia.com/", BROWSER, opener=op)[0])
H = {"Referer": "https://www.nseindia.com/"}
def nse(name, path, keep=4):
    s, body, _ = get("https://www.nseindia.com" + path, BROWSER, "application/json", op, H)
    try:
        d = json.loads(body)
    except Exception:
        print("nse", name, s, "not json", body[:150]); return
    if isinstance(d, list):
        print("nse", name, s, "list", len(d)); sample = d[:keep]
    else:
        print("nse", name, s, "keys", list(d)[:10], {k: len(v) for k, v in d.items() if isinstance(v, list)})
        sample = {k: (v[:keep] if isinstance(v, list) else v) for k, v in d.items()}
    json.dump(sample, open(f"{OUT}/nse_{name}.json", "w"), indent=1, default=str)
nse("shareholding_reliance", "/api/corporate-share-holdings-master?index=equities&symbol=RELIANCE")
nse("pit_reliance", "/api/corporates-pit?index=equities&symbol=RELIANCE&from_date=21-10-2024&to_date=21-10-2026")
nse("pit_all_week", "/api/corporates-pit?index=equities&from_date=14-10-2026&to_date=21-10-2026")
nse("bulk_reliance", "/api/historical/bulk-deals?symbol=RELIANCE&from=21-10-2021&to=21-10-2026")
nse("bulk_all_week", "/api/historical/bulk-deals?from=14-10-2026&to=21-10-2026")
nse("block_all_week", "/api/historical/block-deals?from=14-10-2026&to=21-10-2026")
for f in ("bulk.csv", "block.csv"):
    s, body, _ = get("https://archives.nseindia.com/content/equities/" + f, BROWSER, "text/csv")
    print("archive", f, s, body.splitlines()[:3])

# Pacing: one page every 5 seconds; note when Screener says 429 and what it asks for.
syms = ["TCS", "INFY", "ITC", "SBIN", "LT", "AXISBANK", "MARUTI", "TITAN", "SUNPHARMA", "ULTRACEMCO", "NESTLEIND", "WIPRO",
        "POWERGRID", "NTPC", "ONGC", "COALINDIA", "TATASTEEL", "JSWSTEEL", "HINDALCO", "GRASIM", "CIPLA", "DRREDDY", "EICHERMOT",
        "HEROMOTOCO", "BAJAJ-AUTO", "BRITANNIA", "DABUR", "MARICO", "PIDILITIND", "HAVELLS"]
for gap in (5, 8):
    print(f"-- pacing {gap}s")
    hits = 0
    for sym in syms[:15] if gap == 5 else syms[15:]:
        t = time.time()
        s, _, h = get(f"https://www.screener.in/company/{urllib.parse.quote(sym)}/consolidated/")
        print(" ", sym, s, round(time.time() - t, 2), h.get("Retry-After", ""))
        if s == 429:
            hits += 1; time.sleep(60)
        time.sleep(gap)
    print(f"-- {gap}s: 429s={hits}")
