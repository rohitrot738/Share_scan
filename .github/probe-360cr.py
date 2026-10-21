# Temporary: which 360CR inputs an app could fetch for one stock. Remove before merging.
import json, re, time, urllib.error, urllib.parse, urllib.request
from http.cookiejar import CookieJar

APP_UA = "ShareScan (Android)"
BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

def get(url, ua=BROWSER_UA, accept="text/html", opener=None, headers=None):
    h = {"User-Agent": ua, "Accept": accept, "Accept-Language": "en-IN,en;q=0.9"}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    t = time.time()
    try:
        with (opener.open if opener else urllib.request.urlopen)(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace"), round(time.time() - t, 2)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:200], round(time.time() - t, 2)
    except Exception as e:
        return -1, repr(e)[:200], round(time.time() - t, 2)

def section(html, sid):
    m = re.search(rf'<section[^>]*id="{sid}"(.*?)</section>', html, re.S)
    return m.group(1) if m else ""

def columns(block, table_id=None):
    if table_id:
        m = re.search(rf'id="{table_id}"(.*?)</table>', block, re.S)
        block = m.group(1) if m else ""
    head = re.search(r"<thead>(.*?)</thead>", block, re.S)
    if not head:
        return []
    cells = [re.sub(r"<[^>]+>|\s+", " ", c).strip() for c in re.findall(r"<th[^>]*>(.*?)</th>", head.group(1), re.S)]
    return [c for c in cells if c]

def rows(block):
    return [re.sub(r"<[^>]+>|\s+|&nbsp;|\+", " ", c).strip() for c in re.findall(r'<td class="text"[^>]*>(.*?)</td>', block, re.S)]

def ratios(html):
    top = re.search(r'id="top-ratios"(.*?)</ul>', html, re.S)
    if not top:
        return {}
    out = {}
    for name, val in re.findall(r'<span class="name">\s*(.*?)\s*</span>.*?<span class="nowrap value">(.*?)</span>', top.group(1), re.S):
        out[re.sub(r"\s+", " ", name)] = re.sub(r"<[^>]+>|\s+", " ", val).strip()
    return out

def screener(sym, ua):
    for kind in ("consolidated/", ""):
        url = f"https://www.screener.in/company/{urllib.parse.quote(sym)}/{kind}"
        s, html, t = get(url, ua)
        if s != 200:
            print(f"  screener {kind or 'standalone/'} {s} {t}s")
            continue
        q = section(html, "quarters"); pl = section(html, "profit-loss"); bs = section(html, "balance-sheet")
        cf = section(html, "cash-flow"); sh = section(html, "shareholding")
        qc, pc, bc, cc = columns(q), columns(pl), columns(bs), columns(cf)
        shq = columns(sh, "quarterly-shp")
        print(f"  screener {kind or 'standalone/'} 200 {t}s quarters={len(qc) - 1} [{qc[1] if len(qc) > 1 else ''}..{qc[-1] if qc else ''}] "
              f"annual_pl={len(pc) - 1} balance={len(bc) - 1} cashflow={len(cc) - 1} shareholding_q={len(shq) - 1} [{shq[1] if len(shq) > 1 else ''}..{shq[-1] if shq else ''}]")
        print(f"    quarter rows: {rows(q)[:12]}")
        print(f"    holder rows: {rows(sh)[:7]}")
        print(f"    ratios: {ratios(html)}")
        if len(qc) > 1:
            return True
    return False

def yahoo_series(sym):
    types = ",".join(["quarterlyTotalRevenue", "quarterlyNetIncome", "quarterlyOperatingCashFlow", "quarterlyTotalDebt", "quarterlyStockholdersEquity",
                      "annualTotalRevenue", "annualNetIncome", "annualOperatingCashFlow", "annualFreeCashFlow", "annualTotalDebt", "annualStockholdersEquity"])
    url = f"https://query2.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/{sym}.NS?type={types}&period1=1262304000&period2={int(time.time())}"
    s, body, t = get(url, APP_UA, "application/json")
    if s != 200:
        print(f"  yahoo timeseries {s} {t}s {body[:120]}")
        return
    counts = {}
    for res in json.loads(body).get("timeseries", {}).get("result", []):
        typ = res.get("meta", {}).get("type", ["?"])[0]
        counts[typ] = len([v for v in res.get(typ, []) or [] if v])
    print(f"  yahoo timeseries 200 {t}s {counts}")

def yahoo_weekly(sym):
    s, body, t = get(f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}.NS?range=5y&interval=1wk", APP_UA, "application/json")
    n = len(json.loads(body)["chart"]["result"][0]["timestamp"]) if s == 200 else 0
    print(f"  yahoo 5y weekly {s} {t}s weeks={n}")

def nse(sym):
    jar = CookieJar(); op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    s, _, t = get("https://www.nseindia.com/", BROWSER_UA, opener=op)
    print(f"  nse home {s} {t}s cookies={len(jar)}")
    q = urllib.parse.quote(sym)
    for name, path in [
        ("shareholding", f"/api/corporate-share-holdings-master?index=equities&symbol={q}"),
        ("insider", f"/api/corporates-pit?index=equities&symbol={q}&from_date=01-01-2021&to_date=21-10-2026"),
        ("bulk", f"/api/historical/bulk-deals?symbol={q}&from=01-01-2021&to=21-10-2026"),
        ("actions", f"/api/corporates-corporateActions?index=equities&symbol={q}"),
        ("results", f"/api/corporates-financial-results?index=equities&symbol={q}&period=Quarterly"),
    ]:
        s, body, t = get("https://www.nseindia.com" + path, BROWSER_UA, "application/json", op, {"Referer": "https://www.nseindia.com/"})
        n = "?"
        try:
            d = json.loads(body); n = len(d if isinstance(d, list) else d.get("data", []))
        except Exception:
            pass
        print(f"  nse {name} {s} {t}s rows={n}")

STOCKS = ["RELIANCE", "HDFCBANK", "TCS", "M&M", "DIXON", "KPIGREEN", "ETERNAL", "IRFC", "HATSUN", "TSC", "539410"]
for sym in STOCKS:
    print(f"== {sym}")
    ok = screener(sym, APP_UA)
    if not ok:
        print("  (browser UA)"); screener(sym, BROWSER_UA)
    if not sym.isdigit():
        yahoo_series(sym); yahoo_weekly(sym)
    time.sleep(1.5)
print("== NSE")
for sym in ("RELIANCE", "DIXON"):
    print(f"-- {sym}"); nse(sym)
