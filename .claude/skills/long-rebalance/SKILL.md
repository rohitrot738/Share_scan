---
name: long-rebalance
description: Portfolio rebalancing and tax-loss harvesting for Indian equity holdings: weights against targets, trades back to target outside a band, short- or long-term holding of each lot, gains, and the tax if sold at listed-equity rates. Use when the user asks to rebalance their portfolio or about capital gains tax or harvesting losses.
---

# /long-rebalance (Rebalance & Tax-Loss Harvest)

Reads the holdings CSV (symbol,qty,avg_price,buy_date[,target_pct]; targets equal when not given), prices them (Yahoo, or a CSV with --prices), shows each stock's weight and drift and the quantity to buy or sell for positions more than 5 points off target, marks each lot short-term (a year or less) or long-term, flags lots at a loss as harvesting candidates, and estimates the tax if all were sold (20% short-term, 12.5% long-term over the ₹1.25 lakh exemption: the Budget 2024 rates; check the current ones). Not tax advice.

## Run

```
python3 tools/invest.py long-rebalance --holdings holdings.csv [--band 5] [--prices prices.csv]
```

Example: `python3 tools/invest.py long-rebalance --holdings my-holdings.csv`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
