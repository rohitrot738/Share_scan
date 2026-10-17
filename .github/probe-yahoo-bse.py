"""Temporary probe: Yahoo chart rate limit. Burst until 429, check query2, time the recovery, then try steady rates."""
import csv, http.client, io, threading, time, urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
req = urllib.request.Request("https://archives.nseindia.com/content/equities/EQUITY_L.csv",
                             headers={"User-Agent": BROWSER, "Referer": "https://www.nseindia.com/all-reports"})
syms = [r[0].strip() + ".NS" for r in list(csv.reader(io.StringIO(urllib.request.urlopen(req, timeout=30).read().decode())))[1:] if r]
local = threading.local()
counter = [0]
lock = threading.Lock()
T0 = time.time()

def chart(host="query1.finance.yahoo.com"):
    with lock:
        ticker = syms[counter[0] % len(syms)]; counter[0] += 1
    key = "conn_" + host
    conn = getattr(local, key, None)
    if conn is None:
        conn = http.client.HTTPSConnection(host, timeout=25); setattr(local, key, conn)
    try:
        conn.request("GET", "/v8/finance/chart/%s?range=3mo&interval=1d" % ticker, headers={"User-Agent": "ShareScan/1.0 (Android)"})
        r = conn.getresponse(); r.read()
        return r.status
    except Exception:
        conn.close(); setattr(local, key, None); return -1

def say(*a):
    print("t=%5.1fs" % (time.time() - T0), *a, flush=True)

# A: burst from 4 threads until the first 429.
stop = threading.Event(); statuses = Counter(); sent = [0]
def burst():
    while not stop.is_set():
        s = chart(); 
        with lock:
            statuses[s] += 1; sent[0] += 1
        if s == 429: stop.set()
with ThreadPoolExecutor(4) as pool:
    for _ in range(4): pool.submit(burst)
say("A burst: first 429 after", sent[0], "requests", dict(statuses))
say("A2 query2 right after:", Counter(chart("query2.finance.yahoo.com") for _ in range(10)))

# B: one request every 5 s until a 200, for up to 10 minutes.
start = time.time()
while time.time() - start < 600:
    if chart() == 200:
        say("B recovered after %.0f s" % (time.time() - start)); break
    time.sleep(5)
else:
    say("B no recovery in 600 s")
say("B2 burst of 200 after recovery:", Counter(chart() for _ in range(200)))

# C: steady rates, 4 threads, each run 90 s.
def steady(rate, seconds):
    interval = 1.0 / rate; next_at = [time.time()]; out = Counter(); end = time.time() + seconds
    def worker():
        while True:
            with lock:
                at = next_at[0]; next_at[0] += interval
            if at > end: return
            delay = at - time.time()
            if delay > 0: time.sleep(delay)
            s = chart()
            with lock: out[s] += 1
    with ThreadPoolExecutor(4) as pool:
        for _ in range(4): pool.submit(worker)
    return out

for rate in (5, 10, 20):
    while chart() == 429:   # start each test from a recovered state
        time.sleep(10)
    say("C steady %d req/s for 90 s:" % rate, dict(steady(rate, 90)))
