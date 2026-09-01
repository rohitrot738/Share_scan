from dataclasses import asdict
from pathlib import Path
from config import ScannerConfig
from pattern_engine import PatternFeatures

ACTIVE_FILE = Path('active_indicator.txt')

def clamp(x, lo=0.0, hi=1.0):
    return max(lo, min(hi, x))

def rsi_score(v: float) -> float:
    if v < 45: return max(0.0, v/45.0*55.0)
    if v <= 60: return 55.0 + (v-45.0)*(25.0/15.0)
    if v <= 70: return 80.0 + (v-60.0)*1.5
    if v <= 75: return 95.0 - (v-70.0)*3.0
    return max(50.0, 80.0 - (v-75.0)*4.0)

def active_indicators() -> list[str]:
    if not ACTIVE_FILE.exists():
        return []
    raw=ACTIVE_FILE.read_text(encoding='utf-8').replace('\n', ',')
    names=[]
    for part in raw.split(','):
        name=part.strip().lower()
        if name and name != 'none' and name not in names:
            names.append(name)
    return names

def active_indicator() -> str:
    names=active_indicators()
    return ','.join(names) if names else 'none'

def branch_score(name: str, f: PatternFeatures) -> float:
    if name == 'macd': return 88.0 if f.macd_hist > 0 else 32.0
    if name == 'vwap':
        g=f.vwap_gap_pct
        return 92.0 if 0 <= g <= 2 else 78.0 if 2 < g <= 4 else 55.0 if -1 <= g < 0 else 30.0
    if name == 'bollinger':
        p=f.bb_position
        return 90.0 if 0.55 <= p <= 0.90 else 70.0 if 0.40 <= p <= 1.0 else 35.0
    if name == 'stochastic':
        k=f.stoch_k
        return 90.0 if 50 <= k <= 80 else 72.0 if 35 <= k < 50 else 45.0 if 80 < k <= 90 else 25.0
    if name == 'adx-dmi':
        a=f.adx14
        return 92.0 if 25 <= a <= 45 else 72.0 if 20 <= a < 25 else 55.0 if a > 45 else 30.0
    if name == 'cci':
        c=f.cci20
        return 90.0 if 0 <= c <= 150 else 70.0 if -50 <= c < 0 else 45.0 if 150 < c <= 220 else 25.0
    if name == 'mfi':
        m=f.mfi14
        return 90.0 if 50 <= m <= 80 else 70.0 if 35 <= m < 50 else 45.0 if 80 < m <= 90 else 25.0
    if name == 'obv': return 88.0 if f.obv_slope > 0 else 32.0
    if name == 'roc':
        r=f.roc12
        return 90.0 if 0 <= r <= 6 else 70.0 if -2 <= r < 0 else 45.0 if 6 < r <= 10 else 25.0
    if name == 'atr':
        a=f.atr_pct
        return 88.0 if 0.8 <= a <= 3.5 else 68.0 if 0.4 <= a < 0.8 else 45.0 if 3.5 < a <= 5.0 else 25.0
    if name == 'supertrend': return 90.0 if f.supertrend_dir > 0 else 25.0
    if name == 'ichimoku': return 90.0 if f.ichimoku_bull > 0 else 30.0
    if name == 'pivots':
        g=f.pivot_gap_pct
        return 90.0 if 0 <= g <= 2.5 else 70.0 if -1 <= g < 0 else 45.0 if 2.5 < g <= 5 else 25.0
    if name == 'support-resistance':
        return 100.0 * (0.55*f.support_hold_strength + 0.45*clamp(1.0-f.breakout_distance_pct/5.0))
    return 50.0

def score_features(f: PatternFeatures, cfg: ScannerConfig):
    impulse = clamp(f.impulse_pct / max(cfg.impulse_min_pct * 2.0, 0.1))
    tight_base = clamp(1.0 - f.consolidation_range_pct / max(cfg.consolidation_max_range_pct, 0.1))
    volume_dryup = clamp((1.15 - f.volume_dryup_ratio) / 0.65)
    proximity = clamp(1.0 - f.breakout_distance_pct / max(cfg.breakout_proximity_pct, 0.1))
    rvol = clamp(f.rvol_now / 2.5)
    components = {
        'impulse_strength': 100*impulse,
        'base_quality': 100*(0.55*tight_base+0.45*f.base_quality),
        'volume_structure': 100*(0.65*volume_dryup+0.35*rvol),
        'higher_low_strength': 100*f.higher_low_strength,
        'trend_alignment': 100*f.trend_alignment,
        'support_hold': 100*f.support_hold_strength,
        'supply_exhaustion': 100*f.supply_exhaustion,
        'breakout_proximity': 100*proximity,
        'rsi_momentum': rsi_score(f.rsi14),
    }
    base_weights={'impulse_strength':.12,'base_quality':.18,'volume_structure':.18,'higher_low_strength':.10,'trend_alignment':.10,'support_hold':.10,'supply_exhaustion':.10,'breakout_proximity':.12}
    names=active_indicators()
    if names:
        pool=min(0.24, 0.08 + 0.02*(len(names)-1))
        base_pool=0.92-pool
        weights={k:v*base_pool for k,v in base_weights.items()}
        weights['rsi_momentum']=.08
        per=pool/len(names)
        for name in names:
            key=f'branch_{name}'
            components[key]=branch_score(name,f)
            weights[key]=per
    else:
        weights={k:v*.92 for k,v in base_weights.items()}; weights['rsi_momentum']=.08
    total=sum(components[k]*weights[k] for k in weights)
    if total>=cfg.min_score_confirmed and f.rvol_now>=cfg.breakout_rvol_min: state='CONFIRMED'
    elif total>=cfg.min_score_ready: state='READY'
    elif total>=cfg.min_score_watch: state='EARLY'
    else: state='IGNORE'
    false_break_risk=100-(.30*components['support_hold']+.25*components['supply_exhaustion']+.25*components['trend_alignment']+.20*components['base_quality'])
    return {'score':round(total,2),'state':state,'false_breakout_risk':round(max(0.0,min(100.0,false_break_risk)),2),'active_indicator':active_indicator(),'components':{k:round(v,2) for k,v in components.items()},'raw_features':asdict(f)}
