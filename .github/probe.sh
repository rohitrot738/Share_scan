#!/usr/bin/env bash
# Temporary: looks for a sector source that covers NSE, SME and BSE-only stocks. Remove before merging.
set -u
echo "now: $(TZ=Asia/Kolkata date)"
python3 - <<'PY'
import collections, json, time, urllib.request, http.cookiejar

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json, text/plain, */*",
                                               "Accept-Language": "en-US,en;q=0.9", **(headers or {})})
    try:
        r = opener.open(req, timeout=25); return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:200].decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)

BSE = {"Referer": "https://www.bseindia.com/", "Origin": "https://www.bseindia.com"}
st, body = get("https://api.bseindia.com/BseIndiaAPI/api/ListofScripData/w?Group=&Scripcode=&industry=&segment=Equity&status=Active", BSE)
print("bse list", st, len(body))
if st == 200:
    rows = json.loads(body)
    print("keys:", list(rows[0].keys()))
    print("first:", json.dumps(rows[0])[:700])
    for key in rows[0]:
        vals = [r.get(key) for r in rows]
        filled = sum(1 for v in vals if v not in (None, "", "-"))
        print(f"  {key}: filled {filled}/{len(rows)} e.g. {collections.Counter(v for v in vals if v).most_common(3)}")

for code in ["500002", "511700", "531033", "539149", "530163"]:
    st, body = get(f"https://api.bseindia.com/BseIndiaAPI/api/ComHeadernew/w?quotetype=EQ&scripcode={code}&seriesid=", BSE)
    print("bse header", code, st, body[:500])
    time.sleep(0.3)

st, _ = get("https://www.nseindia.com/", {"Accept": "text/html"})
print("nse home", st, [c.name for c in jar])
for sym in ["RELIANCE", "DRONE", "GRETEX", "SHYAMDHANI", "KODYTECH"]:
    st, body = get(f"https://www.nseindia.com/api/quote-equity?symbol={sym}", {"Referer": f"https://www.nseindia.com/get-quotes/equity?symbol={sym}"})
    info = None
    if st == 200:
        try: info = json.loads(body).get("industryInfo")
        except Exception: info = body[:150]
    print("nse quote", sym, st, info if info is not None else body[:150])
    time.sleep(0.5)
PY
