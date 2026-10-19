#!/usr/bin/env bash
# Temporary: calibrates the live RVOL curve against what the app sees, the live daily bar. Remove before merging.
set -u
echo "now: $(TZ=Asia/Kolkata date)"
python3 - <<'PY'
import collections, datetime, json, statistics, time, urllib.request
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
STOCKS = """RELIANCE TCS HDFCBANK INFY ICICIBANK SBIN ITC LT BHARTIARTL AXISBANK MARUTI SUNPHARMA HCLTECH BAJFINANCE
HBLENGINE MOLBIO SAMBHV INDHOTEL TRENT DIXON POLYCAB KPITTECH IRFC SUZLON YESBANK IDEA PNB BEL HAL CDSL BSE ANGELONE
KAYNES SONACOMS TITAGARH RVNL NBCC NHPC IREDA JIOFIN PAYTM DELHIVERY NYKAA GRAVITA ELECON CAMS KEI APARINDS JWL
RAILTEL HFCL IEX NATIONALUM SAIL TATASTEEL VEDL HINDZINC COCHINSHIP MAZDOCK GRSE SHANTIGOLD PCJEWELLER TBZ GABRIEL
VALIANTLAB SOUTHBANK OAL ORIENTHOT AEROFLEX GCSL SHYAMMETL LOTUSDEV AIIL BALMLAWRIE ATUL CONCOR TDPOWERSYS HOMEFIRST
BHARATWIRE PGIL STAR DIVISLAB NORTHARC ARROWGREEN GREENPOWER ANMOL BAJAJINDEF PATANJALI POWERGRID SURYAROSNI""".split()

def chart(sym, rng, interval):
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}.NS?range={rng}&interval={interval}"
    req = urllib.request.Request(url, headers={"User-Agent": "ShareScan/1.0 (Android)"})
    return json.load(urllib.request.urlopen(req, timeout=25))["chart"]["result"][0]

marks = [5, 10, 15, 20, 30, 45, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330, 345, 360, 375]
hist = collections.defaultdict(list)   # mark -> shares of the final daily volume, past days
today_ratio = []                        # live daily bar / sum of today's 5m bars so far
today_share = collections.defaultdict(list)
now = datetime.datetime.now(IST)
today = now.date()
for sym in STOCKS:
    try:
        daily = chart(sym, "3mo", "1d")
        five = chart(sym, "60d", "5m")
    except Exception as e:
        print(sym, "error", e); continue
    dvol = {}
    for t, v in zip(daily.get("timestamp") or [], daily["indicators"]["quote"][0]["volume"]):
        if v: dvol[datetime.datetime.fromtimestamp(t, IST).date()] = v
    days = collections.defaultdict(list)
    for t, v in zip(five.get("timestamp") or [], five["indicators"]["quote"][0]["volume"]):
        if v is None: continue
        dt = datetime.datetime.fromtimestamp(t, IST)
        days[dt.date()].append(((dt.hour * 60 + dt.minute) - 555, v))
    for day, bars in days.items():
        if day == today:
            done = sum(v for s, v in bars)
            if done and dvol.get(day):
                today_ratio.append(dvol[day] / done)
            continue
        final = dvol.get(day)
        if not final or len(bars) < 70: continue
        for m in marks:
            hist[m].append(sum(v for s, v in bars if s + 5 <= m) / final)
    time.sleep(0.25)

print("past days, share of the FINAL DAILY volume done by each time (median, p25, p75):")
for m in marks:
    xs = sorted(hist[m])
    if xs:
        print(f"  {m:3d} min ({9 + (15 + m) // 60}:{(15 + m) % 60:02d}) {statistics.median(xs):.3f} {xs[len(xs)//4]:.3f} {xs[3*len(xs)//4]:.3f}  n={len(xs)}")
xs = sorted(today_ratio)
if xs:
    print(f"today at {now:%H:%M}: live daily bar / sum of 5m bars: median {statistics.median(xs):.3f} p25 {xs[len(xs)//4]:.3f} p75 {xs[3*len(xs)//4]:.3f} n={len(xs)}")
# Sum of 5m bars over a whole past day against the daily bar: how much volume the 5m bars miss.
PY
