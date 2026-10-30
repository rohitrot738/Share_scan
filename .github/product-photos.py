"""Temporary, run once: real pictures for each company's products section, found on the internet.

  - the company's Screener page: its logo (the 96px picture by its name) and its website (the link under its name)
  - Wikidata, by the company's ISIN or its NSE/BSE ticker: its logo (a Commons file) and official website, where
    Screener has none
  - the company's own website: its share picture (og:image), the picture its own pages show when linked, and its
    icon (apple-touch-icon) when no logo is found elsewhere
  - Wikimedia Commons, searched by the company's name: photos whose title names the company and one of its products
    (cr360/products.py's words)

Each picture is downloaded, shrunk and stored in the app (app/src/main/assets/product-photos/SYMBOL/*.webp), with an
index of them (product-photos.json); the app needs no internet for them. Commons pictures carry their author and
licence, which the app shows. Removed before merging.

    python .github/product-photos.py probe OUT_DIR SYMBOL...        a few companies, with a contact sheet to look at
    python .github/product-photos.py screener PART PARTS ASSETS_DIR  one part of the companies in products.json, read
                                                                     from Screener one page every six seconds
"""
import gzip
import html
import io
import json
import os
import re
import shutil
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
# Products a photo can show: things, not services. A bank, a software company or a hospital is shown by its logo.
PHYSICAL_GROUPS = {"auto", "materials", "clothing", "electrical", "food", "home", "energy", "industry"}
PHYSICAL_KEYS = {"aircraft", "ship", "rail", "defence", "api", "medicine", "vaccine", "meddevice", "ayurveda", "news", "gaming", "toys"}
PHYSICAL = {p.key for p in products.PRODUCTS if p.group in PHYSICAL_GROUPS or p.key in PHYSICAL_KEYS} - {"realty", "water", "recycling"}
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


NSE, BSE = "Q638740", "Q638398"  # Wikidata's National Stock Exchange of India and Bombay Stock Exchange


def wikidata_listed() -> dict[str, dict]:
    """Logo file and website of each company Wikidata lists on NSE or BSE, by its ticker there (symbol or code)."""
    query = f"""SELECT ?ticker ?logo ?site WHERE {{ VALUES ?ex {{ wd:{NSE} wd:{BSE} }} ?item p:P414 ?st . ?st ps:P414 ?ex ;
                pq:P249 ?ticker . OPTIONAL {{ ?item wdt:P154 ?logo }} OPTIONAL {{ ?item wdt:P856 ?site }} }}"""
    out = {}
    try:
        _, body = get(WIKIDATA + "?format=json&query=" + urllib.parse.quote(query), accept="application/sparql-results+json", timeout=90)
        for row in json.loads(body)["results"]["bindings"]:
            d = out.setdefault(row["ticker"]["value"].strip().upper(), {})
            if "logo" in row and "logo" not in d:
                d["logo"] = row["logo"]["value"].replace("http://", "https://") + "?width=256"
            if "site" in row and "site" not in d:
                d["site"] = row["site"]["value"]
    except Exception as e:  # noqa: BLE001 - without it the other sources still serve
        cache.log(f"wikidata listed: {e}")
    return out


# On a Screener page: the company's logo in its heading, and its website, the link with the link icon under its name.
_SCREENER_LOGO = re.compile(r"https://cdn-media\.screener\.in/company-logos/thumbnails/[0-9A-Za-z_-]+\.(?:webp|png|jpe?g)")
_SCREENER_SITE = re.compile(r'<a href="(https?://[^"]+)"[^>]*>\s*<i class="icon-link"></i>')


def screener_links(html: str) -> tuple[str, str]:
    """The logo and website a Screener page shows for its company; empty when it shows none."""
    end = html.find("</h1>")
    logo = _SCREENER_LOGO.search(html[:end]) if end > 0 else None
    site = _SCREENER_SITE.search(html)
    return (logo.group(0) if logo else ""), (site.group(1) if site else "")


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
        # A product photo is a photograph: drawings and PNGs are mostly logos and charts.
        if f["mime"] != "image/jpeg" or NOT_PRODUCT.search(title):
            continue
        # The product must be named in the title itself; a description often names it in passing.
        words = " " + title + " "
        for key in made:
            if key in out["p"] or key not in PHYSICAL:
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
SHAPES = {"logo": ((96, 96), False, 55), "banner": ((360, 189), True, 35), "photo": ((120, 120), True, 55)}
SMALLEST = {"logo": 32, "banner": 300, "photo": 100}  # narrower pictures would only blur


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
    if pic.width < SMALLEST[kind] or min(pic.width, pic.height) < 32:
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


GAP = 6.0  # seconds from one Screener page to the next, as the nightly cache reads them
STOP_AFTER = 320 * 60  # seconds a part may read, inside its job's six hours


