"""Yatharth Hospital screenshot training case.

Keeps the human-labelled reference separate from the generic engine.
Do not import absolute prices into production scoring; use the normalized
behavioural observations and thresholds only.
"""

CASE_ID = "YATHARTH_PRE_BREAKOUT_2026_08_24"
SYMBOL = "YATHARTH"
LABEL = "SUCCESSFUL_PRE_BREAKOUT_EXPANSION"

REFERENCE = {
    "timeframes_seen": ["1m", "5m", "15m", "30m", "1h", "1d", "1w"],
    "reference_price_zone": (895.0, 900.0),
    "pre_breakout_base_zone": (845.0, 865.0),
    "intraday_spike_zone": (900.0, 915.0),
    "observations": {
        "1w": "primary uptrend pressing prior high zone",
        "1d": "higher-high/higher-low structure near major resistance",
        "1h": "repeated compression around the upper base before expansion",
        "30m": "base and reclaim before vertical move",
        "15m": "tightening near resistance then momentum expansion",
        "5m": "range compression followed by high-volume breakout impulse",
        "1m": "vertical expansion after trigger; late chase risk increases sharply",
    },
}

# Generic, stock-independent lessons extracted from screenshots.
GENERIC_RULES = {
    "reward_tight_base": True,
    "reward_price_near_resistance": True,
    "reward_volume_expansion_on_attack": True,
    "require_higher_timeframe_alignment": True,
    "require_intraday_trigger_alignment": True,
    "penalize_vertical_extension": True,
    "extension_limit_pct": {"1m": 3.0, "5m": 3.0, "default": 5.0},
    "resistance_proximity_pct": {"strong": 1.5, "moderate": 3.0},
    "volume_ratio": {"strong": 2.0, "moderate": 1.35},
    "base_width_pct": {"strong": 8.0, "moderate": 12.0},
}
