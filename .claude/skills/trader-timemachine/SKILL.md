---
name: trader-timemachine
description: Monte Carlo replay of 10,000 possible paths from a stock's own recent 5-minute moves: the odds a trade reaches its target before its stop. Use when the user asks for the probability or odds of a trade working.
---

# /trader-timemachine (Time Machine Replay)

Takes the stock's own candle-to-candle moves (5-minute candles of the last 60 days by default, leaving out the overnight gaps), draws 10,000 futures of 30 candles (about 2½ hours) from them, and counts how many reach the target first, the stop first, or neither. It needs at least 30 past moves and never makes them up.

## Run

```
python3 tools/trader.py timemachine SYMBOL --entry E --stop S --target T [--paths 10000] [--steps 30] [--interval 5m|15m|1d] [--csv FILE]
```

Example: `python3 tools/trader.py timemachine RELIANCE --entry 2950 --stop 2930 --target 2990`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
