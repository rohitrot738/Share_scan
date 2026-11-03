"""Temporary, run once: photos of each company's own products, from its own website, for the tiles of the app's
products section. Removed before merging.

For each company: its website from its Screener page (one page every six seconds, as the nightly cache reads them),
then the website's home page and its product pages (links naming its products, or "products", "brands", "range"...),
and on them the pictures named for one of its products (alt text, file name, the caption under it, or a page about
that product). Each picture is checked to be a photo, not a logo, icon or text, shrunk to a 128px WebP and stored
in the app (app/src/main/assets/product-photos/SYMBOL/<product>.webp) with its index entry and credit.

    python .github/product-shots.py probe OUT SYMBOL...       a few companies, with contact sheets to look at
    python .github/product-shots.py part PART PARTS ASSETS    one part of the companies in products.json
"""
import html as htmllib
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import urllib.robotparser
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import date

from cr360 import build_app_cache as cache, products

UA = cache.BROWSER_UA
GAP = 6.0  # seconds from one Screener page to the next
STOP_AFTER = 320 * 60  # seconds a part may read, inside its job's six hours
MAX_PAGES = 8
PATTERNS = {p.key: pattern for p, pattern in products._COMPILED}
# Products a photo can show; a bank's, a software house's or a school's are services.
NO_PHOTO = {p.key for p in products.PRODUCTS if p.group in ("finance", "tech")} | {
    "media", "news", "gaming", "education", "staffing", "diagnostics", "hospital", "logistics", "port", "power", "investing"}

PRODUCT_PAGE = re.compile(r"product|brand|range|portfolio|catalog|collection|shop|solution|offering|segment|business|"
                          r"what-we-(?:do|make)|our-(?:work|projects?)|projects?|models?|vehicles?", re.I)
NOT_A_PAGE = re.compile(r"career|job|investor|annual|report|financial|result|news|media|press|blog|event|csr|sustainab|"
                        r"contact|about|login|sign-?in|register|account|cart|privacy|terms|policy|disclaimer|sitemap|faq|"
                        r"download|\.(?:pdf|jpe?g|png|gif|svg|webp|zip|xlsx?|docx?|mp4)\b|mailto:|tel:|javascript:|whatsapp|"
                        r"facebook|linkedin|twitter|youtube|instagram|x\.com|wa\.me|play\.google|apps\.apple|"
                        r"dealer|locator|warranty|support|feedback|grievance|tender|vendor", re.I)
NOT_A_PHOTO = re.compile(r"\b(?:logos?|icons?|favicons?|sprites?|flags?|avatars?|placeholders?|loader|loading|spinner|"
                         r"arrows?|btn|buttons?|social|whatsapp|facebook|twitter|linkedin|youtube|instagram|awards?|"
                         r"certificat\w*|iso|team|directors?|chairman|chairperson|ceo|founders?|people|staff|careers?|"
                         r"employees?|mr|mrs|shri|smt|portrait|signature|maps?|qr|badges?|stamps?|blank|pixel|spacer|1x1|"
                         r"dummy|default|no image|opens|opened|inaugurat\w*|partner\w*|signs|signed|mou|ceremony|"
                         r"conference|summit|meeting|visit\w*|felicitat\w*|celebrat\w*|agm|webinar)\b")
_A = re.compile(r"<a\s([^>]*)>(.*?)</a>", re.I | re.S)
_IMG = re.compile(r"<img\s[^>]*>", re.I)
_SOURCE = re.compile(r"<source\s[^>]*>", re.I)
_BG = re.compile(r"background(?:-image)?\s*:\s*url\(\s*['\"]?([^'\")]+)['\"]?\s*\)", re.I)
_META = re.compile(r"<meta\s[^>]*>", re.I)
_TITLE = re.compile(r"<title[^>]*>(.*?)</title>", re.I | re.S)
_ATTR = re.compile(r'([a-z:_-]+)\s*=\s*"([^"]*)"|([a-z:_-]+)\s*=\s*\'([^\']*)\'', re.I)
_TAG = re.compile(r"<[^>]+>")
_SCREENER_SITE = re.compile(r'<a href="(https?://[^"]+)"[^>]*>\s*<i class="icon-link"></i>')


def log(msg: str) -> None:
    cache.log(msg)


