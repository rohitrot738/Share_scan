from typing import Dict
import pandas as pd
from config import ScannerConfig
from pattern_engine import extract_features
from scoring_engine import score_features

# Higher-timeframe context + intraday trigger weights.
TF_WEIGHTS = {
    '1m': 0.05,
    '5m': 0.15,
    '15m': 0.15,
    '30m': 0.10,
    '1h': 0.15,
    '4h': 0.10,
    '1d': 0.20,
    '1w': 0.10,
}

# Screenshot-trained reference profile: Yatharth Hospital, 2026-08-24.
# The purpose is NOT to memorize the stock; it encodes the structure visible
# immediately around a strong expansion so similar PRE-breakout setups score up.
YATHARTH_REFERENCE_PROFILE = {
    'symbol': 'YATHARTH',
    'reference_price_zone': (895.0, 900.0),
    'pre_breakout_base_zone': (845.0, 865.0),
    'intraday_spike_zone': (900.0, 915.0),
    'observations': {
        '1w': 'strong primary uptrend; price pressing prior high zone',
        '1d': 'higher-high/higher-low structure near major resistance',
        '1h': 'repeated compression roughly 820-870 followed by expansion',
        '30m': 'base/reclaim around 840-860 before vertical move',
        '15m': 'tightening near 850-860 then momentum expansion',
        '5m': 'range around 850-860 followed by high-volume breakout impulse',
        '1m': 'sharp 856-to-910 expansion; chase risk after vertical candle',
    },
}


def _pre_breakout_bonus(tf: str, df: pd.DataFrame) -> float:
    """Generic bonus for compression + pressure + volume expansion.

    Uses only OHLCV columns when available. It intentionally rewards the setup
    before/at breakout and penalizes an already overextended vertical move.
    """
    if df is None or len(df) < 25:
        return 0.0
    required = {'high', 'low', 'close', 'volume'}
    if not required.issubset({str(c).lower() for c in df.columns}):
        return 0.0

    x = df.copy()
    x.columns = [str(c).lower() for c in x.columns]
    x = x.dropna(subset=['high', 'low', 'close', 'volume'])
    if len(x) < 25:
        return 0.0

    recent = x.iloc[-20:]
    close = float(x['close'].iloc[-1])
    prior_high = float(x['high'].iloc[-21:-1].max())
    recent_low = float(recent['low'].min())
    width_pct = (prior_high - recent_low) / max(close, 1e-9) * 100.0
    distance_pct = (prior_high - close) / max(prior_high, 1e-9) * 100.0

    vol_base = float(x['volume'].iloc[-21:-1].median())
    vol_ratio = float(x['volume'].iloc[-1]) / max(vol_base, 1.0)
    one_bar_move = abs(float(x['close'].iloc[-1]) / max(float(x['close'].iloc[-2]), 1e-9) - 1.0) * 100.0

    bonus = 0.0
    # Tight/controlled base rather than a loose structure.
    if width_pct <= 8.0:
        bonus += 3.0
    elif width_pct <= 12.0:
        bonus += 1.5

    # Price sitting just below resistance is the key pre-breakout condition.
    if 0.0 <= distance_pct <= 1.5:
        bonus += 5.0
    elif 1.5 < distance_pct <= 3.0:
        bonus += 3.0

    # Participation should start expanding as price attacks resistance.
    if vol_ratio >= 2.0:
        bonus += 4.0
    elif vol_ratio >= 1.35:
        bonus += 2.0

    # Avoid learning to buy after the sort of vertical 1m/5m expansion seen
    # in the reference screenshots; flag it as chase/extension risk instead.
    extension_limit = 3.0 if tf in {'1m', '5m'} else 5.0
    if one_bar_move >= extension_limit:
        bonus -= 5.0

    return bonus


def analyse_timeframes(data: Dict[str, pd.DataFrame], cfg: ScannerConfig):
    per_tf = {}
    weighted = 0.0
    used = 0.0

    for tf, df in data.items():
        features = extract_features(df, cfg)
        score = score_features(features, cfg)

        # Add screenshot-derived generic pre-breakout confirmation without
        # replacing the existing pattern/scoring engines.
        bonus = _pre_breakout_bonus(tf, df)
        score = dict(score)
        score['base_score'] = score.get('score', 0.0)
        score['pre_breakout_bonus'] = round(bonus, 2)
        score['score'] = round(max(0.0, min(100.0, score['base_score'] + bonus)), 2)
        per_tf[tf] = score

        weight = TF_WEIGHTS.get(tf, 0.05)
        weighted += score['score'] * weight
        used += weight

    final_score = weighted / used if used else 0.0
    states = [x['state'] for x in per_tf.values()]

    # Require stronger agreement before calling a setup confirmed.
    high_tf_scores = [per_tf[t]['score'] for t in ('1h', '4h', '1d', '1w') if t in per_tf]
    trigger_scores = [per_tf[t]['score'] for t in ('5m', '15m', '30m') if t in per_tf]
    high_tf_ok = (sum(high_tf_scores) / len(high_tf_scores) >= 72) if high_tf_scores else True
    trigger_ok = (sum(trigger_scores) / len(trigger_scores) >= 72) if trigger_scores else True

    if final_score >= 88 and 'CONFIRMED' in states and high_tf_ok and trigger_ok:
        final_state = 'CONFIRMED'
    elif final_score >= 78 and high_tf_ok:
        final_state = 'READY'
    elif final_score >= 65:
        final_state = 'EARLY'
    else:
        final_state = 'IGNORE'

    return {
        'final_score': round(final_score, 2),
        'final_state': final_state,
        'high_tf_ok': high_tf_ok,
        'trigger_ok': trigger_ok,
        'reference_profile': 'YATHARTH_PRE_BREAKOUT_2026_08_24',
        'timeframes': per_tf,
    }
