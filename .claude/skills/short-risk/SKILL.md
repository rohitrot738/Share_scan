---
name: short-risk
description: Swing (CNC delivery) position sizing and stops: quantity from a 1.5% risk (capped at 2%) of capital, at most 25% of capital in one stock, target at 1:2.5, a stop two ATRs under the entry when none is given, a chandelier trailing stop, and the 5-open-positions limit. Use when the user asks how much to buy for a swing trade or where to trail the stop.
---

# /short-risk (Swing Risk & Trailing Stop)

Sizes a delivery position so a stop-out loses at most the risk budget (1.5% of capital by default, never above 2%) and no more than 25% of capital sits in one stock; puts the target at 2.5 times the risk; with --symbol, puts the stop two ATRs under the entry when none is given and, with --since (the entry day), trails it at the highest close since less three ATRs, never lower than the first stop; warns at five open positions.

## Run

```
python3 tools/invest.py short-risk --entry E (--stop S | --symbol SYMBOL) [--capital 100000] [--risk-pct 1.5 | --risk-rs R] [--open N] [--since YYYY-MM-DD]
```

Example: `python3 tools/invest.py short-risk --entry 3500 --stop 3400 --capital 200000`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
