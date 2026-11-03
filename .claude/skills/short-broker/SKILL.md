---
name: short-broker
description: Paper swing (CNC delivery) order tickets for Zerodha Kite, Angel One and Shoonya: a LIMIT entry plus the stop and target as a GTT (good-till-triggered) pair. Use when the user wants swing order tickets or a GTT setup. Never sends an order.
---

# /short-broker (Swing CNC Order Tickets (paper))

Writes a delivery entry order and its exits in each broker API's own fields: Kite's two-leg GTT (OCO) with both triggers, Angel One's two GTT rules (single-leg, so one is cancelled when the other fires) and Shoonya's entry with an OCO. The stop's limit sits half a percent past its trigger so it fills. Angel One needs the instrument's numeric token from its instrument list.

## Run

```
python3 tools/invest.py short-broker SYMBOL --qty N --entry E --stop S --target T [--side BUY]
```

Example: `python3 tools/invest.py short-broker TITAN --qty 10 --entry 3500 --stop 3400 --target 3750`

Run it from the repository root; it prints JSON. Explain the result in the user's language (Hindi or Hinglish if they
write so) with the numbers, and say plainly it is not investment advice. A figure the data lacks is `null` or
"unknown": say so, never fill it in. If it prints `{"error": ...}`, tell the user what did not come. Nothing here places
an order.
