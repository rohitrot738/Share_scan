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


def measure_hits(c, h, l, horizon, min_hits=5):
    """For each rise, the median sessions among closes that got there within [horizon] sessions, every close
    watched the full horizon; and how many of those closes got there."""
    n = len(c)
    tr = true_range(c, h, l)
    starts = list(range(ATR_N, n - horizon))
    if len(starts) < 30:
        return [], []
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
    med = [statistics.median(x) if len(x) >= min_hits else None for x in hits]
    rate = [len(x) / len(starts) for x in hits]
    return [None if m is None else int(round(m)) for m in med], rate


def sessions(rises, atrs):
    if not rises or not atrs > 0:
        return None
    at = len(rises) if len(rises) < atrs < len(rises) + 0.01 else atrs
    if at > len(rises):
        return None
    below, above = int(at // 1), -int(-at // 1)
    frm = 0 if below == 0 else rises[below - 1]
    to = rises[above - 1]
    if frm is None or to is None:
        return None
    v = to if above == below else frm + (to - frm) * (at - below)
    return max(1, round(v))


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
    random.seed(23)
    sample = random.sample(symbols, min(400, len(symbols)))
    WATCH = 40
    methods = {
        "A all-closes KM": lambda c, h, l: measure(c, h, l),
        "B hits within 40": lambda c, h, l: measure_hits(c, h, l, 40)[0],
        "C hits within 60": lambda c, h, l: measure_hits(c, h, l, 60)[0],
        "D hits within 30": lambda c, h, l: measure_hits(c, h, l, 30)[0],
    }
    today = {m: [[], [], []] for m in methods}
    today_none = {m: [0, 0, 0] for m in methods}
    calib = {m: [[], [], []] for m in methods}
    untimed_hit = {m: [[0, 0], [0, 0], [0, 0]] for m in methods}
    rates = [[], [], []]
    outcomes = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]  # hit, stop first, neither
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
                rises = f(tc, th, tl)
                for i, t in enumerate(p["targets"]):
                    v = sessions(rises, (t - p["entry"]) / p["atr"])
                    (today[m][i].append(v) if v is not None else None)
                    if v is None:
                        today_none[m][i] += 1
            _, rate = measure_hits(tc, th, tl, 40)
            if rate:
                for i, t in enumerate(p["targets"]):
                    k = min(MAX_ATRS, max(1, round((t - p["entry"]) / p["atr"])))
                    rates[i].append(rate[k - 1])
        n = len(c)
        cut = n - AHEAD
        if cut < HIST:
            continue
        hc, hh, hl = c[cut - HIST:cut], h[cut - HIST:cut], l[cut - HIST:cut]
        p = plan(hc, hh, hl)
        if not p:
            continue
        fh, fl = h[cut:], l[cut:]
        e = -1 if p["broken"] else next((j for j in range(len(fh)) if fh[j] >= p["entry"]), None)
        if e is None or e > AHEAD - 1 - WATCH:
            continue
        plans += 1
        stop_day = next((j for j in range(e + 1, e + WATCH + 1) if fl[j] <= p["stop"]), None)
        for i, t in enumerate(p["targets"]):
            hit = next((j for j in range(max(e, 0), e + WATCH + 1) if fh[j] >= t), None)
            took = None if hit is None else max(1, hit - e)
            if took is not None and (stop_day is None or took <= stop_day - e):
                outcomes[i][0] += 1
            elif stop_day is not None:
                outcomes[i][1] += 1
            else:
                outcomes[i][2] += 1
            for m, f in methods.items():
                pred = sessions(f(hc, hh, hl), (t - p["entry"]) / p["atr"])
                if pred is None:
                    untimed_hit[m][i][0] += 1
                    untimed_hit[m][i][1] += took is not None
                elif took is not None:
                    calib[m][i].append((pred, took))
    print(f"stocks {len(sample)}, failed {failed}; plans entered early enough to watch {WATCH} sessions: {plans}")
    for i in range(3):
        o = outcomes[i]
        tot = sum(o) or 1
        print(f"T{i+1} outcome in {WATCH} sessions: target first {o[0]/tot:.0%}, stop first {o[1]/tot:.0%}, neither {o[2]/tot:.0%}")
        if rates[i]:
            print(f"   past hit rate (rise within 40 sessions) at this distance: median {statistics.median(rates[i]):.0%}")
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
                by = sum(1 for p_, t_ in rows if t_ <= p_) / len(rows)
                ratio = statistics.median(t_ / p_ for p_, t_ in rows)
                print(f"     hits with an estimate: {len(rows)}; hit by the estimate {by:.0%}; actual/estimate median {ratio:.2f}")
            u = untimed_hit[m][i]
            if u[0]:
                print(f"     untimed plans: {u[0]}, target hit in {WATCH}: {u[1]/u[0]:.0%}")


if __name__ == "__main__":
    main()
