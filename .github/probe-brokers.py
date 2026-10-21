# Temporary: checks Groww's search answers the app's own User-Agent. Remove before merging.
import json, urllib.parse, urllib.request

for agent in ["ShareScan (Android)", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP2A.240805.005)"]:
    for q, field in [("RELIANCE", "nse_scrip_code"), ("M&M", "nse_scrip_code"), ("ETERNAL", "nse_scrip_code"), ("539410", "bse_scrip_code")]:
        url = "https://groww.in/v1/api/search/v1/entity?app=false&page=0&size=10&q=" + urllib.parse.quote(q, safe="")
        req = urllib.request.Request(url, headers={"User-Agent": agent, "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                content = json.loads(r.read()).get("content", [])
                hit = [c.get("search_id") for c in content if c.get("entity_type") == "Stocks" and c.get(field) == q]
                print(agent[:20], q, r.status, hit)
        except Exception as e:
            print(agent[:20], q, "ERR", repr(e)[:200])
