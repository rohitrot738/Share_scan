---
name: long-valuation
description: Intrinsic value of an NSE/BSE stock four ways: the app's sector valuation framework (fair value, buy price), the company's own usual P/E, the Graham number, and a ten-year DCF of free cash flow; with the margin of safety of each. Use when the user asks what a stock is worth, its fair value or whether it is undervalued.
---

# /long-valuation (Intrinsic Value)

Gives the R/S framework's intrinsic value (its sector model's value met halfway by the company's usual P/E, after the penalties) and buy price, the fair value at the company's usual P/E, the Graham number (√(22.5 × TTM EPS × book value a share), only where the book value is known) and a DCF (the last three years' average free cash flow growing at the profit CAGR, 0 to 15%, fading to 5%, at a 12% discount, less debt), each against the price the R/S file valued it at. Change the DCF with --discount, --growth and --terminal. A DCF is very sensitive to these inputs; show them with its result.

## Run

```
python3 tools/invest.py long-valuation SYMBOL [--discount 12] [--growth G] [--terminal 5]
```

Example: `python3 tools/invest.py long-valuation ASIANPAINT`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
