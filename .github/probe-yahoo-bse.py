"""Temporary probe: does Yahoo keep a chart connection alive across requests?"""
import http.client, time

tickers = ["RELIANCE.NS", "TCS.NS", "SPACEINCUBA.BO", "NOPE123.BO", "INFY.NS"] * 12
conn = http.client.HTTPSConnection("query1.finance.yahoo.com", timeout=20)
conn.connect()
first_sock = conn.sock
reconnects, statuses, headers = 0, [], set()
start = time.time()
for i, t in enumerate(tickers):
    conn.request("GET", "/v8/finance/chart/%s?range=3mo&interval=1d" % t,
                 headers={"User-Agent": "ShareScan/1.0 (Android)", "Accept": "application/json,text/plain,*/*"})
    r = conn.getresponse()
    body = r.read()
    statuses.append(r.status)
    headers.add((r.getheader("Connection"), r.getheader("Keep-Alive"), r.version))
    if conn.sock is None:
        reconnects += 1
        conn.connect()
print("requests", len(tickers), "in", round(time.time() - start, 2), "s; reconnects", reconnects,
      "; same socket at end", conn.sock is first_sock)
print("statuses", sorted(set(statuses)), "connection headers", headers)
