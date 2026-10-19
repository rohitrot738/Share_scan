"""Temporary: runs the app's support-floor rules (SupportFloor.kt) on real NSE charts and reports what they find.
Remove before merging."""
import csv, gzip, io, json, random, statistics, sys, time, urllib.request

UA = "ShareScan/1.0 (Android)"
LONG_WINDOW, LONG_AGE, GAP, SPAN = 115, 63, 5, 3


def get(url, headers=None, raw=False):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "gzip", **(headers or {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read()
        size = len(body)
        if r.headers.get("Content-Encoding") == "gzip":
            body = gzip.decompress(body)
        return (body, size) if raw else body.decode("utf-8-sig", "replace")


def find(close, low, zone_pct):
    n = min(len(close), len(low))
    if n < 10:
        return None
    z = zone_pct / 100
    best, tried = None, set()
    for p in range(n):
        lo = low[p]
        if any(low[j] < lo for j in range(max(0, p - SPAN), min(n, p + SPAN + 1))):
            continue
        if lo <= 0 or lo in tried:
            continue
        tried.add(lo)
        bottom, top = lo * (1 - z), lo * (1 + z)
        last_break = -1
        for i in range(n):
            if close[i] < bottom:
                last_break = i
        if last_break == n - 1:
            continue
        touches, first, last_touch, in_zone, pierces, deepest = 0, -1, -10**9, False, 0, lo
        for i in range(last_break + 1, n):
            if low[i] < bottom:
                pierces += 1
            elif low[i] < deepest:
                deepest = low[i]
            if low[i] <= top:
                if not in_zone and i - last_touch >= GAP:
                    touches += 1
                    last_touch = i
                    if first < 0:
                        first = i
                in_zone = True
            else:
                in_zone = False
        if touches < 2:
            continue
        cand = dict(level=deepest, age=n - first, touches=touches, unbroken=last_break < 0, window=n)
        key = (cand["age"], -pierces, touches, deepest)
        if best is None or key > best[0]:
            best = (key, cand)
    return best[1] if best else None


def analyse(symbol):
    body, size = get(f"https://query1.finance.yahoo.com/v8/finance/chart/{symbol}.NS?range=6mo&interval=1d", raw=True)
    chart = json.loads(body)["chart"]["result"][0]
    q = chart["indicators"]["quote"][0]
    rows = [(c, h, l) for c, h, l in zip(q["close"], q["high"], q["low"]) if None not in (c, h, l) and c > 0 and l > 0]
    close = [r[0] for r in rows]
    high = [max(r) for r in rows]
    low = [min(r) for r in rows]
    n = len(close)
    if n < 21:
        return None, size
    tr = [high[0] - low[0]] + [max(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])) for i in range(1, n)]
    frm = max(1, n - 50)
    swing = max(sum(tr[i] / close[i] * 100 for i in range(frm, n)) / (n - frm), 0.25)
    zone = min(max(swing / 2, 1.0), 3.0)
    floor = find(close, low, zone)
    if floor:
        floor.update(price=close[-1], zone=round(zone, 2), symbol=symbol,
                     gap=round((close[-1] / floor["level"] - 1) * 100, 1),
                     long=floor["unbroken"] and n >= LONG_WINDOW and floor["age"] >= LONG_AGE)
    return floor, size


def main():
    text = get("https://archives.nseindia.com/content/equities/EQUITY_L.csv")
    symbols = [r["SYMBOL"].strip() for r in csv.DictReader(io.StringIO(text)) if r.get(" SERIES", r.get("SERIES", "")).strip() == "EQ"]
    random.seed(19)
    sample = random.sample(symbols, min(300, len(symbols)))
    floors, sizes, failed = [], [], 0
    for i, s in enumerate(sample):
        try:
            floor, size = analyse(s)
            sizes.append(size)
            if floor:
                floors.append(floor)
        except Exception as e:
            failed += 1
        time.sleep(0.15)
    print(f"stocks {len(sample)}, charts {len(sizes)}, failed {failed}")
    print(f"6mo payload: median {statistics.median(sizes)/1024:.1f} KB on the wire, total {sum(sizes)/1e6:.1f} MB for {len(sizes)}")
    print(f"with a floor: {len(floors)}; age >= {LONG_AGE} (card line): {sum(f['age'] >= LONG_AGE for f in floors)}; long (chip): {sum(f['long'] for f in floors)}")
    longs = [f for f in floors if f["long"]]
    if longs:
        print("long gap %: median", statistics.median(f["gap"] for f in longs), "max", max(f["gap"] for f in longs))
        print("long touches: median", statistics.median(f["touches"] for f in longs))
    print("ages:", sorted(f["age"] for f in floors))
    for f in sorted(longs, key=lambda f: f["gap"])[:25]:
        print(f)
    for f in random.sample([f for f in floors if not f["long"]], min(10, len(floors) - len(longs))):
        print("short", f)


if __name__ == "__main__":
    main()
