---
name: long-portfolio
description: Core-and-satellite long-term allocation: 60% core compounders, 30% growth, 10% cash reserve (or another split), each bucket's money spread by inverse volatility from the app's R/S data. Use when the user asks how to divide capital among long-term stocks.
---

# /long-portfolio (Core & Satellite Allocation)

Splits the capital into core compounders, growth stocks and a cash reserve (60/30/10 by default), and within a bucket gives calmer stocks more money (each stock's weight is one over its annual volatility, when the R/S file has every one; equal shares otherwise), with the shares that buys at the price the R/S file valued it at. With no stocks given, suggest some from /long-screener first.

## Run

```
python3 tools/invest.py long-portfolio --capital C --core A,B,C --growth D,E [--split 60,30,10]
```

Example: `python3 tools/invest.py long-portfolio --capital 500000 --core TCS,HDFCBANK,ASIANPAINT --growth POLYCAB,DIXON`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
