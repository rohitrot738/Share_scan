# Share_scan — Ghost Move Pro

CPU-first multi-stock pre-breakout scanner inspired by Ghost Trade Pro.

## Goal
Detect the preparation phase before a fast bullish move across NSE/BSE instead of waiting for a late breakout signal.

## Current foundation
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
This is only the base engine. Thresholds, weights and filters will be refined from 50–100 labelled chart screenshots across multiple timeframes, including both successful moves and failed setups. The purpose is to learn the common pre-move structure, not to hard-code one stock or one exact chart shape.
