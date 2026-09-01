# Share_scan — Ghost Move Pro

CPU-first multi-stock pre-breakout scanner inspired by Ghost Trade Pro.

## Goal
Detect the preparation phase before a fast bullish move across NSE/BSE instead of waiting for a late breakout signal.

## Ghost Trade Pro equipment now included
- demand / supply zone engine
- order-flow proxy from OHLCV
- buyer/seller pressure and absorption score
- abnormal-volume / possible large-participation detector
- VWAP engine
- opening-range breakout (ORB)
- low-volume pullback detection
- green confirmation logic
- false-breakout filter
- upper-wick rejection and failed-break detection
- entry-zone engine
- invalidation / stop-loss engine
- multi-target risk-reward engine
- relative-strength module for benchmark comparison
- integrated `ghost_trade_core.py` snapshot engine

Important: OHLCV cannot reveal the true exchange order book or actual hidden/bulk orders. The current bulk/order-flow modules are evidence-based proxies. A broker/exchange market-depth feed can later be attached to replace/augment them with real bid/ask depth and trades.

## Live NSE/BSE pipeline

`live_scan.py` now provides a two-stage full-market scan designed for 1–2 week swing candidates.

### Stage 1 — whole market prefilter
- loads the NSE equity universe from the official NSE equity CSV
- attempts to load active BSE equity scrips from the BSE API
- downloads daily OHLCV in batches
- scores trend, proximity to 20-day resistance, relative volume, 5/20-day momentum, base width and liquidity
- keeps only the strongest configurable shortlist

### Stage 2 — Ghost ranking
For the Stage-1 shortlist it downloads 15m, 30m, 1h, 1d and 1w candles and derives 4h candles from 1h data. It then runs the existing multi-timeframe engine plus `ghost_trade_snapshot`, applies a false-breakout penalty and ranks the final candidates.

Outputs are written to:
- `scan_output/top10.csv`
- `scan_output/top10.json`
- `scan_output/stage1_shortlist.csv`

### Run locally

```bash
pip install -r requirements.txt
python live_scan.py --top 10 --shortlist 120
```

NSE only:

```bash
python live_scan.py --top 10 --shortlist 120 --nse-only
```

BSE only:

```bash
python live_scan.py --top 10 --shortlist 120 --bse-only
```

If BSE blocks its public endpoint, an optional CSV can be supplied through `EXTRA_SYMBOLS_FILE`. The CSV columns are `symbol,exchange,yahoo_symbol,name`; `yahoo_symbol` is optional and defaults to `.NS` for NSE or `.BO` for BSE.

### GitHub Actions
Run **Actions → Live Market Scan → Run workflow**. Choose `top`, `shortlist` and `market`. The completed run uploads `share-scan-results` containing the CSV/JSON outputs.

The workflow also performs syntax and import checks on pull requests.

## Ghost Move Pro foundation
- 5m / 15m / 30m / 1h multi-timeframe analysis
- impulse detection
- tight-base / consolidation detection
- relative volume and volume dry-up
- higher-low strength
- trend alignment
- support holding
- supply exhaustion heuristic
- breakout proximity
- false-breakout risk
- 0–100 setup score
- EARLY / READY / CONFIRMED / IGNORE states

## Calibration plan
This remains the foundation rather than the final calibrated strategy. Thresholds, weights and filters will be refined from 50–100 labelled chart screenshots across multiple timeframes, including both successful moves and failed setups. The purpose is to learn the common pre-move structure, not to hard-code one stock or one exact chart shape.

## Next layers
- broker/exchange market-depth adapter for true bid/ask and trades
- backtesting and walk-forward validation
- sector-relative strength and regime filter
- alerts/dashboard
- screenshot-derived feature calibration

## Live candlestick chart

The scanner includes a mobile-friendly live chart for NSE symbols. It supports
`1m`, `2m`, `3m`, `5m`, `10m`, `15m`, `30m`, `1h`, `4h`, `1d`, and `1w`.
The symbol list first uses the current `scan_output/top100_by_volume.csv`, then
the saved `scan_results/latest.csv`. Historical candles come from Yahoo; live
LTP updates come from Groww when `GROWW_ACCESS_TOKEN` is configured server-side.

```bash
pip install -r requirements.txt
python live_chart_server.py
```

Open `http://127.0.0.1:8787`. The Groww token is never sent to the browser.
