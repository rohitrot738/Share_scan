from typing import Dict
import pandas as pd
from config import ScannerConfig
from pattern_engine import extract_features
from scoring_engine import score_features

TF_WEIGHTS = {'5m': 0.25, '15m': 0.35, '30m': 0.15, '1h': 0.25}


def analyse_timeframes(data: Dict[str, pd.DataFrame], cfg: ScannerConfig):
    per_tf = {}
    weighted = 0.0
    used = 0.0

    for tf, df in data.items():
        features = extract_features(df, cfg)
        score = score_features(features, cfg)
        per_tf[tf] = score
        weight = TF_WEIGHTS.get(tf, 0.10)
        weighted += score['score'] * weight
        used += weight

    final_score = weighted / used if used else 0.0
    states = [x['state'] for x in per_tf.values()]

    if final_score >= 88 and 'CONFIRMED' in states:
        final_state = 'CONFIRMED'
    elif final_score >= 78:
        final_state = 'READY'
    elif final_score >= 65:
        final_state = 'EARLY'
    else:
        final_state = 'IGNORE'

    return {
        'final_score': round(final_score, 2),
        'final_state': final_state,
        'timeframes': per_tf,
    }
