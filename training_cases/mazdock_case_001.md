# Training Case 001 — MAZDOCK

Source: user-supplied chart screenshots across 1m, 5m, 15m, 30m, and 1h.

## Purpose
This case is used to teach the scanner the market behaviour around a strong bullish expansion and the later post-expansion distribution/mean-reversion phase. It is not a single-shape template.

## Observed bullish pre-move DNA
- Higher-timeframe impulse from a lower base into a new value area.
- After the first impulse, price does not fully retrace the expansion; it holds a relatively elevated zone.
- Tight consolidation develops below/around prior local resistance.
- Pullbacks become shallower and increasingly fail to create sustained lower lows.
- Volume contracts during consolidation compared with the impulse leg.
- Repeated tests near resistance are absorbed instead of causing a major rejection.
- Breakout occurs with a marked increase in participation/volume and wide-range bullish candles.
- The strongest signal is the sequence: impulse -> hold -> compression -> absorption -> expansion.

## Multi-timeframe interpretation
### 1h
- Best for regime and structural context.
- Detects impulse leg, elevated base, resistance shelf, and eventual expansion.
- Should carry heavy weight for trend qualification, but should not be used alone for entry timing.

### 30m / 15m
- Best for identifying the compression/base, repeated resistance tests, shallow pullbacks, and breakout readiness.
- These frames should contribute heavily to the PRE-BREAKOUT score.

### 5m
- Best for timing re-acceleration, micro higher lows, low-volume pullback, and first volume expansion.
- Should be used for execution confirmation, not as the sole directional filter.

### 1m
- Useful only as a microstructure proxy when real market-depth/order-flow data are unavailable.
- Shows noise, stop runs, failed pushes, and post-breakout distribution.
- Must receive a lower weight to avoid overfitting to noise.

## Post-move / failure DNA visible in the later screenshots
- After the expansion, 1m and 5m become much noisier and show repeated spikes/rejections.
- Lower highs begin to form after exhaustion near the highs.
- Sharp upside probes fail to hold, followed by quick retracements.
- Distribution/chop replaces clean compression.
- A scanner must distinguish this phase from the earlier clean pre-breakout base.

## Features to encode from this case
1. Impulse strength normalized by ATR and percentage move.
2. Retention ratio: how much of the impulse is held during the base.
3. Base width as % and ATR multiple.
4. Volume contraction during the base vs impulse volume.
5. Resistance test count.
6. Rejection decay: whether successive rejection distances become smaller.
7. Pullback depth decay / higher-low quality.
8. Time spent near the top half of the base.
9. Breakout proximity without already being overextended.
10. Breakout RVOL / wide-range confirmation.
11. False-breakout penalty for upper-wick expansion without close acceptance.
12. Post-expansion exhaustion penalty when lower-timeframe spikes and lower highs dominate.
13. Multi-timeframe agreement score, with 15m/30m/1h weighted more than 1m.

## Label
Primary pattern family: PRE_BREAKOUT_COMPRESSION_AFTER_IMPULSE
Secondary labels: RESISTANCE_ABSORPTION, LOW_VOLUME_PULLBACK, RANGE_COMPRESSION, BREAKOUT_EXPANSION

## Notes
This case alone must not define final thresholds. Thresholds should be learned/calibrated after many winner and failure cases are added.
