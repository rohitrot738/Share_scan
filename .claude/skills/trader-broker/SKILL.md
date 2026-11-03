---
name: trader-broker
description: Paper order tickets for Zerodha Kite Connect and Angel One SmartAPI: the entry order, a stop-loss order and a target order. Use when the user wants an order ticket, bracket or OCO setup for a trade. Never sends an order.
---

# /trader-broker (Broker Ticket Formatter (paper))

Writes the three orders a trade needs in each broker API's own fields: a LIMIT entry, an SL-M (stop-loss market) exit at the stop and a LIMIT exit at the target, MIS (intraday) or CNC (delivery). Angel One also needs the instrument's numeric symbol token from its instrument list. The tickets are for paper trading or to type in; nothing is sent.

## Run

```
python3 tools/trader.py ticket SYMBOL --side BUY|SELL --qty N --price P --stop S --target T [--product MIS|CNC]
```

Example: `python3 tools/trader.py ticket RELIANCE --side BUY --qty 33 --price 2950 --stop 2935 --target 2980`

Run it from the repository root; it prints JSON. Explain the result to the user in their language (Hindi or
Hinglish if they write so), with the numbers, and say plainly that it is an estimate from past prices, not investment
advice. If it prints `{"error": ...}`, tell the user what did not come; never fill in numbers yourself. Price candles
come from Yahoo Finance (where this machine's network allows it); `--csv FILE` (time,open,high,low,close[,volume]) can
stand in for them. Nothing here places an order.
