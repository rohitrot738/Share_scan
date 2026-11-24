# NSE Historical Order & Trade Data Research (November 2026)

## Product confirmed from NSE's public product page

NSE Data & Analytics lists a separate Historical Order & Trade Data product for CM, F&O, CD and COM. For CM, the product covers order- and trade-level information plus index value. NSE lists a technical specification, undertaking and sample-file downloads, and directs customers to contact marketdata@nse.co.in for subscription.

Official product page:
https://betanseapi.nseindia.com/static/market-data/eod-historical-data-subscription

## What the current public specification confirms

The public document is titled *NSE Historical Data - Order and Trade Specification*, Version 1.18, dated 18 May 2026, and is marked Non-Confidential. It describes timestamped historical order-book events and executed trades in standardized fixed-length files.

The specification distinguishes:
- Trim version: reduced field set, all order ticks, EOD availability.
- Full version: complete order-book events and executed trades, with delayed availability.
- Full Orders: available after 90 calendar days from trade date.
- Full Trades: available after 30 calendar days from trade date.

The documented order activity model includes order entry, cancellation and modification. The dataset is therefore materially more useful for historical order-book reconstruction than L1 quote snapshots or one-second OHLC/tick summaries.

Specification:
https://nsearchives.nseindia.com/web/mediaattachment/2026-05/NSE_Hist_Order_Trade_Data_1.18_20260518122214.pdf

## Important limits and licensing

- The product is paid/subscription-based; public sample links are not evidence that unrestricted production data is free.
- NSE requires a data undertaking and subscription process. Confirm permitted use, retention, display, redistribution and personal-app terms with NSE Data & Analytics before using purchased data.
- The specification's copyright notice prohibits reproducing the document without prior permission. This repository records high-level product facts only; it does not copy field layouts, offsets, sample records or proprietary material.
- The historical product is delayed by design; it is suitable for historical validation and replay, not a live scanner.
- Do not assume the historical records directly label the aggressor side. The decoder must establish whether order/trade identifiers and event linkage permit reliable side classification. Validate this against actual authorized sample files before claiming true bid/ask footprint.

## Project decision

Use this product as the first candidate for validating an event-sourced historical order-book engine, subject to entitlement and approved sample data. Do not build a parser from guessed offsets or from copied specification layouts.

Before implementation, obtain:
1. The licensed CM sample files (both Orders and Trades) and their matching trigger/metadata files.
2. Written confirmation of the exact product version and permitted processing/use.
3. A representative symbol/day with all required streams/files, including split-file cases.
4. Confirmation of how trade records link to order IDs, and how corrections/cancellations and sequence/order-time ordering are represented.

## Implementation boundary

Current repository code still accepts canonical normalized executed trades only. It does not parse NSE historical fixed-length files, reconstruct a full order book, connect to SFTP/cloud, or infer aggressor side. The next implementation must be a separate historical adapter with fixture-based tests, and must keep source provenance and data-integrity status attached to every replay.
