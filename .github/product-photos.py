"""Temporary, run once: real pictures for each company's products section, found on the internet.

  - Wikidata, by the company's ISIN: its logo (a Commons file) and official website
  - the company's own website: its icon (apple-touch-icon) and its share picture (og:image), the picture its own
    pages show when linked
  - Wikimedia Commons, searched by the company's name: photos whose title names the company and one of its products
    (cr360/products.py's words), and its logo

Each picture is downloaded, shrunk and stored in the app (app/src/main/assets/product-photos/SYMBOL/*.webp), with an
index of them (product-photos.json); the app needs no internet for them. Commons pictures carry their author and
licence, which the app shows. Removed before merging.

    python .github/product-photos.py probe OUT_DIR SYMBOL...       a few companies, with a contact sheet to look at
    python .github/product-photos.py all PROFILES_DIR ASSETS_DIR   every company read by product-data.py
"""
import gzip
import html
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date

from cr360 import build_app_cache as cache, products

UA = "ShareScan/1.2 (https://github.com/rohitrot738/Share_scan; product pictures for the app)"
BROWSER_UA = cache.BROWSER_UA
COMMONS = "https://commons.wikimedia.org/w/api.php"
WIKIDATA = "https://query.wikidata.org/sparql"
LEGAL = re.compile(r"\b(?:limited|ltd\.?|pvt\.?|private|(?:the )?company|co\.?|corporation|corp\.?|inc\.?|plc|\(india\)|india)\s*$", re.I)
# A Commons photo whose title says it shows people, an event, a building or a chart is not a product's photo.
NOT_PRODUCT = re.compile(r"\b(?:mr|mrs|shri|smt|dr|ceo|chairman|chairperson|director|minister|president|founder|portrait|meeting|"
                         r"meets|inaugurat\w*|ceremony|award\w*|conference|summit|visit\w*|signing|signs|launch\w*|event|"
                         r"office|offices|headquarters|hq|building|tower|campus|house|bhawan|bhavan|map|chart|graph|share|"
                         r"certificate|stamp|cheque|team|staff|employees|workers|protest|strike|rally|school|hospital|people)\b")
WEAK = {"the", "india", "indian", "industries", "industry", "enterprises", "international", "global", "national", "bharat",
        "hindustan", "united", "new", "general", "shree", "shri", "sri", "and", "of", "group", "holdings", "ventures"}


def get(url, ua=UA, timeout=20, accept="*/*"):
    req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept": accept, "Accept-Language": "en-IN,en;q=0.8"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.geturl(), r.read(1_500_000)


def core_name(name: str) -> str:
    """"Apollo Tyres Ltd" -> "Apollo Tyres", "Maruti Suzuki India Limited" -> "Maruti Suzuki"."""
    name = re.sub(r"\s+", " ", name.replace("&", " and ")).strip(" .")
    for _ in range(3):
        name = LEGAL.sub("", name).strip(" .,-")
    return name


def key_phrase(name: str) -> str:
    """What a picture's title must carry to be this company's: its whole name, as words ("apollo tyres"), so that
    a Tata Motors truck is not Tata Steel's and any electronics is not Bharat Electronics'."""
    words = re.findall(r"[a-z0-9]+", name.lower())
    if words and words[0] == "the":
        words = words[1:]
    return " ".join(words) if any(len(w) >= 3 and w not in WEAK for w in words) else ""


def wikidata(isins: list[str]) -> dict[str, dict]:
    """Logo file and website of each company Wikidata knows by ISIN."""
    out = {}
    for i in range(0, len(isins), 250):
        values = " ".join(f'"{x}"' for x in isins[i:i + 250] if re.fullmatch(r"[A-Z0-9]{12}", x))
        query = f"""SELECT ?isin ?logo ?site WHERE {{ VALUES ?isin {{ {values} }} ?item wdt:P946 ?isin .
                    OPTIONAL {{ ?item wdt:P154 ?logo }} OPTIONAL {{ ?item wdt:P856 ?site }} }}"""
        try:
            _, body = get(WIKIDATA + "?format=json&query=" + urllib.parse.quote(query), accept="application/sparql-results+json", timeout=60)
            for row in json.loads(body)["results"]["bindings"]:
                d = out.setdefault(row["isin"]["value"], {})
                if "logo" in row and "logo" not in d:
                    d["logo"] = row["logo"]["value"].replace("http://", "https://") + "?width=256"
                if "site" in row and "site" not in d:
                    d["site"] = row["site"]["value"]
        except Exception as e:  # noqa: BLE001 - a batch that fails leaves those companies to the other sources
            cache.log(f"wikidata batch {i}: {e}")
        time.sleep(1)
    return out


