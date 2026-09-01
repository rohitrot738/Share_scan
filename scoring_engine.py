from dataclasses import asdict
from config import ScannerConfig
from pattern_engine import PatternFeatures


def clamp(x, lo=0.0, hi=1.0):
    return max(lo, min(hi, x))


def rsi_score(rsi_value: float) -> float:
    """RSI branch: reward constructive momentum, avoid chasing extremes."""
    if rsi_value < 45:
        return max(0.0, rsi_value / 45.0 * 55.0)
    if rsi_value <= 60:
        return 55.0 + (rsi_value - 45.0) * (25.0 / 15.0)
    if rsi_value <= 70:
        return 80.0 + (rsi_value - 60.0) * 1.5
    if rsi_value <= 75:
        return 95.0 - (rsi_value - 70.0) * 3.0
    return max(50.0, 80.0 - (rsi_value - 75.0) * 4.0)


def score_features(f: PatternFeatures, cfg: ScannerConfig):
    impulse = clamp(f.impulse_pct / max(cfg.impulse_min_pct * 2.0, 0.1))
    tight_base = clamp(1.0 - f.consolidation_range_pct / max(cfg.consolidation_max_range_pct, 0.1))
    volume_dryup = clamp((1.15 - f.volume_dryup_ratio) / 0.65)
    proximity = clamp(1.0 - f.breakout_distance_pct / max(cfg.breakout_proximity_pct, 0.1))
    rvol = clamp(f.rvol_now / 2.5)
    rsi = rsi_score(f.rsi14)

    components = {
        'impulse_strength': 100 * impulse,
        'base_quality': 100 * (0.55 * tight_base + 0.45 * f.base_quality),
        'volume_structure': 100 * (0.65 * volume_dryup + 0.35 * rvol),
        'higher_low_strength': 100 * f.higher_low_strength,
        'trend_alignment': 100 * f.trend_alignment,
        'support_hold': 100 * f.support_hold_strength,
        'supply_exhaustion': 100 * f.supply_exhaustion,
        'breakout_proximity': 100 * proximity,
        'rsi_momentum': rsi,
    }

    # Add RSI without overpowering the existing model: 8% new weight,
    # remaining components scaled proportionally to 92%.
    base_weights = {
        'impulse_strength': 0.12,
        'base_quality': 0.18,
        'volume_structure': 0.18,
        'higher_low_strength': 0.10,
        'trend_alignment': 0.10,
        'support_hold': 0.10,
        'supply_exhaustion': 0.10,
        'breakout_proximity': 0.12,
    }
    weights = {k: v * 0.92 for k, v in base_weights.items()}
    weights['rsi_momentum'] = 0.08

    total = sum(components[k] * weights[k] for k in components)

    if total >= cfg.min_score_confirmed and f.rvol_now >= cfg.breakout_rvol_min:
        state = 'CONFIRMED'
    elif total >= cfg.min_score_ready:
        state = 'READY'
    elif total >= cfg.min_score_watch:
        state = 'EARLY'
    else:
        state = 'IGNORE'

    false_break_risk = 100 - (
        0.30 * components['support_hold'] +
        0.25 * components['supply_exhaustion'] +
        0.25 * components['trend_alignment'] +
        0.20 * components['base_quality']
    )

    return {
        'score': round(total, 2),
        'state': state,
        'false_breakout_risk': round(max(0.0, min(100.0, false_break_risk)), 2),
        'components': {k: round(v, 2) for k, v in components.items()},
        'raw_features': asdict(f),
    }
