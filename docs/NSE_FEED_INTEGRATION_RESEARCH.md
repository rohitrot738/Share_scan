# NSE Feed Integration Research (November 2026)

## Publicly verifiable product facts

NSE Data & Analytics describes these real-time market-data tiers:
- Level 1: best bid and ask.
- Level 2: up to 5 best bid and ask levels.
- Level 3: up to 20 best bid and ask levels.
- Tick-by-tick: full order book.

NSE states that the real-time feed is multicast and is delivered through a dedicated leased-line circuit, or may be obtained through an authorized real-time data vendor.

Official public product page:
https://betanseapi.nseindia.com/static/market-data/real-time-data-subscription

NSE's published authorized real-time data vendor list includes (among others) TrueData Financial Information Private Ltd, Global Financial Datafeeds LLP, Bloomberg, Refinitiv, and others. The subscribed segments differ by vendor; verify the current list and exact CM entitlement before procurement.

Official vendor list:
https://nsearchives.nseindia.com/web/sites/default/files/inline-files/Authorized_Realtime_Data_Vendors.pdf

## Important document restriction

The NSE MTBT API Specification Version 7.1 PDF currently linked from the public trading-protocol page is marked **Confidential** and states that its contents are proprietary and disclosure/duplication is prohibited. Public URL accessibility is not evidence of permission to reproduce or implement from restricted content. This repository therefore does not copy its packet layouts, message structures, or field offsets.

Official protocol index:
https://betanseapi.nseindia.com/static/trade/platform-services-neat-trading-system-protocols

Before implementing a native packet decoder, obtain the current specification and written usage rights directly through the user's authorized NSE/vendor relationship. The vendor should confirm whether its API exposes normalized trades, order events, order IDs, sequence numbers, timestamps, and recovery/snapshot facilities, and provide approved sample payloads.

## Footprint data requirements

A trade record containing only price and quantity is insufficient for true bid/ask footprint classification. The system needs either:
1. vendor-provided aggressor-side classification with documented semantics; or
2. authorized order and trade events sufficient to reconstruct the order book and identify which side was resting versus incoming, including cancellation, modification, trade-cancel, sequence-gap and recovery handling.

Do not guess aggressor side from candle direction, last-price movement, or OHLCV. Do not mix resting depth with executed trade volume.

## Suggested vendor questions

Request written confirmation for:
- NSE Capital Market (CM) real-time entitlement covering all required symbols.
- Tick-by-tick order and trade data (not only Level 1/2/3 snapshots).
- API/SDK format (WebSocket, TCP, multicast, binary or normalized JSON).
- Trade-side/aggressor semantics and whether both buy/sell order IDs are supplied.
- Sequence numbers, packet-loss recovery, order-book snapshots and trade corrections/cancellations.
- Historical tick/order/trade archive availability and redistribution/display rights for a personal application.
- Rate limits, concurrent connections, per-device/app display licensing, and total charges.

## Current implementation status

- `orderflow/decoder.py`: strict CSV/JSON/JSONL decoder for vendor-normalized executed trades.
- `orderflow/history.py`: replay engine that builds footprint bars from those normalized records.
- No NSE live connection, native packet decoder, or vendor SDK is implemented.
- Do not mark live-feed readiness until an authorized vendor adapter is validated against vendor-provided sample data and sequence/recovery tests.
