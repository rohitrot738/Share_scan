"""Temporary: samples today's live daily-bar volume through the session, to calibrate the live RVOL curve.

Usage: volume_sample.py <out.jsonl> HH:MM [HH:MM ...]  (IST times; sleeps until each, then samples every stock)
"""
import datetime, json, sys, time, urllib.request

IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
STOCKS = """RELIANCE TCS HDFCBANK INFY ICICIBANK SBIN ITC LT BHARTIARTL AXISBANK MARUTI SUNPHARMA HCLTECH BAJFINANCE
HBLENGINE MOLBIO SAMBHV INDHOTEL TRENT DIXON POLYCAB KPITTECH IRFC SUZLON YESBANK IDEA PNB BEL HAL CDSL BSE ANGELONE
KAYNES SONACOMS TITAGARH RVNL NBCC NHPC IREDA JIOFIN PAYTM DELHIVERY NYKAA GRAVITA ELECON CAMS KEI APARINDS JWL
RAILTEL HFCL IEX NATIONALUM SAIL TATASTEEL VEDL HINDZINC COCHINSHIP MAZDOCK GRSE""".split()


def sample(out, label):
    now = datetime.datetime.now(IST)
    rows = 0
    with open(out, "a") as f:
        for sym in STOCKS:
            url = f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}.NS?range=5d&interval=1d"
            req = urllib.request.Request(url, headers={"User-Agent": "ShareScan/1.0 (Android)"})
            try:
                r = json.load(urllib.request.urlopen(req, timeout=20))["chart"]["result"][0]
            except Exception as e:
                print(label, sym, "error", e)
                continue
            m = r["meta"]
            ts = r.get("timestamp") or []
            vols = r["indicators"]["quote"][0]["volume"]
            today = [v for t, v in zip(ts, vols) if datetime.datetime.fromtimestamp(t, IST).date() == now.date()]
            f.write(json.dumps({
                "label": label, "at": now.strftime("%H:%M:%S"), "sym": sym,
                "bar": today[-1] if today else None,
                "meta": m.get("regularMarketVolume"),
                "time": datetime.datetime.fromtimestamp(m.get("regularMarketTime", 0), IST).strftime("%d %H:%M"),
            }) + "\n")
            rows += 1
            time.sleep(0.2)
    print(label, "sampled", rows, "at", now.strftime("%H:%M:%S"), flush=True)


out = sys.argv[1]
for label in sys.argv[2:]:
    hh, mm = map(int, label.split(":"))
    now = datetime.datetime.now(IST)
    target = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
    if target > now:
        time.sleep((target - now).total_seconds())
    sample(out, label)
