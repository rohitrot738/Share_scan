---
name: trader-alerts
description: Stop-loss, target and 15:15 IST square-off alerts for an open trade. Use when the user asks whether a trade's stop or target is hit, or about intraday square-off time.
---

# /trader-alerts (Live Price & Bell Alerts)

Checks the price against the trade's stop and target (for a buy or a short) and, from 15:15 IST until the close, reminds to close intraday (MIS) positions before the broker squares them off. The time is India's whatever this machine's clock is set to.

## Run

```
python3 tools/trader.py alerts --cmp PRICE --stop S --target T [--side BUY|SELL] [--time HH:MM]
python3 tools/trader.py alerts --symbol SYMBOL --stop S --target T   # price from Yahoo
```

Example: `python3 tools/trader.py alerts --cmp 2995 --stop 2935 --target 2990`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
