# Order Flow Historical Replay v1

## What this adds
- Strict canonical trade decoder for CSV, JSON and JSONL files.
- Historical replay through the existing FootprintBuilder.
- JSON output with per-bar volume, delta, POC, imbalance description and explicit descriptive-only status.
- Timezone-aware timestamps, positive price/quantity validation, explicit aggressor side, and optional trade-ID deduplication.

## Canonical input contract
Every executed trade must provide `symbol`, `timestamp` (ISO-8601 with timezone), `price`, `quantity` (positive integer), and `aggressor` (`buy` or `sell`). Optional `trade_id` supports duplicate suppression.

CSV header example:
```csv
symbol,timestamp,price,quantity,aggressor,trade_id
ABC,2026-01-01T09:15:01+05:30,100.05,25,buy,T1
ABC,2026-01-01T09:15:02+05:30,100.05,10,sell,T2
```

## Run
```bash
python -m orderflow.history path/to/trades.csv --tick-size 0.05 --bar-seconds 60 --output footprint.json
```

## Critical boundary
This package accepts a documented, normalized executed-trade feed. It does **not** decode native NSE multicast/binary packets, infer aggressor side, fetch exchange data, or connect to a vendor. A real adapter must be implemented only against the user's licensed vendor specification and sample packets. Never treat quote depth as executed volume. Replay output is historical-file analysis, not a live signal.
