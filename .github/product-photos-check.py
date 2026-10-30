"""Temporary: why the picture run found so few pictures, and which sources reach more companies. Removed before merging.

    python .github/product-photos-check.py PROFILES_DIR
"""
import gzip
import importlib.util
import io
import json
import os
import re
import sys
import urllib.parse
from collections import Counter

from cr360 import build_app_cache as cache

spec = importlib.util.spec_from_file_location("pp", ".github/product-photos.py")
pp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pp)


def size_of(data: bytes) -> str:
    from PIL import Image
    try:
        pic = Image.open(io.BytesIO(data))
        return f"{pic.format} {pic.width}x{pic.height}"
    except Exception as e:  # noqa: BLE001
        return f"not a picture ({type(e).__name__})"


def fetch(url, ua=pp.BROWSER_UA, accept="*/*"):
    try:
        final, body = pp.get(url, ua=ua, timeout=20, accept=accept)
        return final, body, ""
    except Exception as e:  # noqa: BLE001
        return "", b"", f"{type(e).__name__}: {str(e)[:80]}"


def sparql(query):
    final, body, err = fetch(pp.WIKIDATA + "?format=json&query=" + urllib.parse.quote(query), ua=pp.UA,
                             accept="application/sparql-results+json")
    if err:
        print("  sparql:", err)
        return []
    return json.loads(body)["results"]["bindings"]


profiles = {}
for name in os.listdir(sys.argv[1]):
    if name.startswith("profiles-") and name.endswith(".json.gz"):
        profiles.update(json.load(gzip.open(os.path.join(sys.argv[1], name), "rt", encoding="utf-8")))
sites = {s: p["site"] for s, p in profiles.items() if p.get("site")}
print(f"== profiles {len(profiles)}, with a website {len(sites)}")
print("  ", list(sites.items())[:12])
print("   hosts:", Counter(urllib.parse.urlparse(u).netloc.split(".")[-1] for u in sites.values()).most_common(8))

print("== wikidata")
for label in ("National Stock Exchange of India", "Bombay Stock Exchange"):
    final, body, err = fetch("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&search="
                             + urllib.parse.quote(label), ua=pp.UA)
    hits = json.loads(body).get("search", [])[:3] if body else []
    print(f"  {label}: {[(h['id'], h.get('label'), h.get('description')) for h in hits]} {err}")
rows = sparql('SELECT (COUNT(DISTINCT ?i) AS ?n) WHERE { ?i wdt:P946 ?isin FILTER(STRSTARTS(?isin, "INE")) }')
print("  items with an INE ISIN:", rows and rows[0]["n"]["value"])
rows = sparql("""SELECT ?ex (COUNT(DISTINCT ?i) AS ?n) (COUNT(DISTINCT ?logo) AS ?logos) (COUNT(DISTINCT ?site) AS ?sites) WHERE {
  VALUES ?ex { wd:Q638550 wd:Q217741 } ?i wdt:P414 ?ex . OPTIONAL { ?i wdt:P154 ?logo } OPTIONAL { ?i wdt:P856 ?site } } GROUP BY ?ex""")
print("  listed on Q638550 / Q217741:", [(r["ex"]["value"][-8:], r["n"]["value"], r["logos"]["value"], r["sites"]["value"]) for r in rows])
rows = sparql("""SELECT ?i ?iLabel ?ticker ?ex WHERE { VALUES ?ex { wd:Q638550 wd:Q217741 } ?i p:P414 ?st . ?st ps:P414 ?ex .
  OPTIONAL { ?st pq:P249 ?ticker } SERVICE wikibase:label { bd:serviceParam wikibase:language "en". } } LIMIT 12""")
print("  sample:", [(r.get("iLabel", {}).get("value"), r.get("ticker", {}).get("value")) for r in rows])
rows = sparql("""SELECT (COUNT(DISTINCT ?i) AS ?n) (COUNT(DISTINCT ?logo) AS ?logos) WHERE { ?i wdt:P17 wd:Q668 ; wdt:P31/wdt:P279* wd:Q4830453 ;
  wdt:P856 ?site . OPTIONAL { ?i wdt:P154 ?logo } }""")
print("  Indian businesses with a website:", rows and (rows[0]["n"]["value"], rows[0]["logos"]["value"]))

print("== screener pages")
screener = cache.Screener()
for sym in ("MRF", "ASIANPAINT", "PIDILITIND"):
    status, html = screener._get(sym + "/")
    print(f"  {sym}: {status}, {len(html)} chars")
    for marker in ("company-links", "icon-link", "company-logos"):
        at = html.find(marker)
        print(f"    {marker}: " + (re.sub(r"\s+", " ", html[max(0, at - 200):at + 500]) if at >= 0 else "none"))
    logo = re.search(r'https://cdn-media\.screener\.in/company-logos/[^"\']+', html)
    if logo:
        for url in (logo.group(0), logo.group(0).replace("/thumbnails/", "/")):
            final, body, err = fetch(url)
            print(f"    logo {url[-60:]}: {size_of(body) if body else err}, {len(body)} bytes")

print("== company websites")
sample = list(sites.items())[::max(1, len(sites) // 25)][:25]
for sym, url in sample:
    final, body, err = fetch(url if url.startswith("http") else "https://" + url, accept="text/html")
    if err:
        print(f"  {sym} {url}: {err}")
        continue
    page = body.decode("utf-8", "replace")[:400_000]
    found = pp.site_pictures(url)
    icons = [pp._attrs(t) for t in pp._LINK.findall(page)]
    icons = [(a.get("rel"), a.get("sizes"), a.get("href", "")[-40:]) for a in icons if "icon" in a.get("rel", "").lower()]
    print(f"  {sym} {url} -> {final[:60]}: {len(body)} bytes, found {list(found)}, icons {icons[:4]}")

print("== google favicons")
for sym, url in sample[:12]:
    host = urllib.parse.urlparse(url if url.startswith("http") else "https://" + url).netloc
    for svc in (f"https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://{host}&size=128",
                f"https://www.google.com/s2/favicons?domain={host}&sz=128"):
        final, body, err = fetch(svc)
        print(f"  {sym} {host} {svc[8:24]}: {size_of(body) if body else err}")

print("== commons, the found logos")
for sym in ("MRF", "ASIANPAINT", "TITAN", "MARUTI", "HINDUNILVR"):
    name = pp.core_name(profiles.get(sym, {}).get("name", ""))
    files = pp.commons(name)
    print(f"  {sym} '{name}': {len(files)} files, e.g. {[f['title'][:50] for f in files[:6]]}")
