# NSE Order Flow / Footprint v1

## Scope
This isolated package adds deterministic footprint aggregation and descriptive metrics. It does not alter the existing scanner or main branch.

## Input contract
Each record must contain symbol, exchange timestamp, execution price, quantity, unique trade ID when supplied, and aggressor side (buy/sell) classified from authoritative feed semantics. This engine never infers aggressor side from candle colour, OHLCV, or quote snapshots. Confirm the precise NSE/vendor message schema before writing a decoder.

## Metrics
- Ask volume: executed volume classified as buyer-initiated.
- Bid volume: executed volume classified as seller-initiated.
- Delta = ask volume minus bid volume.
- Delta percent = delta divided by total volume times 100.
- POC: price level with greatest classified executed volume.
- Diagonal imbalance: configurable ratio comparison against an adjacent observed price level.

## Limitations
- Depth snapshots are resting liquidity, not executed volume; never add them to footprint trade volume.
- A footprint bar alone cannot prove absorption, iceberg orders, institutional identity, or intent. Absorption requires price response and multi-bar context.
- No live NSE data is fetched by v1. The adapter fails closed until authorized entitlement and a vendor-specific transport/decoder are configured.
- Demo trades are synthetic and must never be presented as market data or scanner signals.

## Tests and demo
Run tests: python -m unittest discover -s tests -p 'test_orderflow_*.py'
Run synthetic demo: python -m orderflow.demo

## Next gate
Obtain the NSE/vendor product specification, entitlement, permitted usage, network requirements, message definitions, and historical-data access. Implement and validate the decoder against vendor sample packets before enabling live scanning.
