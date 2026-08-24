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
- real NSE/BSE universe data adapter
- broker/API adapter for live candles and market depth
- full-market ranking loop
- backtesting and walk-forward validation
- sector-relative strength and regime filter
- alerts/dashboard
- screenshot-derived feature calibration