_META = re.compile(r"<meta\s[^>]*>", re.I)
_LINK = re.compile(r"<link\s[^>]*>", re.I)
_ATTR = re.compile(r'([a-z:-]+)\s*=\s*"([^"]*)"|([a-z:-]+)\s*=\s*\'([^\']*)\'', re.I)


def _attrs(tag: str) -> dict:
    return {(m.group(1) or m.group(3)).lower(): html.unescape(m.group(2) if m.group(1) else m.group(4)) for m in _ATTR.finditer(tag)}


def site_pictures(url: str) -> dict:
    """The website's share picture and its largest icon, as absolute addresses; what is not found is left out."""
    if not url:
        return {}
    if not url.startswith("http"):
        url = "https://" + url
    try:
        final, body = get(url, ua=BROWSER_UA, timeout=15, accept="text/html")
    except Exception:  # noqa: BLE001 - a site that does not answer has no pictures
        return {}
    page = body.decode("utf-8", "replace")[:400_000]
    out, best = {}, (0, "")
    for tag in _META.findall(page):
        a = _attrs(tag)
        if (a.get("property") or a.get("name", "")).lower() in ("og:image", "og:image:url", "twitter:image") and a.get("content"):
            out.setdefault("banner", urllib.parse.urljoin(final, a["content"].strip()))
    for tag in _LINK.findall(page):
        a = _attrs(tag)
        rel, href = a.get("rel", "").lower(), a.get("href", "").strip()
        if not href or "icon" not in rel or href.lower().endswith(".svg"):
            continue
        size = max([int(n) for n in re.findall(r"(\d+)x\d+", a.get("sizes", ""))] or [180 if "apple" in rel else 32])
        if size > best[0]:
            best = (size, urllib.parse.urljoin(final, href))
    if best[0] >= 96:
        out["icon"] = best[1]
    return {k: v for k, v in out.items() if v.startswith("https://") or v.startswith("http://")}


def commons(name: str) -> list[dict]:
    """Commons pictures found by the company's name: title, thumbnail, mime, licence and author."""
    params = {"action": "query", "format": "json", "generator": "search", "gsrnamespace": "6", "gsrlimit": "40",
              "gsrsearch": f'"{name}"', "prop": "imageinfo", "iiprop": "url|mime|extmetadata", "iiurlwidth": "320",
              "iiextmetadatafilter": "LicenseShortName|Artist|ObjectName|ImageDescription", "maxlag": "5"}
    try:
        _, body = get(COMMONS + "?" + urllib.parse.urlencode(params), timeout=30)
    except Exception as e:  # noqa: BLE001
        cache.log(f"commons {name}: {e}")
        return []
    files = []
    for page in (json.loads(body).get("query", {}).get("pages", {}) or {}).values():
        info = (page.get("imageinfo") or [{}])[0]
        meta = info.get("extmetadata", {})
        text = lambda k: re.sub(r"<[^>]+>", "", html.unescape(meta.get(k, {}).get("value", "")))[:300]
        if info.get("mime") not in ("image/jpeg", "image/png", "image/svg+xml", "image/webp") or not info.get("thumburl"):
            continue
        files.append({"title": page["title"][5:], "thumb": info["thumburl"], "mime": info["mime"], "licence": text("LicenseShortName"),
                      "author": text("Artist"), "about": text("ImageDescription") + " " + text("ObjectName")})
    return files


