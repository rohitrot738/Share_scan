---
name: trader-exit
description: Stop-loss and target planner for an intraday trade: stop at the opening range's midpoint (or an ATR multiple) and targets at 1:1.5 and 1:2 reward-to-risk, with the 15:15 IST square-off. Use when the user asks where to put the stop or targets.
---

# /trader-exit (Exit & Target Planner)

Puts the stop at the middle of the opening range (or ATR × multiple from the entry with --atr) and the targets at each R multiple of the risk, for a buy or a short, and refuses a stop on the wrong side of the entry.

## Run

```
python3 tools/trader.py exit --entry E --orb-high H --orb-low L [--side BUY|SELL] [--r 1.5 2] [--atr A --atr-mult 1]
```

Example: `python3 tools/trader.py exit --entry 2950 --orb-high 2960 --orb-low 2920`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
