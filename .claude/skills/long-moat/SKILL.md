---
name: long-moat
description: Economic moat checklist of an Indian company from its own numbers: pricing power (margins held), returns on capital, steady growth, balance sheet, cash conversion, promoter holding, pledge, promoter selling and auditor resignation. Use when the user asks whether a company has a moat or about promoter integrity.
---

# /long-moat (Moat & Promoter Checklist)

Ticks each check the data can answer: net margin held or rose over the years, ROCE or ROE at least 15%, sales up nearly every year, debt-to-equity at most 0.5 with interest covered five times, operating cash flow at least 80% of profit, promoters holding half or more and not cutting, no pledge, no promoter selling in the last year, no auditor resignation. Market share is not in the data; say so.

## Run

```
python3 tools/invest.py long-moat SYMBOL
```

Example: `python3 tools/invest.py long-moat TCS`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