def match(name: str, made: list[str], files: list[dict]) -> dict:
    """The company's logo and a photo for each product it makes, from Commons pictures whose title names it."""
    phrase = key_phrase(core_name(name))
    out, credits = {"p": {}}, {}
    if not phrase:
        return out
    for f in files:
        title = " ".join(re.findall(r"[a-z0-9]+", f["title"].rsplit(".", 1)[0].lower()))
        if not re.search(rf"\b{re.escape(phrase)}\b", title):
            continue
        credit = " · ".join(x for x in (f["author"].strip(), f["licence"].strip(), "Wikimedia Commons") if x)
        if "logo" in title and "logo" not in out:
            out["logo"], credits[f["thumb"]] = f["thumb"], credit
            continue
        if f["mime"] == "image/svg+xml" or NOT_PRODUCT.search(title):
            continue
        # The product must be named in the title itself; a description often names it in passing.
        words = " " + title + " "
        for key in made:
            if key in out["p"]:
                continue
            pattern = next(p for prod, p in products._COMPILED if prod.key == key)
            if pattern.search(words):
                out["p"][key], credits[f["thumb"]] = f["thumb"], credit
                break
    if credits:
        out["c"] = credits
    return out


def pictures(symbol: str, stock: dict, made: list[str], known: dict) -> dict:
    """Every picture found for one company."""
    name = stock.get("name", "")
    wd = known.get(stock.get("isin", ""), {})
    found = match(name, made, commons(core_name(name))) if name else {"p": {}}
    site = site_pictures(stock.get("site") or wd.get("site", ""))
    if wd.get("logo"):
        found["logo"] = wd["logo"]
        found.setdefault("c", {})[wd["logo"]] = "Wikimedia Commons"
    elif "logo" not in found and site.get("icon"):
        found["logo"] = site["icon"]
    if site.get("banner"):
        found["banner"] = site["banner"]
    return {k: v for k, v in found.items() if v}


def run(symbols: dict[str, dict], made: dict[str, list[str]]) -> dict:
    known = wikidata(sorted({s.get("isin", "") for s in symbols.values()}))
    cache.log(f"wikidata: {len(known)} companies, {sum(1 for d in known.values() if 'logo' in d)} logos")
    out = {}

    def one(sym):
        time.sleep(0.2)
        return sym, pictures(sym, symbols[sym], made.get(sym, []), known)

    # Commons is asked one company at a time from each of two workers; websites are all different hosts.
    with ThreadPoolExecutor(2) as pool:
        for i, (sym, found) in enumerate(pool.map(one, sorted(symbols))):
            if found:
                out[sym] = found
            if i % 200 == 0:
                cache.log(f"  {i}/{len(symbols)} {sym}: {found}")
    return out


# How each kind of picture is stored: the box it is shrunk into, whether it is cropped to fill it, and its quality.
# As small as still shows the company plainly, so the app stays light.
SHAPES = {"logo": ((96, 96), False, 65), "banner": ((400, 210), True, 40), "photo": ((120, 120), True, 55)}


def store(url: str, kind: str, path: str) -> bool:
    """Downloads [url] and writes it as a small WebP at [path]; false when it is not a usable picture."""
    from PIL import Image, ImageOps
    try:
        _, data = get(url, ua=BROWSER_UA if "wikimedia" not in url else UA, timeout=25)
        pic = Image.open(io.BytesIO(data))
        pic.load()
    except Exception:  # noqa: BLE001 - a picture that does not come or does not open is left out
        return False
    (w, h), crop, quality = SHAPES[kind]
    if pic.width < 32 or pic.height < 32:
        return False
    pic = pic.convert("RGBA")
    pic = ImageOps.fit(pic, (w, h), Image.LANCZOS) if crop else ImageOps.contain(pic, (w, h), Image.LANCZOS)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pic.save(path, "WEBP", quality=quality, method=6)
    return True


