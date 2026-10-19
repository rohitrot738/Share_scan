#!/usr/bin/env bash
# Temporary: looks for a sector source that covers NSE, SME and BSE-only stocks. Remove before merging.
set -u
echo "now: $(TZ=Asia/Kolkata date)"
python3 - <<'PY'
import csv, datetime, gzip, io, json, random, time, urllib.request, http.cookiejar

UA_APP = "ShareScan/1.0 (Android)"
UA_WEB = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

def get(url, ua=UA_WEB, headers=None, raw=False):
    req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept": "*/*", **(headers or {})})
    try:
        r = opener.open(req, timeout=25)
        body = r.read()
        return r.status, body if raw else body.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:300].decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)

# 1) NSE's daily security file: any industry column?
day = datetime.date.today()
for back in range(8):
    d = day - datetime.timedelta(days=back)
    st, body = get(f"https://nsearchives.nseindia.com/content/cm/NSE_CM_security_{d:%d%m%Y}.csv.gz",
                   headers={"Referer": "https://www.nseindia.com/all-reports"}, raw=True)
    if st == 200:
        text = gzip.decompress(body).decode("utf-8", "replace")
        print("security file", d, "header:", text.splitlines()[0][:900])
        print("row:", text.splitlines()[1][:600])
        break
else:
    print("no security file")

# Sample symbols: NSE small caps, SME, BSE-only.
st, nse = get("https://archives.nseindia.com/content/equities/EQUITY_L.csv")
nse_rows = list(csv.reader(io.StringIO(nse)))[1:]
nse_syms = [r[0] for r in nse_rows]
nse_isins = {r[6].strip() for r in nse_rows if len(r) > 6}
st, sme = get("https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv", headers={"Referer": "https://www.nseindia.com/"})
sme_syms = [r[0] for r in list(csv.reader(io.StringIO(sme)))[1:] if r]
print("nse", len(nse_syms), "sme", len(sme_syms))
bse = []
for back in range(8):
    d = day - datetime.timedelta(days=back)
    st, body = get(f"https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_{d:%Y%m%d}_F_0000.CSV",
                   headers={"Referer": "https://www.bseindia.com/"})
    if st == 200 and "TckrSymb" in body[:500]:
        rows = list(csv.DictReader(io.StringIO(body)))
        bse = [(r["TckrSymb"], r["FinInstrmId"]) for r in rows if r.get("ISIN") not in nse_isins and r.get("SctySrs") not in ("E","F","G","GS","IF","IT","DT")]
        print("bse-only", len(bse), "from", d)
        break
random.seed(7)
samples = {
    "NSE": [s + ".NS" for s in random.sample(nse_syms, 25)],
    "SME": [s + ".NS" for s in random.sample(sme_syms, 20)] if sme_syms else [],
    "BSE": [c + ".BO" for _, c in random.sample(bse, 20)] if bse else [],
}

# 2) Yahoo search: sector and industry per symbol, no crumb.
for market, syms in samples.items():
    found = 0
    for t in syms:
        st, body = get(f"https://query2.finance.yahoo.com/v1/finance/search?q={t}&quotesCount=3&newsCount=0&listsCount=0", ua=UA_APP)
        sector = industry = None
        if st == 200:
            for q in json.loads(body).get("quotes", []):
                if q.get("symbol", "").upper() == t.upper():
                    sector, industry = q.get("sector"), q.get("industry")
        found += bool(sector)
        print(f"search {market} {t}: {st} {sector} / {industry}")
        time.sleep(0.15)
    print(f"== search coverage {market}: {found}/{len(syms)}")

# 3) Yahoo quoteSummary assetProfile with a crumb.
get("https://fc.yahoo.com", ua=UA_WEB)
st, crumb = get("https://query1.finance.yahoo.com/v1/test/getcrumb", ua=UA_WEB)
print("crumb", st, crumb[:40])
if st == 200:
    for market, syms in samples.items():
        found = 0
        for t in syms[:10]:
            st, body = get(f"https://query2.finance.yahoo.com/v10/finance/quoteSummary/{t}?modules=assetProfile&crumb={crumb}", ua=UA_WEB)
            sector = None
            if st == 200:
                prof = json.loads(body)["quoteSummary"]["result"][0].get("assetProfile", {})
                sector = prof.get("sector"), prof.get("industry")
                found += bool(prof.get("sector"))
            print(f"summary {market} {t}: {st} {sector}")
            time.sleep(0.2)
        print(f"== summary coverage {market}: {found}/10")

# 4) BSE's scrip list with browser headers.
st, body = get("https://api.bseindia.com/BseIndiaAPI/api/ListofScripData/w?Group=&Scripcode=&industry=&segment=Equity&status=Active",
               headers={"Referer": "https://www.bseindia.com/", "Origin": "https://www.bseindia.com", "Accept": "application/json, text/plain, */*",
                        "Accept-Language": "en-US,en;q=0.9"})
print("bse api", st, body[:300])
PY
