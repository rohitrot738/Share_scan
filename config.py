from dataclasses import dataclass

@dataclass
class ScannerConfig:
    impulse_lookback: int = 20
    impulse_min_pct: float = 2.5
    consolidation_lookback: int = 10
    consolidation_max_range_pct: float = 3.0
    volume_short_window: int = 5
    volume_long_window: int = 20
    volume_dryup_ratio: float = 0.75
    breakout_rvol_min: float = 1.5
    atr_window: int = 14
    breakout_proximity_pct: float = 1.2
    min_score_watch: float = 65
    min_score_ready: float = 78
    min_score_confirmed: float = 88
