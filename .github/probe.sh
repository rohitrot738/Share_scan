#!/usr/bin/env bash
# Temporary: checks which data sources the app can use for live scans and sectors. Remove before merging.
set -u
UA="ShareScan/1.0 (Android)"
echo "now UTC: $(date -u) | IST: $(TZ=Asia/Kolkata date)"
fetch() { curl -sS -m 30 -A "$UA" -H "Accept: application/json,text/plain,*/*" "${@:2}" -o /tmp/p.bin -w "%{http_code} %{size_download}" "$1"; }

for t in RELIANCE.NS HBLENGINE.NS %5ENSEI 500325.BO; do
  echo "== yahoo chart $t"
  fetch "https://query1.finance.yahoo.com/v8/finance/chart/$t?range=3mo&interval=1d"; echo
  python3 - <<'PY'
import json, datetime
try:
    d = json.load(open('/tmp/p.bin'))
    r = d['chart']['result'][0]; m = r['meta']
    ist = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
    f = lambda s: datetime.datetime.fromtimestamp(s, ist).strftime('%a %d %b %H:%M') if s else s
    print({k: m.get(k) for k in ('currency','exchangeName','instrumentType','regularMarketPrice','chartPreviousClose','regularMarketVolume','regularMarketDayHigh','dataGranularity','range','hasPrePostMarketData','gmtoffset','exchangeTimezoneName')})
    print('meta keys', sorted(m.keys()))
    print('regularMarketTime', f(m.get('regularMarketTime')))
    ctp = m.get('currentTradingPeriod', {})
    for k, v in ctp.items(): print('period', k, f(v.get('start')), '->', f(v.get('end')))
    ts = r.get('timestamp', []); q = r['indicators']['quote'][0]
    for i in range(max(0, len(ts) - 3), len(ts)):
        print(' bar', f(ts[i]), 'o', q['open'][i], 'h', q['high'][i], 'l', q['low'][i], 'c', q['close'][i], 'v', q['volume'][i])
except Exception as e:
    print('parse error', e, open('/tmp/p.bin','rb').read()[:300])
PY
done

echo "== yahoo spark v8"; fetch "https://query1.finance.yahoo.com/v8/finance/spark?symbols=RELIANCE.NS,TCS.NS,INFY.NS&range=1d&interval=1d"; echo; head -c 600 /tmp/p.bin; echo
echo "== yahoo spark v7"; fetch "https://query1.finance.yahoo.com/v7/finance/spark?symbols=RELIANCE.NS,TCS.NS,INFY.NS&range=1d&interval=5m"; echo; head -c 300 /tmp/p.bin; echo
echo "== yahoo quote v7"; fetch "https://query1.finance.yahoo.com/v7/finance/quote?symbols=RELIANCE.NS,TCS.NS"; echo; head -c 300 /tmp/p.bin; echo

# Intraday volume profile: share of a full session's volume traded by each time of day, from 5-minute bars.
echo "== intraday volume profile"
: > /tmp/profiles.jsonl
for t in RELIANCE TCS HDFCBANK INFY ICICIBANK SBIN ITC LT BHARTIARTL AXISBANK TATAMOTORS MARUTI SUNPHARMA HCLTECH BAJFINANCE \
         HBLENGINE MOLBIO SAMBHV INDHOTEL TRENT DIXON POLYCAB KPITTECH IRFC SUZLON YESBANK IDEA PNB BEL HAL \
         CDSL BSE ANGELONE KAYNES SONACOMS TITAGARH RVNL NBCC NHPC IREDA JIOFIN ZOMATO PAYTM DELHIVERY NYKAA; do
  fetch "https://query1.finance.yahoo.com/v8/finance/chart/$t.NS?range=60d&interval=5m" > /dev/null
  python3 - "$t" >> /tmp/profiles.jsonl <<'PY'
import json, sys, datetime, collections
ist = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
try:
    r = json.load(open('/tmp/p.bin'))['chart']['result'][0]
except Exception:
    sys.exit()
ts = r.get('timestamp') or []; v = r['indicators']['quote'][0]['volume']
days = collections.defaultdict(list)
for s, vol in zip(ts, v):
    if vol is None: continue
    t = datetime.datetime.fromtimestamp(s, ist)
    days[t.date()].append(((t.hour * 60 + t.minute) - 555, vol))
for day, bars in days.items():
    total = sum(b for _, b in bars)
    if total <= 0 or len(bars) < 70: continue
    print(json.dumps({'sym': sys.argv[1], 'day': str(day), 'bars': bars, 'total': total}))
PY
done
python3 - <<'PY'
import json, statistics
rows = [json.loads(l) for l in open('/tmp/profiles.jsonl')]
print('sessions', len(rows), 'stocks', len({r['sym'] for r in rows}))
marks = [5, 10, 15, 30, 45, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330, 345, 360, 370, 375]
for m in marks:
    shares = []
    for r in rows:
        # A 5-minute bar starting at minute s covers s..s+5; it is done by minute m when s + 5 <= m.
        done = sum(v for s, v in r['bars'] if s + 5 <= m)
        shares.append(done / r['total'])
    shares.sort()
    print(f"min {m:3d} ({9 + (15 + m) // 60}:{(15 + m) % 60:02d}) median {statistics.median(shares):.3f} p25 {shares[len(shares)//4]:.3f} p75 {shares[3*len(shares)//4]:.3f}")
PY
