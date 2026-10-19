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


def main():
    text = get("https://archives.nseindia.com/content/equities/EQUITY_L.csv")
    symbols = [r["SYMBOL"].strip() for r in csv.DictReader(io.StringIO(text)) if r.get(" SERIES", r.get("SERIES", "")).strip() == "EQ"]
    random.seed(29)
    sample = random.sample(symbols, min(450, len(symbols)))
    WATCH = 40
    def combo(c, h, l):
        e = measure_hits(c, h, l, 40, min_hits=3, breakouts=True)
        f = measure_hits(c, h, l, 40, scale=0.5)
        return [x if x is not None else y for x, y in zip(e, f)]
    methods = {
        "B hits within 40": lambda c, h, l: measure_hits(c, h, l, 40),
        "E breakout starts, 3+ hits": lambda c, h, l: measure_hits(c, h, l, 40, min_hits=3, breakouts=True),
        "F B x 0.5": lambda c, h, l: measure_hits(c, h, l, 40, scale=0.5),
        "G E else F": combo,
    }
    today = {m: [[], [], []] for m in methods}
    today_none = {m: [0, 0, 0] for m in methods}
    calib = {m: [[], [], []] for m in methods}
    untimed = {m: [[0, 0], [0, 0], [0, 0]] for m in methods}
    plans = failed = 0
    for symbol in sample:
        try:
            c, h, l = load(symbol)
        except Exception:
            failed += 1
            continue
        time.sleep(0.12)
        tc, th, tl = c[-HIST:], h[-HIST:], l[-HIST:]
        p = plan(tc, th, tl)
        if p:
            for m, f in methods.items():
                table = f(tc, th, tl)
                for i, t in enumerate(p["targets"]):
                    v = interp(table, (t - p["entry"]) / p["atr"], 1)
                    if v is None:
                        today_none[m][i] += 1
                    else:
                        today[m][i].append(round(v))
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
        if e is None or e > AHEAD - 1 - WATCH:
            continue
        plans += 1
        for m, f in methods.items():
            table = f(hc, hh, hl)
            for i, t in enumerate(p["targets"]):
                hit = next((j for j in range(max(e, 0), e + WATCH + 1) if fh[j] >= t), None)
                took = None if hit is None else max(1, hit - e)
                atrs = (t - p["entry"]) / p["atr"]
                est = [interp(table, atrs, k) for k in range(3)]
                if est[1] is None:
                    untimed[m][i][0] += 1
                    untimed[m][i][1] += took is not None
                elif took is not None:
                    calib[m][i].append((est, took))
    print(f"stocks {len(sample)}, failed {failed}; plans watched {WATCH} sessions after entry: {plans}")
    for m in methods:
        print("==", m)
        for i in range(3):
            v = sorted(today[m][i])
            line = f"  T{i+1} today: timed {len(v)}, untimed {today_none[m][i]}"
            if len(v) >= 4:
                q = statistics.quantiles(v, n=4)
                line += f"; median {statistics.median(v)} quartiles {q[0]:.0f}-{q[2]:.0f}"
            print(line)
            rows = calib[m][i]
            if rows:
                by = sum(1 for e_, t_ in rows if t_ <= round(e_[1])) / len(rows)
                inside = sum(1 for e_, t_ in rows if round(e_[0]) <= t_ <= round(e_[2])) / len(rows)
                ratio = statistics.median(t_ / e_[1] for e_, t_ in rows)
                print(f"     hits with an estimate: {len(rows)}; by the estimate {by:.0%}; inside q1-q3 {inside:.0%}; actual/estimate median {ratio:.2f}")
            u = untimed[m][i]
            if u[0]:
                print(f"     untimed plans: {u[0]}, target hit in {WATCH}: {u[1]/u[0]:.0%}")


if __name__ == "__main__":
    main()
