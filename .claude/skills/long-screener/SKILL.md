---
name: long-screener
description: Long-term quality screen of every NSE/BSE company in the app's R/S data: ROCE over 15%, ROE over 15%, debt-to-equity under 0.5, profit CAGR over 12%, promoters at least 50%, no pledge. Use when the user wants compounders or quality stocks for long-term investing.
---

# /long-screener (Quality Compounder Screener)

Checks each of the ~5,000 companies in the app's nightly R/S files: ROCE (where the file has it), ROE (TTM EPS over book value, or the latest year's profit over the equity its debt and debt-to-equity imply), debt-to-equity, the profit CAGR over the years in the file (up to four), promoter holding and pledge. A company passes when no known test fails and at least four are known (--strict: all six). The R/S file has no pledge figures at present, so pledge shows as unknown.

## Run

```
python3 tools/invest.py long-screener [--top 30] [--strict]
```

Example: `python3 tools/invest.py long-screener --top 20`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
