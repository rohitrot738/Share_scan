---
name: trader-risk
description: Pre-trade risk guard for intraday trading: daily loss limit, trades per day and no new entry after 14:45 IST. Use before the user takes a trade, or when they ask whether a trade is within their rules.
---

# /trader-risk (Pre-Trade Guard)

Blocks a trade that would take the day's loss past the limit (₹1,500 by default), one past the day's trade count (3), or a new intraday entry after 14:45 IST or outside market hours, and lists every rule it breaks. The limits can be changed with --max-loss, --max-trades and --no-entry-after.

## Run

```
python3 tools/trader.py risk --loss RUPEES_IF_STOPPED [--day-loss D] [--trades N] [--time HH:MM] [--max-loss 1500] [--max-trades 3] [--no-entry-after 14:45]
```

Example: `python3 tools/trader.py risk --loss 500 --day-loss 400 --trades 1`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
