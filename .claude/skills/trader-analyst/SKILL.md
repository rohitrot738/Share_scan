---
name: trader-analyst
description: Quarterly results and fundamentals of an NSE/BSE company: sales, net profit, EPS, net margin and their year-on-year change for the last 8 quarters, yearly cash flow and debt, P/E and the app's R/S score. Use when the user asks about results, growth, margins or fundamentals.
---

# /trader-analyst (Indian Earnings & Fundamentals)

Reads the company's quarterly and yearly results from the app's nightly R/S files and works out each quarter's net margin and its sales and profit growth against the same quarter a year before.

## Run

```
python3 tools/trader.py analyst SYMBOL
```

Example: `python3 tools/trader.py analyst RELIANCE`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
