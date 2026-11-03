---
name: trader-budget
description: Position sizing for an Indian trading account: shares to buy so a stop-out loses at most 1% of capital (or a rupee amount), within MIS intraday margin (about 5x) or CNC delivery. Use when the user asks how many shares to buy or how much margin a trade needs.
---

# /trader-budget (Position Sizing & Margin)

Works out the quantity two ways, by risk (risk budget ÷ (entry − stop)) and by money (capital × leverage ÷ entry), takes the smaller, and shows the loss if stopped out and the margin it needs. Leverage is 5x for MIS by default; brokers set each stock's own intraday margin, so check it. The 1% rule is a common guideline, not a SEBI rule.

## Run

```
python3 tools/trader.py budget --capital C --entry E --stop S [--risk-pct 1 | --risk-rs R] [--product MIS|CNC] [--leverage 5]
```

Example: `python3 tools/trader.py budget --capital 50000 --entry 2950 --stop 2935 --risk-rs 500`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
