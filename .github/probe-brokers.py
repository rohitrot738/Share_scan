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

def search(q, field, want):
    s, u, b = get("https://groww.in/v1/api/search/v1/entity?app=false&page=0&q=" + urllib.parse.quote(q) + "&size=10", "application/json")
    try:
        for c in json.loads(b).get("content", []):
            if c.get("entity_type") == "Stocks" and c.get(field) == want:
                return c.get("search_id") or c.get("id")
    except Exception as e:
        return "ERR " + repr(e) + b[:100]
    return None

def page(slug_):
    s, u, b = get("https://groww.in/stocks/" + slug_)
    return s == 200 and "/stocks/" in u

import urllib.parse
random.seed(11)
must = ["M&M", "3MINDIA", "ETERNAL", "BAJAJ-AUTO", "J&KBANK", "BNALTD", "KMCSHIL", "VMART", "SIS", "TIKONA", "PHOENIXLTD", "NAUKRI"]
sample = [s_ for s_ in must if s_ in names] + random.sample(sorted(names), 100)
found = pages = 0
for sym in sample:
    sid = search(sym, "nse_scrip_code", sym)
    good = bool(sid) and not sid.startswith("ERR") and page(sid)
    found += bool(sid) and not str(sid).startswith("ERR")
    pages += good
    if not good: print("NSE MISS", sym, names[sym], sid)
    time.sleep(0.3)
print(f"== NSE search ids {found}/{len(sample)}, pages {pages} ==")

s, u, b = get("https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv", "text/csv")
sme = [{k.strip(): v.strip() for k, v in r.items()} for r in csv.DictReader(io.StringIO(b))] if s == 200 else []
print("SME list", s, len(sme))
for r in random.sample(sme, min(25, len(sme))):
    sym = r["SYMBOL"]
    sid = search(sym, "nse_scrip_code", sym)
    print("SME", sym, sid, page(sid) if sid and not sid.startswith("ERR") else "-")
    time.sleep(0.3)

# BSE-only stocks: Groww's bse_scrip_code is the numeric code.
for code, sym in [("539410", "MUZALI"), ("500325", "RELIANCE"), ("531254", "x"), ("512068", "x"), ("543942", "x")]:
    sid = search(code, "bse_scrip_code", code)
    print("BSE", code, sid, page(sid) if sid and not sid.startswith("ERR") else "-")
    time.sleep(0.3)
