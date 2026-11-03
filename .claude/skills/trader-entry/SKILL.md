---
name: trader-entry
description: Opening-range breakout (ORB) entry check on 5-minute candles: the first 15 minutes' high and low, and whether a candle has since closed above or below them, with a fair-value-gap confirmation. Use when the user asks whether a stock has given an ORB entry today.
---

# /trader-entry (Entry Timer (ORB))

Takes the latest session's 5-minute candles, marks the opening range (09:15 to 09:30 IST by default), and reports WAITING_DATA, WAITING (inside the range) or TRIGGERED (the first candle that closed beyond it: BUY above, SELL below, with its time and price). A trigger also says whether an unfilled fair value gap points the same way (smc_confirmation).

## Run

```
python3 tools/trader.py entry SYMBOL [--orb-minutes 15] [--csv FILE]
```

Example: `python3 tools/trader.py entry SBIN`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
