---
name: short-scanner
description: Swing-trade scanner for NSE/BSE stocks (holding 2 days to 4 weeks): 52-week breakout, Stage-2 uptrend, 20/50 EMA pullback, volume dry-up, pocket pivot and high-volume 20-day breakout. Use when the user wants swing candidates or asks which stocks show these setups.
---

# /short-scanner (Swing Momentum Scanner)

Reads two years of daily candles for each stock (a given list, or a default momentum watchlist) and marks: a close at a 52-week high; a Stage-2 uptrend (price over the 150- and 200-day averages, the 200-day rising, within 25% of the 52-week high); a pullback that touched the 20 or 50 EMA in an uptrend; volume drying up (5-day average under 60% of the 50-day); a pocket pivot (an up day on more volume than any down day of the ten before); and a 20-day breakout on 1.5 times the usual volume. Stocks with the most signals come first.

## Run

```
python3 tools/invest.py short-scanner [SYMBOL ...]
```

Example: `python3 tools/invest.py short-scanner TITAN POLYCAB DIXON`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