def finish(sym: str, stock: dict, made: list[str], logo: str, site: str, wd: dict, assets: str) -> dict:
    """Stores one company's pictures under [assets]; returns its index entry, empty when none was found."""
    site = site or wd.get("site", "")
    name = stock.get("name", "")
    found = match(name, made, commons(core_name(name))) if name else {"p": {}}
    pics = site_pictures(site)
    folder = f"product-photos/{safe(sym)}"
    entry, credits = {}, {}
    logos = ((logo, "Screener.in"), (wd.get("logo"), "Wikimedia Commons"), (pics.get("icon"), ""),
             (found.get("logo"), found.get("c", {}).get(found.get("logo", ""), "")))
    for url, credit in logos:
        if url and store(url, "logo", os.path.join(assets, f"{folder}/logo.webp")):
            entry["logo"] = f"{folder}/logo.webp"
            if credit:
                credits[entry["logo"]] = credit
            break
    if pics.get("banner") and store(pics["banner"], "banner", os.path.join(assets, f"{folder}/banner.webp")):
        entry["banner"] = f"{folder}/banner.webp"
    for key, url in found.get("p", {}).items():
        rel = f"{folder}/{key}.webp"
        if store(url, "photo", os.path.join(assets, rel)):
            entry.setdefault("p", {})[key] = rel
            credits[rel] = found["c"][url]
    if credits:
        entry["c"] = credits
    return entry


def screener_part(part: int, parts: int, assets: str) -> None:
    """Pictures for one part of the companies the app knows products of: NSE first, then SME, then BSE. Part 0
    starts the pictures afresh; the later parts add to them."""
    started = time.time()
    universe, _ = cache.load_universe(date.today())
    made = json.load(open(os.path.join(assets, "products.json"), encoding="utf-8"))["stocks"]
    rank = {"nse": 0, "sme": 1, "bse": 2}
    symbols = sorted((s for s in made if s in universe), key=lambda s: (rank.get(universe[s].get("market", "nse"), 3), s))
    size = -(-len(symbols) // parts)
    mine = symbols[part * size:(part + 1) * size]
    index_path = os.path.join(assets, "product-photos.json")
    if part == 0:
        shutil.rmtree(os.path.join(assets, "product-photos"), ignore_errors=True)
        index = {}
    else:
        index = json.load(open(index_path, encoding="utf-8"))["stocks"]
    known = wikidata(sorted({universe[s].get("isin", "") for s in mine}))
    listed = wikidata_listed()
    cache.log(f"part {part}: {len(mine)} of {len(symbols)} companies; wikidata {len(known)} by ISIN, {len(listed)} by ticker")
    jobs, missed, last, refusals = {}, [], 0.0, 0
    with ThreadPoolExecutor(4) as pool:
        for i, sym in enumerate(mine):
            if refusals >= 5 or time.time() - started > STOP_AFTER:
                missed.extend(mine[i:])
                break
            stock = universe[sym]
            for attempt in range(2):  # a refusal waits, then asks once more
                wait = GAP - (time.time() - last)
                if wait > 0:
                    time.sleep(wait)
                last = time.time()
                status, html, headers = cache.http_get("https://www.screener.in/company/"
                                                       + urllib.parse.quote(cache.screener_slug(sym, stock)) + "/")
                if status != 429:
                    break
                refusals += 1
                pause = min(600, int(headers.get("Retry-After") or 0) or 60 * refusals)
                cache.log(f"  screener refused ({refusals}), waiting {pause}s")
                time.sleep(pause)
            if status == 200:
                refusals = 0
            logo, site = screener_links(html) if status == 200 else ("", "")
            if status != 200:
                missed.append(sym)
            wd = known.get(stock.get("isin", "")) or listed.get(sym.upper()) or listed.get(str(stock.get("code", "")).upper()) or {}
            jobs[sym] = pool.submit(finish, sym, stock, made[sym], logo, site, wd, assets)
            if i % 100 == 0:
                cache.log(f"  {i}/{len(mine)} {sym}: {status} logo {bool(logo)} site {site}")
        for sym, job in jobs.items():
            entry = job.result()
            if entry:
                index[sym] = entry
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump({"v": 1, "stocks": dict(sorted(index.items()))}, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    mine_found = [index[s] for s in mine if s in index]
    size_kb = sum(os.path.getsize(os.path.join(d, n)) for d, _, ns in os.walk(os.path.join(assets, "product-photos")) for n in ns) // 1024
    cache.log(f"part {part}: {len(mine_found)} of {len(mine)} with pictures - logos {sum('logo' in e for e in mine_found)}, "
              f"banners {sum('banner' in e for e in mine_found)}, photos {sum(len(e.get('p', {})) for e in mine_found)}; "
              f"missed {len(missed)}: {' '.join(missed[:60])}; all pictures {size_kb} KB")


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
    elif mode == "screener":
        screener_part(int(sys.argv[2]), int(sys.argv[3]), sys.argv[4])


if __name__ == "__main__":
    main()
