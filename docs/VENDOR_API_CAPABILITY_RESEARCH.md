# Vendor API Capability Check (November 2026)

This is a public-documentation review, not a vendor endorsement or subscription recommendation.

## TrueData

Public TrueData API product information describes its Live Data API as L1 with single best bid/ask details. Its pricing page explicitly says programmatic API access is separate from Velocity plans and requires a separate application, compliance review and approval.

- API product: https://www.truedata.in/products/marketdataapi
- Pricing / API access note: https://www.truedata.in/information/pricing
- WebSocket API docs: https://truedataapi.readme.io/reference/websocket-api

TrueData's separate FutPrint terminal advertises footprint charts, bid/ask classification, CVD and order-flow tools. Its market-data page says raw feeds are licensed separately from the terminal and directs users to contact TrueData for raw feeds.

- FutPrint: https://futprint.truedata.in/
- FutPrint data coverage: https://futprint.truedata.in/market-data

Conclusion: FutPrint demonstrates that the provider has a footprint product, but the public API pages reviewed do not establish that a customer API subscription exposes the underlying tick-by-tick order/trade events required by this repository. Obtain written confirmation and sample API messages before building an adapter.

## Global Datafeeds (GFDL)

Its public API documentation describes NSE Cash real-time data as L1, with single best bid/ask, and realtime updates at one-second frequency. It also lists tick history as one-second periodicity in the described NSE CM historical offering.

- API overview: https://globaldatafeeds.in/apis/
- Data availability: https://globaldatafeeds.in/global-datafeeds-apis/global-datafeeds-apis/introduction/type-of-data-available/
- Supported exchanges: https://globaldatafeeds.in/global-datafeeds-apis/global-datafeeds-apis/introduction/supported-exchanges/
- WebSocket docs: https://globaldatafeeds.in/global-datafeeds-apis/documentation-support/documentation/websockets-api-documentation/

Conclusion: the public API description is not enough to claim full order-by-order MTBT data or true bid/ask executed-volume footprint. Ask GFDL whether a separate full-depth/order-event product exists for the required NSE CM segment.

## Decision for this project

Do not select a provider based only on the words “real-time”, “tick”, or “market depth”. For the target footprint engine, request a sample payload and confirm all of the following in writing:
1. Per-trade execution price, quantity and exchange timestamp (not 1-second snapshots).
2. Buy and sell order IDs or a documented aggressor-side field.
3. Order add/modify/cancel events if aggressor side must be reconstructed.
4. Sequence numbers and gap recovery / snapshot synchronization.
5. Full NSE CM symbol coverage, subscription limits, historical retention and redistribution/display permissions.

A vendor offering only L1 best bid/ask snapshots can support a quote/price scanner, but cannot by itself support a verified order-by-order footprint. No paid service has been selected or purchased, and no credentials are configured.
