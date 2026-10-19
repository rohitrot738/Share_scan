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
    now = [[], [], []]          # today's estimates, per target
    now_none = [0, 0, 0]
    calib = [[], [], []]        # (predicted, reached_by_predicted, reached_in_window, stop_first)
    calib_none = [[0, 0], [0, 0], [0, 0]]
    triggered = untriggered = failed = 0
    for symbol in sample:
        try:
            c, h, l = load(symbol)
        except Exception:
            failed += 1
            continue
        time.sleep(0.12)
        # What the app shows today: the latest six months.
        rises = measure(c[-HIST:], h[-HIST:], l[-HIST:])
        p = plan(c[-HIST:], h[-HIST:], l[-HIST:])
        if p and rises:
            for i, t in enumerate(p["targets"]):
                s = sessions(rises, (t - p["entry"]) / p["atr"])
                if s is None:
                    now_none[i] += 1
                else:
                    now[i].append(s)
        # Out of sample: estimate 60 sessions before the end, then watch what happened.
        n = len(c)
        cut = n - AHEAD
        if cut < HIST:
            continue
        hc, hh, hl = c[cut - HIST:cut], h[cut - HIST:cut], l[cut - HIST:cut]
        rises = measure(hc, hh, hl)
        p = plan(hc, hh, hl)
        if not p or not rises:
            continue
        fh, fl = h[cut:], l[cut:]
        if p["broken"]:
            e = -1
        else:
            e = next((j for j in range(len(fh)) if fh[j] >= p["entry"]), None)
            if e is None:
                untriggered += 1
                continue
        triggered += 1
        stop_day = next((j for j in range(max(e, 0), len(fh)) if fl[j] <= p["stop"] and j > e), None)
        for i, t in enumerate(p["targets"]):
            pred = sessions(rises, (t - p["entry"]) / p["atr"])
            hit = next((j for j in range(max(e, 0), len(fh)) if fh[j] >= t), None)
            took = None if hit is None else max(1, hit - e)
            watched = len(fh) - 1 - e
            stop_first = stop_day is not None and (hit is None or stop_day < hit)
            if pred is None:
                calib_none[i][0] += 1
                calib_none[i][1] += took is not None
                continue
            if pred > watched:
                continue  # not watched long enough to judge
            calib[i].append((pred, took is not None and took <= pred, took is not None, stop_first, took))
    print(f"stocks {len(sample)}, failed {failed}; out of sample: triggered {triggered}, never triggered {untriggered}")
    for i in range(3):
        v = sorted(now[i])
        if v:
            q = statistics.quantiles(v, n=4)
            print(f"T{i+1} today: timed {len(v)}, untimed {now_none[i]}; sessions median {statistics.median(v)} "
                  f"quartiles {q[0]:.0f}-{q[2]:.0f} min {v[0]} max {v[-1]}")
    for i in range(3):
        rows = calib[i]
        if not rows:
            continue
        by = sum(r[1] for r in rows) / len(rows)
        ever = sum(r[2] for r in rows) / len(rows)
        stop = sum(r[3] for r in rows) / len(rows)
        ratios = [r[4] / r[0] for r in rows if r[4] is not None]
        print(f"T{i+1} calibration: {len(rows)} plans; reached by the estimate {by:.0%}, within {AHEAD} sessions {ever:.0%}, "
              f"stop first {stop:.0%}; actual/estimate median {statistics.median(ratios) if ratios else float('nan'):.2f}")
        nn = calib_none[i]
        if nn[0]:
            print(f"   untimed: {nn[0]} plans, reached in window {nn[1] / nn[0]:.0%}")
    # The same check by estimate band, T1 only.
    for lo, hi in ((1, 5), (6, 10), (11, 20), (21, 60)):
        rows = [r for r in calib[0] if lo <= r[0] <= hi]
        if rows:
            print(f"T1 estimate {lo}-{hi}: {len(rows)} plans, reached by estimate {sum(r[1] for r in rows) / len(rows):.0%}, "
                  f"ever {sum(r[2] for r in rows) / len(rows):.0%}")


if __name__ == "__main__":
    main()