def safe(symbol: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", symbol)


def download_all(found: dict, assets: str) -> dict:
    """Every picture found, stored under [assets]/product-photos; the index names the stored files instead."""
    jobs = []
    for sym, f in found.items():
        folder = f"product-photos/{safe(sym)}"
        for kind in ("logo", "banner"):
            if f.get(kind):
                jobs.append((sym, kind, None, f[kind], f"{folder}/{kind}.webp"))
        for key, url in f.get("p", {}).items():
            jobs.append((sym, "photo", key, url, f"{folder}/{key}.webp"))

    def one(job):
        sym, kind, key, url, rel = job
        return job, store(url, kind, os.path.join(assets, rel))

    index = {}
    with ThreadPoolExecutor(6) as pool:
        for (sym, kind, key, url, rel), ok in pool.map(one, jobs):
            if not ok:
                continue
            entry = index.setdefault(sym, {})
            credit = found[sym].get("c", {}).get(url)
            if key:
                entry.setdefault("p", {})[key] = rel
            else:
                entry[kind] = rel
            if credit:
                entry.setdefault("c", {})[rel] = credit
    return index


def sheet(found: dict, assets: str, path: str) -> None:
    """A contact sheet of what was found, to look at: one row per company (logo, banner, products)."""
    from PIL import Image, ImageDraw
    rows = list(found.items())[:70]
    cell = 120
    img = Image.new("RGB", (cell * 7 + 160, cell * len(rows)), (24, 20, 40))
    draw = ImageDraw.Draw(img)
    for r, (sym, f) in enumerate(rows):
        draw.text((4, r * cell + 4), sym, fill=(255, 255, 255))
        files = [("logo", f.get("logo")), ("banner", f.get("banner"))] + list(f.get("p", {}).items())
        for c, (label, rel) in enumerate(files[:7]):
            if not rel:
                continue
            x, y = 160 + c * cell, r * cell
            try:
                pic = Image.open(os.path.join(assets, rel)).convert("RGBA")
                pic.thumbnail((cell - 8, cell - 22))
                img.paste(pic, (x + 4, y + 4), pic)
            except Exception:  # noqa: BLE001
                draw.text((x + 4, y + 40), "x", fill=(255, 80, 80))
            draw.text((x + 4, y + cell - 16), label[:16], fill=(200, 200, 255))
    img.save(path)


def main():
    mode = sys.argv[1]
    if mode == "probe":
        out_dir, wanted = sys.argv[2], sys.argv[3:]
        universe, _ = cache.load_universe(date.today())
        profiles = json.load(open("ui-profiles/profiles.json", encoding="utf-8"))
        symbols = {s: universe[s] for s in wanted if s in universe}
        made = {s: products.classify(profiles.get(s, {}).get("about", ""), profiles.get(s, {}).get("points", ""),
                                     profiles.get(s, {}).get("sector", "")) for s in symbols}
        found = run(symbols, made)
        os.makedirs(out_dir, exist_ok=True)
        json.dump(found, open(f"{out_dir}/photos-found.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        stored = download_all(found, f"{out_dir}/assets")
        json.dump(stored, open(f"{out_dir}/photos.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        sheet(stored, f"{out_dir}/assets", f"{out_dir}/photos-sheet.png")
        size = sum(os.path.getsize(os.path.join(d, n)) for d, _, ns in os.walk(f"{out_dir}/assets") for n in ns)
        cache.log(f"{len(stored)} companies, {size // 1024} KB of pictures")
    else:
        profiles_dir, assets = sys.argv[2], sys.argv[3]
        universe, _ = cache.load_universe(date.today())
        symbols, made = {}, {}
        for name in os.listdir(profiles_dir):
            if name.startswith("profiles-") and name.endswith(".json.gz"):
                for sym, p in json.load(gzip.open(os.path.join(profiles_dir, name), "rt", encoding="utf-8")).items():
                    symbols[sym] = {**universe.get(sym, {}), "name": p.get("name") or universe.get(sym, {}).get("name", ""), "site": p.get("site", "")}
                    made[sym] = products.classify(p.get("about", ""), p.get("points", ""), p.get("sector", ""))
        found = run(symbols, made)
        stored = download_all(found, assets)
        with open(os.path.join(assets, "product-photos.json"), "w", encoding="utf-8") as f:
            json.dump({"v": 1, "stocks": stored}, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
        size = sum(os.path.getsize(os.path.join(d, n)) for d, _, ns in os.walk(os.path.join(assets, "product-photos")) for n in ns)
        cache.log(f"{len(stored)} companies with pictures, {sum(1 for v in stored.values() if v.get('p'))} with product photos, "
                  f"{size // 1024} KB")


if __name__ == "__main__":
    main()
