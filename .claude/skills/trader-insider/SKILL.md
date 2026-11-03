---
name: trader-insider
description: SEBI insider trades, NSE bulk/block deals, promoter pledge and shareholding trend for an Indian stock, from the app's nightly R/S data. Use when the user asks who is buying or selling a stock, insider or promoter activity, bulk deals or pledges.
---

# /trader-insider (SEBI Insider & Bulk Deals Tracker)

Reads the stock's insider trades (SEBI PIT disclosures) and bulk/block deals from the app's nightly R/S history files: the last 12 months' buys and sells, the ten latest of each, the promoters' pledged %, the change in promoter holding and the last four quarters' shareholding (promoter, FII, DII, public).

## Run

```
python3 tools/trader.py insider SYMBOL
```

Example: `python3 tools/trader.py insider TATASTEEL`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
