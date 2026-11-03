---
name: trader-smc
description: Smart Money Concepts on an NSE/BSE chart: unfilled fair value gaps (FVG), unmitigated order blocks (OB) and liquidity sweeps. Use when the user asks about FVG, order blocks, liquidity grabs, stop hunts or smart-money levels for a stock.
---

# /trader-smc (Smart Money Concepts)

Finds the three-candle fair value gaps price has not filled yet, the order blocks (the last opposite candle before a strong move) price has not closed through, and the candles that swept the highs or lows of the five before them and closed back inside.

## Run

```
python3 tools/trader.py smc SYMBOL [--interval 1d|1h|15m|5m] [--lookback 60] [--csv FILE]
```

Example: `python3 tools/trader.py smc TATASTEEL --interval 15m`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