def get(url: str, accept: str = "*/*", limit: int = 2_000_000, timeout: int = 15) -> tuple[str, bytes, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept, "Accept-Language": "en-IN,en;q=0.8"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.geturl(), r.read(limit), r.headers.get("Content-Type", "")


def attrs(tag: str) -> dict:
    return {(m.group(1) or m.group(3)).lower(): htmllib.unescape(m.group(2) if m.group(1) else m.group(4)) for m in _ATTR.finditer(tag)}


def text_of(fragment: str) -> str:
    return re.sub(r"\s+", " ", htmllib.unescape(_TAG.sub(" ", fragment))).strip()


def host(url: str) -> str:
    h = urllib.parse.urlparse(url).netloc.lower().split(":")[0]
    return h[4:] if h.startswith("www.") else h


def same_site(url: str, home: str) -> bool:
    a, b = host(url), host(home)
    return bool(a) and (a == b or a.endswith("." + b) or b.endswith("." + a))


def words(s: str) -> str:
    """Lower-case words of a text, a URL or a file name, for the product patterns."""
    s = urllib.parse.unquote(s)
    s = re.sub(r"([a-z])([A-Z])", r"\1 \2", s)
    return " " + re.sub(r"[^a-z0-9]+", " ", s.lower()).strip() + " "


def keys_in(text: str, keys: list[str]) -> list[str]:
    return [k for k in keys if PATTERNS[k].search(text)]


def screener_site(html: str) -> str:
    m = _SCREENER_SITE.search(html)
    return m.group(1) if m else ""


def pick_srcset(srcset: str, base: str) -> str:
    best, best_w = "", 0
    for part in srcset.split(","):
        bits = part.strip().split()
        if not bits:
            continue
        w = int(bits[1][:-1]) if len(bits) > 1 and bits[1].endswith("w") and bits[1][:-1].isdigit() else 0
        if not best or (best_w > 1000 and w < best_w) or (w <= 1000 and w > best_w):
            best, best_w = bits[0], w
    return urllib.parse.urljoin(base, best) if best else ""


def page_images(url: str, page: str) -> list[dict]:
    """Every picture on a page, with the words around it: its alt and title, its file name and the text after it."""
    out = []

    def add(src: str, at: int, a: dict, kind: str):
        if not src or src.startswith("data:"):
            return
        full = urllib.parse.urljoin(url, src.strip())
        if not full.startswith("http") or re.search(r"\.(?:svg|gif|ico)(?:\?|$)", full, re.I):
            return
        after = text_of(page[at:at + 600])[:120]
        out.append({"url": full, "alt": " ".join(x for x in (a.get("alt", ""), a.get("title", "")) if x), "after": after,
                    "w": int(a["width"]) if a.get("width", "").isdigit() else 0,
                    "h": int(a["height"]) if a.get("height", "").isdigit() else 0, "kind": kind})

    for m in _IMG.finditer(page):
        a = attrs(m.group(0))
        src = ""
        for name in ("data-srcset", "srcset", "data-lazy-srcset"):
            if a.get(name):
                src = pick_srcset(a[name], url)
                break
        src = src or a.get("data-src") or a.get("data-lazy-src") or a.get("data-original") or a.get("data-lazy") or a.get("src", "")
        add(src, m.end(), a, "img")
    for m in _SOURCE.finditer(page):
        a = attrs(m.group(0))
        if a.get("srcset") or a.get("data-srcset"):
            add(pick_srcset(a.get("data-srcset") or a["srcset"], url), m.end(), a, "source")
    for m in _BG.finditer(page):
        add(m.group(1), m.end(), {}, "background")
    for m in _META.finditer(page):
        a = attrs(m.group(0))
        if (a.get("property") or a.get("name", "")).lower() in ("og:image", "twitter:image") and a.get("content"):
            add(a["content"], 0, {"alt": text_of(_TITLE.search(page).group(1)) if _TITLE.search(page) else ""}, "og")
    return out


def page_links(url: str, page: str, keys: list[str]) -> list[tuple[int, str, list[str]]]:
    """The site's own links that may lead to its products, best first: (score, address, products named)."""
    found = {}
    for m in _A.finditer(page):
        a = attrs(m.group(1))
        href = a.get("href", "").strip()
        if not href or href.startswith("#"):
            continue
        full = urllib.parse.urljoin(url, href).split("#")[0]
        if not full.startswith("http") or not same_site(full, url) or NOT_A_PAGE.search(full):
            continue
        label = text_of(m.group(2))[:80]
        if NOT_A_PAGE.search(label):
            continue
        named = keys_in(words(full) + words(label), keys)
        score = 3 * bool(named) + 2 * bool(PRODUCT_PAGE.search(urllib.parse.urlparse(full).path + " " + label))
        if score and (full not in found or found[full][0] < score):
            found[full] = (score, full, named)
    return sorted(found.values(), key=lambda x: (-x[0], len(x[1])))


def static_html(url: str) -> tuple[str, str]:
    final, body, kind = get(url, "text/html")
    if "html" not in kind:
        raise ValueError(kind)
    return final, body.decode("utf-8", "replace")


def robots(home_url: str):
    """The site's robots.txt rules; a site without one, or whose file does not come, allows everything."""
    rules = urllib.robotparser.RobotFileParser()
    try:
        _, body, _ = get(urllib.parse.urljoin(home_url, "/robots.txt"), "text/plain", limit=200_000, timeout=10)
        rules.parse(body.decode("utf-8", "replace").splitlines())
    except Exception:  # noqa: BLE001
        rules.parse([])
    return rules


def crawl(site: str, keys: list[str]) -> tuple[list[dict], dict]:
    """The pictures on a company's home page and product pages, each with the products its words name."""
    info = {"site": site, "pages": [], "error": ""}
    if not site:
        return [], info
    url = site if site.startswith("http") else "https://" + site
    try:
        home_url, home = static_html(url)
    except Exception as e:  # noqa: BLE001 - a site that does not answer, or keeps automated requests out, is left alone
        info["error"] = f"{type(e).__name__}: {str(e)[:60]}"
        return [], info
    rules = robots(home_url)
    pages = [(home_url, home, [])]
    queue = page_links(home_url, home, keys)[:MAX_PAGES - 1]
    seen = {home_url}
    for score, link, named in queue:
        if len(pages) >= MAX_PAGES or link in seen or not rules.can_fetch(UA, link):
            continue
        seen.add(link)
        try:
            final, page = static_html(link)
        except Exception:  # noqa: BLE001
            continue
        pages.append((final, page, named))
        # A products page's own links to each product's page hold the product's pictures.
        if PRODUCT_PAGE.search(urllib.parse.urlparse(final).path) and len(queue) < 3 * MAX_PAGES:
            queue.extend(x for x in page_links(final, page, keys)[:4] if x[1] not in seen and x[2])
    info["pages"] = [p[0] for p in pages]
    info["links"] = len(_A.findall(home))
    info["imgs"] = len(_IMG.findall(home))
    on_pages = Counter()
    found = []
    for page_url, page, named in pages:
        title = text_of(_TITLE.search(page).group(1)) if _TITLE.search(page) else ""
        # A page about a product names it in its address or title; the home page's title names the company.
        page_keys = [] if page_url == home_url else named or keys_in(words(urllib.parse.urlparse(page_url).path) + words(title), keys)
        product_page = page_url != home_url and (bool(page_keys) or bool(PRODUCT_PAGE.search(urllib.parse.urlparse(page_url).path)))
        for im in {i["url"]: i for i in page_images(page_url, page)}.values():
            on_pages[im["url"]] += 1
            im["page"] = page_url
            im["page_keys"] = page_keys
            im["product_page"] = product_page
            found.append(im)
    out = []
    for im in found:
        if on_pages[im["url"]] >= 3:  # the site's own frame: its logo, menus, footer
            continue
        name = urllib.parse.urlparse(im["url"]).path.rsplit("/", 1)[-1]
        if NOT_A_PHOTO.search(words(name) + words(im["alt"])) or (0 < im["w"] < 120) or (0 < im["h"] < 90):
            continue
        by_alt, by_name, by_after = keys_in(words(im["alt"]), keys), keys_in(words(name), keys), keys_in(words(im["after"]), keys)
        scores = {}
        for k in keys:
            s = 3 * (k in by_alt) + 2 * (k in by_name) + (k in by_after) + (k in im["page_keys"])
            if not s and im["product_page"] and len(keys) == 1 and im["kind"] != "og":
                s = 1  # a one-product company's product pages show that product
            if s:
                scores[k] = s + (im["kind"] == "img") + im["product_page"] - (im["kind"] == "og")
        if scores:
            im["scores"] = scores
            out.append(im)
    return out, info


def is_photo(pic) -> bool:
    """A photo, a product on a plain ground among them, spreads over many tones; a logo, an icon or a block of text
    over a few. The tones of the ground (the commonest one) are left out."""
    import math
    from PIL import Image
    rgba = pic.convert("RGBA")
    rgb = Image.new("RGB", rgba.size, (255, 255, 255))
    rgb.paste(rgba, mask=rgba.getchannel("A"))
    tones = rgb.convert("L").resize((96, 96)).histogram()
    ground = max(range(256), key=lambda i: tones[i])
    for i in range(max(0, ground - 6), min(256, ground + 7)):
        tones[i] = 0
    n = sum(tones)
    if n < 96 * 96 * 0.05:
        return False  # all ground
    return -sum(c / n * math.log2(c / n) for c in tones if c) >= 5.0


def cut_out(pic) -> bool:
    """A product shot on a plain light ground (or a transparent one), to be shown whole rather than cropped."""
    rgba = pic.convert("RGBA")
    if sum(1 for a in rgba.getchannel("A").resize((48, 48)).get_flattened_data() if a < 200) > 48 * 48 * 0.1:
        return True
    small = rgba.convert("RGB").resize((48, 48))
    edge = [small.getpixel((x, y)) for x in range(48) for y in (0, 47)] + [small.getpixel((x, y)) for y in range(48) for x in (0, 47)]
    return sum(1 for r, g, b in edge if min(r, g, b) > 220) >= 0.8 * len(edge)


def fetch_photo(url: str):
    from PIL import Image
    try:
        _, data, kind = get(url, "image/*", limit=4_000_000, timeout=20)
        pic = Image.open(io.BytesIO(data))
        pic.load()
    except Exception:  # noqa: BLE001 - a picture that does not come or does not open is left out
        return None
    w, h = pic.size
    if min(w, h) < 150 or not 0.5 <= w / h <= 2.2 or not is_photo(pic):
        return None
    return pic


def store(pic, path: str) -> int:
    from PIL import Image, ImageOps
    if cut_out(pic):
        rgba = pic.convert("RGBA")
        rgba.thumbnail((116, 116), Image.LANCZOS)
        tile = Image.new("RGB", (128, 128), (255, 255, 255))
        tile.paste(rgba, ((128 - rgba.width) // 2, (128 - rgba.height) // 2), rgba)
    else:
        tile = ImageOps.fit(pic.convert("RGB"), (128, 128), Image.LANCZOS)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tile.save(path, "WEBP", quality=55, method=6)
    return os.path.getsize(path)


def company_photos(sym: str, site: str, keys: list[str], folder: str) -> dict:
    """Finds and stores one photo per product the company makes; returns what was found, for the index and review."""
    want = [k for k in keys if k not in NO_PHOTO][:4]
    result = {"sym": sym, "site": site, "keys": want, "photos": {}}
    if not want:
        return result
    cands, info = crawl(site, want)
    result.update({k: v for k, v in info.items() if k != "site"})
    result["candidates"] = len(cands)
    used = set()
    for k in want:
        ranked = sorted((c for c in cands if k in c["scores"] and c["url"] not in used), key=lambda c: -c["scores"][k])
        for c in ranked[:4]:
            pic = fetch_photo(c["url"])
            if pic is None:
                continue
            path = os.path.join(folder, f"{k}.webp")
            store(pic, path)
            used.add(c["url"])
            result["photos"][k] = {"url": c["url"], "alt": c["alt"][:80], "after": c["after"][:80], "page": c["page"],
                                   "score": c["scores"][k], "kind": c["kind"]}
            break
    return result


def safe(symbol: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", symbol)


def read_sites(symbols: list[str], universe: dict, started: float, work) -> list[str]:
    """Reads each company's Screener page in turn for its website and hands it to `work`; the ones not read."""
    last, refusals, missed = 0.0, 0, []
    for i, sym in enumerate(symbols):
        if refusals >= 5 or time.time() - started > STOP_AFTER:
            missed.extend(symbols[i:])
            break
        stock = universe[sym]
        for attempt in range(2):
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
            log(f"  screener refused ({refusals}), waiting {pause}s")
            time.sleep(pause)
        if status == 200:
            refusals = 0
            work(sym, screener_site(html))
        else:
            missed.append(sym)
        if i % 100 == 0:
            log(f"  {i}/{len(symbols)} {sym}: {status}")
    return missed


def sheet(results: list[dict], root: str, path: str) -> None:
    """Contact sheet of the photos found: each with its company, product and the words that named it."""
    from PIL import Image, ImageDraw
    items = [(r["sym"], k, p) for r in results for k, p in r["photos"].items()]
    cols, cw, ch = 6, 150, 170
    rows = max(1, -(-len(items) // cols))
    img = Image.new("RGB", (cols * cw, rows * ch), (28, 24, 44))
    dr = ImageDraw.Draw(img)
    for i, (sym, k, p) in enumerate(items):
        x, y = (i % cols) * cw, (i // cols) * ch
        try:
            pic = Image.open(os.path.join(root, safe(sym), f"{k}.webp")).convert("RGB")
            img.paste(pic, (x + 11, y + 2))
        except Exception:  # noqa: BLE001
            pass
        dr.text((x + 4, y + 132), f"{sym} {k}"[:24], fill=(255, 255, 120))
        dr.text((x + 4, y + 146), (p["alt"] or p["after"] or p["url"].rsplit("/", 1)[-1])[:24], fill=(190, 200, 255))
    img.save(path)


def main():
    mode = sys.argv[1]
    started = time.time()
    universe, _ = cache.load_universe(date.today())
    if mode == "probe":
        out = sys.argv[2]
        made = json.load(open("app/src/main/assets/products.json", encoding="utf-8"))["stocks"]
        symbols = [s for s in sys.argv[3:] if s in universe and s in made]
        results = []
        with ThreadPoolExecutor(6) as pool:
            jobs = []
            read_sites(symbols, universe, started, lambda sym, site: jobs.append(
                pool.submit(company_photos, sym, site, made[sym], os.path.join(out, "photos", safe(sym)))))
            results = [j.result() for j in jobs]
        json.dump(results, open(os.path.join(out, "found.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        for n in range(0, len(results), 24):
            sheet(results[n:n + 24], os.path.join(out, "photos"), os.path.join(out, f"sheet-{n // 24}.png"))
        with_site = [r for r in results if r["site"]]
        log(f"probe: {len(results)} companies, {len(with_site)} with a website, "
            f"{sum(1 for r in with_site if not r.get('error'))} answered, {sum(1 for r in results if r['photos'])} with photos, "
            f"{sum(len(r['photos']) for r in results)} photos of {sum(len(r['keys']) for r in results)} products")
        for r in results:
            log(f"  {r['sym']}: {r['site']} {r.get('error', '')} pages {len(r.get('pages', []))} links {r.get('links')} "
                f"imgs {r.get('imgs')} candidates {r.get('candidates')} -> {sorted(r['photos'])} of {r['keys']}")
    elif mode == "part":
        part, parts, assets = int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
        made = json.load(open(os.path.join(assets, "products.json"), encoding="utf-8"))["stocks"]
        index_path = os.path.join(assets, "product-photos.json")
        index = json.load(open(index_path, encoding="utf-8"))
        rank = {"nse": 0, "sme": 1, "bse": 2}
        symbols = sorted((s for s in made if s in universe and any(k not in NO_PHOTO for k in made[s])),
                         key=lambda s: (rank.get(universe[s].get("market", "nse"), 3), s))
        size = -(-len(symbols) // parts)
        mine = symbols[part * size:(part + 1) * size]
        log(f"part {part}: {len(mine)} of {len(symbols)} companies")
        root = os.path.join(assets, "product-photos")
        staging = os.path.join("product-shots", "photos")
        with ThreadPoolExecutor(8) as pool:
            jobs = []
            missed = read_sites(mine, universe, started, lambda sym, site: jobs.append(
                pool.submit(company_photos, sym, site, made[sym], os.path.join(staging, safe(sym)))))
            results = [j.result() for j in jobs]
        stored = 0
        for r in results:
            entry = index["stocks"].setdefault(r["sym"], {})
            for k, p in r["photos"].items():
                if k in entry.get("p", {}):
                    continue  # a photo already chosen by eye stays
                rel = f"product-photos/{safe(r['sym'])}/{k}.webp"
                os.makedirs(os.path.join(root, safe(r["sym"])), exist_ok=True)
                os.replace(os.path.join(staging, safe(r["sym"]), f"{k}.webp"), os.path.join(assets, rel))
                entry.setdefault("p", {})[k] = rel
                entry.setdefault("c", {})[rel] = f"{host(p['page'])} (company website)"
                stored += 1
            if not entry:
                del index["stocks"][r["sym"]]
        with open(index_path, "w", encoding="utf-8") as f:
            json.dump(index, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
        os.makedirs("product-shots", exist_ok=True)
        with open(os.path.join("product-shots", f"found-{part}.json"), "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False)
        log(f"part {part}: {stored} photos for {sum(1 for r in results if r['photos'])} of {len(mine)} companies; "
            f"{sum(1 for r in results if r['site'])} with a website; missed {len(missed)}: {' '.join(missed[:60])}")


if __name__ == "__main__":
    main()
