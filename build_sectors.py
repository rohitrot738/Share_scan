"""Builds data/sectors.json: the industry of every stock the Android app can scan (NSE main board, NSE SME, BSE-only).

The app scans only the industries the user switches on, per exchange, so it needs every stock's industry before it
scans. One classification is used throughout, the one NSE's index files and BSE's scrip pages share ("Capital Goods",
"Healthcare", "Financial Services", ...):

1. NSE's index constituent files (Nifty Total Market and others) give the industry of the stocks in them.
2. BSE's scrip header gives it for any stock listed on BSE, found through its ISIN in BSE's scrip list.

A stock neither source knows stays out of the file; the app counts it as "other". The previous file is reused, so a
weekly run only asks BSE about stocks that are new or still unknown.

Usage: python build_sectors.py [out.json]
"""
import csv
import datetime
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else "data/sectors.json"
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
BSE_HEADERS = {"Referer": "https://www.bseindia.com/", "Origin": "https://www.bseindia.com"}
NSE_HEADERS = {"Referer": "https://www.nseindia.com/"}
INDEX_FILES = [
    "ind_niftytotalmarket_list.csv",
    "ind_niftymicrocap250_list.csv",
    "ind_niftysmeemerge_list.csv",
    "ind_niftyindiamanufacturing_list.csv",
]
BSE_NON_EQUITY = {"E", "F", "G", "GS", "IF", "IT", "DT"}
# BSE spells a few of NSE's industries differently; the app shows NSE's spelling.
SPELLING = {
    "Automobile & Auto Components": "Automobile and Auto Components",
    "Oil, Gas & Consumable Fuels": "Oil Gas & Consumable Fuels",
    "Media, Entertainment & Publication": "Media Entertainment & Publication",
    "FMCG": "Fast Moving Consumer Goods",
    "IT": "Information Technology",
}


def get(url, headers=None, tries=4):
    for attempt in range(tries):
        req = urllib.request.Request(url, headers={
            "User-Agent": UA,
            "Accept": "application/json, text/plain, text/csv, */*",
            "Accept-Language": "en-US,en;q=0.9",
            **(headers or {}),
        })
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8-sig", "replace")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(2 * (attempt + 1) ** 2)
        except Exception:
            time.sleep(2 * (attempt + 1) ** 2)
    return None


def listing_isins(text):
    """ISIN to symbol from an NSE listing file, whose headers come with stray spaces ("ISIN NUMBER", "ISIN_NUMBER")."""
    isins = {}
    for r in csv.DictReader(io.StringIO(text or "")):
        row = {k.strip().upper().replace("_", " "): (v or "").strip() for k, v in r.items() if k}
        if row.get("ISIN NUMBER") and row.get("SYMBOL"):
            isins[row["ISIN NUMBER"]] = row["SYMBOL"]
    return isins


def clean(industry):
    industry = " ".join((industry or "").split())
    return SPELLING.get(industry, industry)


def main():
    previous = {}
    if os.path.exists(OUT):
        with open(OUT, encoding="utf-8") as f:
            old = json.load(f)
        names = old.get("sectors", [])
        for market in ("nse", "sme", "bse"):
            for isin, index in old.get(market, {}).items():
                previous[isin] = names[index]

    nse_isin = listing_isins(get("https://archives.nseindia.com/content/equities/EQUITY_L.csv"))
    sme_isin = listing_isins(get("https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv", NSE_HEADERS) or
                             get("https://archives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv", NSE_HEADERS))
    print("NSE", len(nse_isin), "SME", len(sme_isin))

    bse_only = {}
    today = datetime.date.today()
    for back in range(10):
        day = today - datetime.timedelta(days=back)
        text = get(f"https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_{day:%Y%m%d}_F_0000.CSV", BSE_HEADERS)
        if text and "TckrSymb" in text[:400]:
            for r in csv.DictReader(io.StringIO(text)):
                isin = (r.get("ISIN") or "").strip()
                if isin and isin not in nse_isin and isin not in sme_isin and r.get("SctySrs", "").strip() not in BSE_NON_EQUITY:
                    bse_only[isin] = r.get("FinInstrmId", "").strip()
            print("BSE-only", len(bse_only), "from", day)
            break

    known = {}
    for name in INDEX_FILES:
        text = get("https://archives.nseindia.com/content/indices/" + name) or get("https://www.niftyindices.com/IndexConstituent/" + name)
        rows = list(csv.DictReader(io.StringIO(text or "")))
        added = 0
        for r in rows:
            isin = (r.get("ISIN Code") or "").strip()
            industry = clean(r.get("Industry"))
            if isin and industry and isin not in known:
                known[isin] = industry
                added += 1
        print(name, "rows", len(rows), "new", added)

    scrip_codes = {}
    text = get("https://api.bseindia.com/BseIndiaAPI/api/ListofScripData/w?Group=&Scripcode=&industry=&segment=Equity&status=Active", BSE_HEADERS)
    for r in json.loads(text or "[]"):
        if r.get("ISIN_NUMBER") and r.get("SCRIP_CD"):
            scrip_codes[r["ISIN_NUMBER"].strip()] = str(r["SCRIP_CD"]).strip()
    for isin, code in bse_only.items():
        scrip_codes.setdefault(isin, code)
    print("BSE scrip codes", len(scrip_codes))

    wanted = [isin for isin in list(nse_isin) + list(sme_isin) + list(bse_only) if isin not in known]
    asked = answered = 0
    for isin in wanted:
        if isin in previous:
            known[isin] = previous[isin]
            continue
        code = scrip_codes.get(isin)
        if not code:
            continue
        asked += 1
        text = get(f"https://api.bseindia.com/BseIndiaAPI/api/ComHeadernew/w?quotetype=EQ&scripcode={code}&seriesid=", BSE_HEADERS, tries=3)
        try:
            industry = clean(json.loads(text or "{}").get("IndustryNew"))
        except ValueError:
            industry = ""
        if industry:
            known[isin] = industry
            answered += 1
        if asked % 200 == 0:
            print(f"asked BSE {asked}, answered {answered}", flush=True)
        time.sleep(0.2)
    print(f"asked BSE {asked}, answered {answered}")

    sectors = sorted({known[i] for i in known})
    index = {name: i for i, name in enumerate(sectors)}
    data = {"v": 1, "updated": today.isoformat(), "sectors": sectors}
    for market, isins in (("nse", nse_isin), ("sme", sme_isin), ("bse", bse_only)):
        data[market] = {isin: index[known[isin]] for isin in sorted(isins) if isin in known}
        print(f"{market}: {len(data[market])}/{len(isins)} with an industry")
    counts = {}
    for market in ("nse", "sme", "bse"):
        for i in data[market].values():
            counts[sectors[i]] = counts.get(sectors[i], 0) + 1
    print("industries:", sorted(counts.items(), key=lambda kv: -kv[1]))
    os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"), ensure_ascii=False)
    print("wrote", OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
