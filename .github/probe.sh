#!/usr/bin/env bash
# Temporary: checks which data sources the app can use for live scans and sectors. Remove before merging.
set -u
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
echo "now UTC: $(date -u) | IST: $(TZ=Asia/Kolkata date)"
fetch() { curl -sS -m 30 -A "$UA" -H "Accept: */*" "${@:2}" -o /tmp/p.bin -w "%{http_code} %{size_download}" "$1"; }

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
    print({k: m.get(k) for k in ('currency','exchangeName','instrumentType','regularMarketPrice','chartPreviousClose','regularMarketVolume','dataGranularity','range','hasPrePostMarketData','gmtoffset','exchangeTimezoneName')})
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

echo "== yahoo spark v7"; fetch "https://query1.finance.yahoo.com/v7/finance/spark?symbols=RELIANCE.NS,TCS.NS,INFY.NS&range=1d&interval=5m"; echo; head -c 400 /tmp/p.bin; echo
echo "== yahoo spark v8"; fetch "https://query1.finance.yahoo.com/v8/finance/spark?symbols=RELIANCE.NS,TCS.NS,INFY.NS&range=1d&interval=1d"; echo; head -c 400 /tmp/p.bin; echo
echo "== yahoo quote v7"; fetch "https://query1.finance.yahoo.com/v7/finance/quote?symbols=RELIANCE.NS,TCS.NS"; echo; head -c 300 /tmp/p.bin; echo

for u in \
  https://archives.nseindia.com/content/indices/ind_nifty500list.csv \
  https://nsearchives.nseindia.com/content/indices/ind_nifty500list.csv \
  https://archives.nseindia.com/content/indices/ind_niftytotalmarket_list.csv \
  https://nsearchives.nseindia.com/content/indices/ind_niftytotalmarket_list.csv \
  https://archives.nseindia.com/content/indices/ind_niftymicrocap250_list.csv \
  https://nsearchives.nseindia.com/content/indices/ind_niftymicrocap250_list.csv \
  https://www.niftyindices.com/IndexConstituent/ind_niftytotalmarket_list.csv \
  https://www.niftyindices.com/IndexConstituent/ind_niftymicrocap250_list.csv; do
  echo "== $u"; fetch "$u" -H "Referer: https://www.nseindia.com/"; echo
  head -c 250 /tmp/p.bin; echo; echo "rows: $(wc -l < /tmp/p.bin)"
  python3 -c "
import csv,collections
rows=list(csv.DictReader(open('/tmp/p.bin',encoding='utf-8-sig',errors='replace')))
if rows and 'Industry' in rows[0]:
  c=collections.Counter(r['Industry'] for r in rows); print(len(c),'industries:',c.most_common(30))
" 2>/dev/null
done

echo "== bse scrip list"
fetch "https://api.bseindia.com/BseIndiaAPI/api/ListofScripData/w?Group=&Scripcode=&industry=&segment=Equity&status=Active" -H "Referer: https://www.bseindia.com/" -H "Origin: https://www.bseindia.com"; echo
head -c 600 /tmp/p.bin; echo
python3 -c "
import json,collections
d=json.load(open('/tmp/p.bin'))
print(type(d), len(d)); print(d[0] if isinstance(d,list) else list(d)[:5])
c=collections.Counter((r.get('INDUSTRY') or r.get('Industry') or '') for r in d); print(len(c),'industries', c.most_common(15))
print('with ISIN', sum(1 for r in d if (r.get('ISIN_NUMBER') or r.get('ISIN'))))
" 2>&1 | head -12
