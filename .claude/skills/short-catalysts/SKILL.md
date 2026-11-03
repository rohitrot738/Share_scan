---
name: short-catalysts
description: Upcoming and recent catalysts of an Indian stock from its BSE filings: results dates, board meetings, dividends and record dates, AGM/EGM, bonus, split, buyback, rights. Use when the user asks about a stock's upcoming events, results date or dividends.
---

# /short-catalysts (Catalyst Tracker)

Reads the company's filings on BSE for the last 90 days (BSE's own announcements, as the app's News reads them), sorts the ones about results, board meetings, dividends, record dates, general meetings, bonus or split, buyback and rights issues, and lists those whose headline names a date still to come as upcoming. An NSE symbol is looked up on BSE's search; --bse-code skips that.

## Run

```
python3 tools/invest.py short-catalysts SYMBOL [--bse-code 500325] [--days 90]
```

Example: `python3 tools/invest.py short-catalysts RELIANCE`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
