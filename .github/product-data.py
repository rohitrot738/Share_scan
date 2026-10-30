"""Temporary, run once: reads every company's About and Key Points from its Screener page and writes what it makes,
for the products file the app carries (app/src/main/assets/products.json). Products hardly change, so the app ships
them rather than waiting for the nightly cache to read every page again. Removed before merging.

    python .github/product-data.py read SLICE COUNT OUT_DIR      one slice of the stocks, at the nightly cache's pace
    python .github/product-data.py retry IN_DIR OUT_DIR          the stocks the slices in IN_DIR missed, as slice "retry"
    python .github/product-data.py join IN_DIR ASSET             every slice into the app's file
"""
import gzip
import json
import os
import re
import sys
import time
import urllib.parse
from datetime import date, datetime, timedelta, timezone

from cr360 import build_app_cache as cache, products, screener_page

STOP_AFTER = 335 * 60  # seconds a slice may read, inside its job's 6 hours
_LINKS = re.compile(r'<div class="company-links[^"]*">(.*?)</div>', re.S)
_HREF = re.compile(r'href="(https?://[^"]+)"')


def website(html: str) -> str:
    """The company's own website from the links under its name on the Screener page; empty when it shows none."""
    block = _LINKS.search(html)
    for url in _HREF.findall(block.group(1) if block else ""):
        if not re.search(r"bseindia|nseindia|screener\.in", url):
            return url
    return ""


def read(part, count: int, out_dir: str, only: list[str] | None = None) -> None:
    started = time.time()
    universe, markets = cache.load_universe(date.today())
    sectors = cache.load_sectors(universe)
    symbols = sorted(universe)
    if only is not None:
        mine = [s for s in only if s in universe]
    else:
        size = -(-len(symbols) // count)
        mine = symbols[part * size:(part + 1) * size]
    cache.log(f"slice {part}: {len(mine)} of {len(symbols)} stocks ({', '.join(sorted(markets))})")
    screener = cache.Screener()
    made, profiles, missed = {}, {}, []
    for i, symbol in enumerate(mine):
        if screener.blocked or time.time() - started > STOP_AFTER:
            missed.extend(mine[i:])
            break
        stock = universe[symbol]
        status, html = screener._get(urllib.parse.quote(cache.screener_slug(symbol, stock)) + "/")
        if status != 200:
            missed.append(symbol)
            continue
        page = screener_page.parse(html)
        about, points, sector = page.get("about", ""), page.get("points", ""), sectors.get(symbol, "")
        profiles[symbol] = {"name": stock.get("name", ""), "site": website(html), "about": about, "points": points, "sector": sector}
        made[symbol] = products.classify(about, points, sector)
        if i % 100 == 0:
            cache.log(f"  {i}/{len(mine)} {symbol}: {made[symbol]}")
    os.makedirs(out_dir, exist_ok=True)
    json.dump({"made": made, "missed": missed}, open(f"{out_dir}/products-{part}.json", "w"))
    with gzip.open(f"{out_dir}/profiles-{part}.json.gz", "wt", encoding="utf-8") as f:
        json.dump(profiles, f, ensure_ascii=False)
    cache.log(f"read {len(made)}, with products {sum(1 for v in made.values() if v)}, missed {len(missed)}")


def join(in_dir: str, asset: str) -> None:
    made, missed = {}, []
    for name in sorted(os.listdir(in_dir)):
        if name.startswith("products-") and name.endswith(".json"):
            part = json.load(open(os.path.join(in_dir, name)))
            made.update(part["made"])
            missed.extend(part["missed"])
    missed = [s for s in dict.fromkeys(missed) if s not in made]
    if len(made) < 1000:
        raise SystemExit(f"only {len(made)} companies read; the app's file is left as it is")
    stocks = {s: keys for s, keys in sorted(made.items()) if keys}
    ist = datetime.now(timezone(timedelta(hours=5, minutes=30)))
    with open(asset, "w", encoding="utf-8") as f:
        json.dump({"v": 1, "updated": ist.strftime("%Y-%m-%d"), "stocks": stocks}, f, separators=(",", ":"))
        f.write("\n")
    print(f"{len(made)} read, {len(stocks)} with products, {len(missed)} missed: {' '.join(missed[:200])}")


def missed(in_dir: str) -> list[str]:
    out = []
    for name in sorted(os.listdir(in_dir)):
        if name.startswith("products-") and name.endswith(".json"):
            out.extend(json.load(open(os.path.join(in_dir, name)))["missed"])
    return out


if __name__ == "__main__":
    if sys.argv[1] == "read":
        read(int(sys.argv[2]), int(sys.argv[3]), sys.argv[4])
    elif sys.argv[1] == "retry":
        read("retry", 1, sys.argv[3], only=missed(sys.argv[2]))
    else:
        join(sys.argv[2], sys.argv[3])
