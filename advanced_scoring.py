from scoring_engine import clamp, rsi_score

def score_features_advanced(f, cfg):
    impulse=clamp(f.impulse_pct/max(cfg.impulse_min_pct*2.0,.1)); tight=clamp(1.0-f.consolidation_range_pct/max(cfg.consolidation_max_range_pct,.1)); dry=clamp((1.15-f.volume_dryup_ratio)/.65); prox=clamp(1.0-f.breakout_distance_pct/max(cfg.breakout_proximity_pct,.1)); rv=clamp(f.rvol_now/2.5)
    def centered(x,s): return 50+clamp(x/s,-1,1)*50
    c={'impulse_strength':100*impulse,'base_quality':100*(.55*tight+.45*f.base_quality),'volume_structure':100*(.65*dry+.35*rv),'higher_low_strength':100*f.higher_low_strength,'trend_alignment':100*f.trend_alignment,'support_hold':100*f.support_hold_strength,'supply_exhaustion':100*f.supply_exhaustion,'breakout_proximity':100*prox,'rsi_momentum':rsi_score(f.rsi14),'macd_momentum':centered(f.macd_hist,1),'vwap_position':centered(f.vwap_gap_pct,3),'bb_position':100*clamp(f.bb_position),'stoch_momentum':100*clamp(f.stoch_k/100),'adx_trend':100*clamp(f.adx14/40),'cci_momentum':centered(f.cci20,200),'mfi_flow':100*clamp(f.mfi14/100),'obv_trend':100 if f.obv_slope>0 else 0,'roc_momentum':centered(f.roc12,10),'supertrend':100 if f.supertrend_dir>0 else 0,'ichimoku':100*f.ichimoku_bull,'pivot_position':centered(f.pivot_gap_pct,5)}
    w={'impulse_strength':.07,'base_quality':.10,'volume_structure':.10,'higher_low_strength':.06,'trend_alignment':.07,'support_hold':.06,'supply_exhaustion':.06,'breakout_proximity':.07,'rsi_momentum':.06,'macd_momentum':.05,'vwap_position':.05,'bb_position':.04,'stoch_momentum':.04,'adx_trend':.05,'cci_momentum':.03,'mfi_flow':.03,'obv_trend':.03,'roc_momentum':.03,'supertrend':.04,'ichimoku':.04,'pivot_position':.02}
    total=sum(c[k]*w[k] for k in c)
    state='CONFIRMED' if total>=cfg.min_score_confirmed and f.rvol_now>=cfg.breakout_rvol_min else ('READY' if total>=cfg.min_score_ready else ('EARLY' if total>=cfg.min_score_watch else 'IGNORE'))
    risk=100-(.30*c['support_hold']+.25*c['supply_exhaustion']+.25*c['trend_alignment']+.20*c['base_quality'])
    return {'score':round(total,2),'state':state,'false_breakout_risk':round(max(0,min(100,risk)),2),'components':{k:round(v,2) for k,v in c.items()}}
