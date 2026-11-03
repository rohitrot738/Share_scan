---
name: short-setup
description: Swing setup analysis of one NSE/BSE stock: weekly and daily trend (multi-timeframe confirmation), support and resistance from swing points, ATR, and unmitigated daily order blocks and unfilled fair value gaps. Use when the user asks whether a stock is a good swing setup or where its levels are.
---

# /short-setup (Swing Setup (multi-timeframe))

Compares the weekly trend (10- and 30-week EMAs) with the daily one (20 and 50 EMAs) and says whether they agree, lists the two nearest swing lows under the price and swing highs over it, the 14-day ATR, and the daily order blocks and fair value gaps price has not closed through or filled.

## Run

```
python3 tools/invest.py short-setup SYMBOL
```

Example: `python3 tools/invest.py short-setup TRENT`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
