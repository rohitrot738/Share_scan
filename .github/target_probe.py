"""Temporary: checks the app's target-time estimate (TargetTimes.kt, TradePlan.kt) on real NSE charts.
Remove before merging."""
import csv, gzip, io, json, random, statistics, time, urllib.request

UA = "ShareScan/1.0 (Android)"
MAX_ATRS, MIN_STARTS, ATR_N = 8, 40, 14
TARGET_R = (1.5, 2.5, 4.0)
HIST, AHEAD = 124, 60


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            body = gzip.decompress(body)
        return body.decode("utf-8-sig", "replace")


def true_range(c, h, l):
    return [h[0] - l[0]] + [max(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1])) for i in range(1, len(c))]


def km_median(waits):
    watched, not_yet = len(waits), 1.0
    for t in sorted(set(w[0] for w in waits)):
        group = [w for w in waits if w[0] == t]
        arrived = sum(1 for w in group if w[1])
        not_yet *= 1 - arrived / watched
        if not_yet <= 0.5:
            return t
        watched -= len(group)
    return None


def measure(c, h, l):
    n = len(c)
    if n - 1 - ATR_N < MIN_STARTS:
        return []
    tr = true_range(c, h, l)
    waits = [[] for _ in range(MAX_ATRS)]
    for s in range(ATR_N, n - 1):
        atr = sum(tr[s - ATR_N + 1:s + 1]) / ATR_N
        if atr <= 0:
            continue
        reached, best = 0, float("-inf")
        for j in range(s + 1, n):
            best = max(best, h[j])
            while reached < MAX_ATRS and best >= c[s] + (reached + 1) * atr:
                waits[reached].append((j - s, True))
                reached += 1
            if reached == MAX_ATRS:
                break
        for k in range(reached, MAX_ATRS):
            waits[k].append((n - 1 - s, False))
    return [km_median(w) for w in waits]


def quartiles(x):
    if len(x) < 2:
        return x[0], x[0], x[0]
    q = statistics.quantiles(x, n=4, method="inclusive")
    return q[0], statistics.median(x), q[2]


def measure_hits(c, h, l, horizon, min_hits=5, breakouts=False, scale=1.0):
    """For each rise: (q1, median, q3) sessions among starts that got there within [horizon] sessions, every start
    watched the full horizon; None with fewer than [min_hits] hits. With [breakouts], only closes above the 20
    sessions' high before them start."""
    n = len(c)
    tr = true_range(c, h, l)
    starts = [s for s in range(max(ATR_N, 20), n - horizon) if not breakouts or c[s] >= max(h[s - 20:s])]
    hits = [[] for _ in range(MAX_ATRS)]
    for s in starts:
        atr = sum(tr[s - ATR_N + 1:s + 1]) / ATR_N
        if atr <= 0:
            continue
        reached, best = 0, float("-inf")
        for j in range(s + 1, s + horizon + 1):
            best = max(best, h[j])
            while reached < MAX_ATRS and best >= c[s] + (reached + 1) * atr:
                hits[reached].append(j - s)
                reached += 1
            if reached == MAX_ATRS:
                break
    return [tuple(v * scale for v in quartiles(x)) if len(x) >= min_hits else None for x in hits]


