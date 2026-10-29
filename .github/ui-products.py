"""Temporary review helper: real products for a few well-known companies, put into the app's R/S file for the
emulator screenshots (the nightly cache adds them for every stock once this is merged). Removed before merging.

    python .github/ui-products.py OUT.json.gz LOG.txt
"""
import gzip
import json
import sys
import time
import urllib.parse
import urllib.request

from cr360 import products, screener_page

SOURCE = "https://github.com/rohitrot738/Share_scan/releases/download/cr360-data/cr360.json.gz"
FIN, IT, OTHER = products.FINANCIAL, products.IT, "Other"
STOCKS = {
    "MRF": OTHER, "APOLLOTYRE": OTHER, "CEATLTD": OTHER, "BALKRISIND": OTHER, "JKTYRE": OTHER, "MARUTI": OTHER,
    "M&M": OTHER, "BAJAJ-AUTO": OTHER, "HEROMOTOCO": OTHER, "EICHERMOT": OTHER, "ASHOKLEY": OTHER, "TVSMOTOR": OTHER,
    "EXIDEIND": OTHER, "BOSCHLTD": OTHER, "SUNPHARMA": OTHER, "CIPLA": OTHER, "DRREDDY": OTHER, "APOLLOHOSP": OTHER,
    "DABUR": OTHER, "HINDUNILVR": OTHER, "ITC": OTHER, "NESTLEIND": OTHER, "BRITANNIA": OTHER, "TATACONSUM": OTHER,
    "MARICO": OTHER, "COLPAL": OTHER, "ASIANPAINT": OTHER, "BERGEPAINT": OTHER, "ULTRACEMCO": OTHER,
    "AMBUJACEM": OTHER, "SUPREMEIND": OTHER, "ASTRAL": OTHER, "KAJARIACER": OTHER, "TATASTEEL": OTHER,
    "JSWSTEEL": OTHER, "HINDALCO": OTHER, "COALINDIA": OTHER, "RELIANCE": OTHER, "ONGC": OTHER, "NTPC": OTHER,
    "TATAPOWER": OTHER, "HAVELLS": OTHER, "POLYCAB": OTHER, "VOLTAS": OTHER, "WHIRLPOOL": OTHER, "DIXON": OTHER,
    "BHARTIARTL": OTHER, "TCS": IT, "INFY": IT, "HDFCBANK": FIN, "ICICIBANK": FIN, "BAJFINANCE": FIN, "SBILIFE": FIN,
    "TITAN": OTHER, "PAGEIND": OTHER, "BATAINDIA": OTHER, "RELAXO": OTHER, "TRENT": OTHER, "JUBLFOOD": OTHER,
    "INDHOTEL": OTHER, "HAL": OTHER, "BEL": OTHER, "PIDILITIND": OTHER, "UPL": OTHER, "CHAMBLFERT": OTHER,
    "BALRAMCHIN": OTHER, "VBL": OTHER, "UBL": OTHER, "PVRINOX": OTHER, "VIPIND": OTHER,
}


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "ShareScan (Android)", "Accept": "text/html"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def main(out, log_path):
    book = json.loads(gzip.decompress(get(SOURCE)))
    lines = []
    for symbol, sector in STOCKS.items():
        time.sleep(3)
        try:
            page = screener_page.parse(get("https://www.screener.in/company/" + urllib.parse.quote(symbol) + "/").decode("utf-8", "replace"))
        except Exception as e:  # noqa: BLE001 - a page that does not come is left out
            lines.append(f"{symbol}: page failed ({e})")
            continue
        made = products.classify(page.get("about", ""), page.get("points", ""), sector)
        row = book["stocks"].get(symbol)
        if made and row is not None:
            row["pr"] = made
        lines.append(f"{symbol}: {made}{'' if row is not None else ' (not in the R/S file)'}")
        print(lines[-1], flush=True)
    with gzip.open(out, "wt", encoding="utf-8") as f:
        json.dump(book, f, separators=(",", ":"))
    open(log_path, "w", encoding="utf-8").write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
