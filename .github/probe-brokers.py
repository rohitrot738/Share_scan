# Temporary: checks broker app ids and Groww stock-page slugs. Remove before merging.
import csv, io, json, random, re, time, urllib.request, urllib.error

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

def get(url, accept="text/html"):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept, "Accept-Language": "en-IN,en"})
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, r.geturl(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, url, e.read().decode("utf-8", "replace")[:300]
    except Exception as e:
        return -1, url, repr(e)

def title(html):
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.S)
    return m.group(1).strip()[:100] if m else "-"

print("== Play Store ==")
for pkg in ["com.zerodha.kite3", "com.zerodha.kite", "com.msf.angelmobile", "com.angelbroking.angelwealth", "com.nextbillion.groww", "com.groww.app"]:
    s, u, b = get(f"https://play.google.com/store/apps/details?id={pkg}&hl=en_IN")
    print(pkg, s, title(b))

print("== assetlinks ==")
for host in ["groww.in", "kite.zerodha.com", "zerodha.com", "www.angelone.in", "angelone.in", "trade.angelone.in"]:
    s, u, b = get(f"https://{host}/.well-known/assetlinks.json", "application/json")
    pk = sorted(set(re.findall(r'"package_name"\s*:\s*"([^"]+)"', b)))
    print(host, s, pk)

print("== Angel One page patterns ==")
for p in ["stocks/reliance-industries-ltd", "stocks/reliance-industries-ltd-share-price", "share-price/reliance-industries-ltd", "stocks/RELIANCE"]:
    s, u, b = get("https://www.angelone.in/" + p)
    print(p, s, u, title(b))

print("== Groww search API ==")
for api in ["https://groww.in/v1/api/search/v1/entity?app=false&page=0&q=RELIANCE&size=6",
            "https://groww.in/v1/api/search/v3/query/global/st_query?query=RELIANCE&size=6"]:
    s, u, b = get(api, "application/json")
    print(api, s, b[:700])

s, u, b = get("https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv", "text/csv")
print("== NSE list ==", s, len(b))
rows = list(csv.DictReader(io.StringIO(b)))
rows = [{k.strip(): v.strip() for k, v in r.items()} for r in rows]
names = {r["SYMBOL"]: r["NAME OF COMPANY"] for r in rows if r.get("SERIES") in ("EQ", "BE")}
print("stocks", len(names))

def slug(name):
    s = name.lower()
    s = re.sub(r"\blimited\b", "ltd", s)
    s = s.replace("'", "").replace("&", " ")
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")
    return s

def search(sym):
    s, u, b = get(f"https://groww.in/v1/api/search/v1/entity?app=false&page=0&q={sym}&size=6", "application/json")
    try:
        for c in json.loads(b).get("content", []):
            if c.get("nse_scrip_code") == sym or c.get("bse_scrip_code") == sym:
                return c.get("id") or c.get("search_id"), c.get("title")
    except Exception:
        pass
    return None, b[:120]

random.seed(7)
must = ["RELIANCE", "M&M", "DRREDDY", "LT", "NAUKRI", "BAJAJ-AUTO", "ONGC", "SBIN", "3MINDIA", "ITC", "HDFCBANK", "UNITDSPR", "ETERNAL", "J&KBANK", "TATAMOTORS"]
sample = [s for s in must if s in names] + random.sample(sorted(names), min(140, len(names)))
ok = 0
bad = []
for sym in sample:
    sl = slug(names[sym])
    s, u, b = get("https://groww.in/stocks/" + sl)
    if s == 200 and "/stocks/" in u:
        ok += 1
    else:
        bad.append((sym, names[sym], sl, s, u) + search(sym))
    time.sleep(0.4)
print(f"== Groww slug hits {ok}/{len(sample)} ==")
for row in bad:
    print("MISS", row)