def interp(table, atrs, pick):
    """Like sessions(), over one of a table's (q1, median, q3)."""
    if not table or not atrs > 0:
        return None
    at = len(table) if len(table) < atrs < len(table) + 0.01 else atrs
    if at > len(table):
        return None
    below, above = int(at // 1), -int(-at // 1)
    frm = 0 if below == 0 else (table[below - 1][pick] if table[below - 1] else None)
    to = table[above - 1][pick] if table[above - 1] else None
    if frm is None or to is None:
        return None
    v = to if above == below else frm + (to - frm) * (at - below)
    return max(1.0, v)


def plan(c, h, l):
    tr = true_range(c, h, l)
    price, high20, low10 = c[-1], max(h[-21:-1]), min(l[-10:])
    atr = sum(tr[-14:]) / 14 or price * 0.01
    trigger = high20 + 0.1 * atr
    broken = price >= trigger
    entry = price if broken else trigger
    stop = low10 - 0.15 * atr
    stop = min(max(stop, entry - 2 * atr), entry - atr)
    if not (0 < stop < entry):
        return None
    risk = entry - stop
    return dict(broken=broken, entry=entry, stop=stop, atr=atr, targets=[entry + r * risk for r in TARGET_R])


def load(symbol):
    chart = json.loads(get(f"https://query1.finance.yahoo.com/v8/finance/chart/{symbol}.NS?range=1y&interval=1d"))["chart"]["result"][0]
    q = chart["indicators"]["quote"][0]
    rows = [(c, h, l) for c, h, l, v in zip(q["close"], q["high"], q["low"], q["volume"])
            if None not in (c, h, l, v) and c > 0 and l > 0]
    return [r[0] for r in rows], [max(r) for r in rows], [min(r) for r in rows]


def collect(symbols, watch):
    """Out-of-sample plans: per target (index, atrs, B estimate (q1, median, q3) or None, sessions taken or None)."""
    rows, today = [], []
    for symbol in symbols:
        try:
            c, h, l = load(symbol)
        except Exception:
            continue
        time.sleep(0.1)
        if len(c) < 60:
            continue
        tc, th, tl = c[-HIST:], h[-HIST:], l[-HIST:]
        p = plan(tc, th, tl)
        if p:
            table = measure_hits(tc, th, tl, 40)
            for i, t in enumerate(p["targets"]):
                atrs = (t - p["entry"]) / p["atr"]
                today.append((i, atrs, interp(table, atrs, 1)))
        n = len(c)
        cut = n - AHEAD
        if cut < HIST:
            continue
        hc, hh, hl = c[cut - HIST:cut], h[cut - HIST:cut], l[cut - HIST:cut]
        p = plan(hc, hh, hl)
        if not p:
            continue
        fh = h[cut:]
        e = -1 if p["broken"] else next((j for j in range(len(fh)) if fh[j] >= p["entry"]), None)
        if e is None or e > AHEAD - 1 - watch:
            continue
        table = measure_hits(hc, hh, hl, 40)
        for i, t in enumerate(p["targets"]):
            hit = next((j for j in range(max(e, 0), e + watch + 1) if fh[j] >= t), None)
            atrs = (t - p["entry"]) / p["atr"]
            rows.append((i, atrs, interp(table, atrs, 1), None if hit is None else max(1, hit - e)))
    return rows, today


def q3(x):
    q = statistics.quantiles(x, n=4, method="inclusive")
    return q[0], statistics.median(x), q[2]


def main():
    import math
    text = get("https://archives.nseindia.com/content/equities/EQUITY_L.csv")
    symbols = [r["SYMBOL"].strip() for r in csv.DictReader(io.StringIO(text)) if r.get(" SERIES", r.get("SERIES", "")).strip() == "EQ"]
    random.seed(7)
    random.shuffle(symbols)
    WATCH = 40
    fit, _ = collect(symbols[:800], WATCH)
    test, today = collect(symbols[800:1200], WATCH)
    print(f"fit rows {len(fit)}, test rows {len(test)}")
    # Per target: the spread of actual / B among fit hits.
    factors = {}
    for i in range(3):
        r = [a / b for j, k, b, a in fit if j == i and b and a]
        factors[i] = q3(r)
        print(f"T{i+1} fit: {len(r)} hits, actual/B quartiles " + " ".join(f"{v:.2f}" for v in factors[i]))
    # A global curve: log(actual) = log a + b log(atrs), over fit hits.
    pts = [(math.log(k), math.log(a)) for j, k, b, a in fit if a and k > 0]
    mx = sum(x for x, _ in pts) / len(pts); my = sum(y for _, y in pts) / len(pts)
    slope = sum((x - mx) * (y - my) for x, y in pts) / sum((x - mx) ** 2 for x, _ in pts)
    a0 = math.exp(my - slope * mx)
    resid = [y - (math.log(a0) + slope * x) for x, y in pts]
    rq = q3(resid)
    print(f"global curve: sessions = {a0:.2f} * atrs^{slope:.2f}; residual quartiles " + " ".join(f"{math.exp(v):.2f}" for v in rq))
    # Same curve, but fit on B: sessions = c * B.
    for name, est in (
        ("B scaled per target", lambda j, k, b: None if b is None else (b * factors[j][0], b * factors[j][1], b * factors[j][2])),
        ("global curve", lambda j, k, b: tuple(a0 * k ** slope * math.exp(v) for v in rq)),
        ("B raw", lambda j, k, b: None if b is None else (b, b, b)),
    ):
        print("==", name)
        for i in range(3):
            rows = [(est(j, k, b), a) for j, k, b, a in test if j == i and a]
            rows = [(e, a) for e, a in rows if e]
            if not rows:
                continue
            by = sum(1 for e, a in rows if a <= round(e[1])) / len(rows)
            inside = sum(1 for e, a in rows if math.floor(e[0]) <= a <= math.ceil(e[2])) / len(rows)
            err = statistics.median(abs(math.log(a / e[1])) for e, a in rows)
            print(f"  T{i+1} test hits {len(rows)}: by the estimate {by:.0%}, inside range {inside:.0%}, median |log error| {err:.2f}")
        for i in range(3):
            v = [est(j, k, b) for j, k, b in today if j == i]
            got = [e for e in v if e]
            if got:
                print(f"  T{i+1} today: timed {len(got)} of {len(v)}; median point {statistics.median(e[1] for e in got):.1f}, "
                      f"median range {statistics.median(e[0] for e in got):.1f}-{statistics.median(e[2] for e in got):.1f}")
    for i in range(3):
        hits = [a for j, k, b, a in test if j == i]
        print(f"T{i+1} test plans {len(hits)}: hit within {WATCH} {sum(1 for a in hits if a) / max(1, len(hits)):.0%}")


if __name__ == "__main__":
    main()
