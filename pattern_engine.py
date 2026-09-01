from dataclasses import dataclass
import numpy as np
import pandas as pd
from indicators import add_basic_indicators
from config import ScannerConfig

@dataclass
class PatternFeatures:
    impulse_pct: float; consolidation_range_pct: float; volume_dryup_ratio: float; breakout_distance_pct: float; higher_low_strength: float; trend_alignment: float; rvol_now: float; rsi14: float; macd_hist: float; vwap_gap_pct: float; bb_position: float; stoch_k: float; adx14: float; cci20: float; mfi14: float; obv_slope: float; roc12: float; atr_pct: float; supertrend_dir: float; ichimoku_bull: float; pivot_gap_pct: float; support_hold_strength: float; supply_exhaustion: float; base_quality: float; resistance: float; support: float

def _pct(a,b): return 0.0 if b==0 or np.isnan(b) else (a/b)*100.0

def extract_features(df,cfg):
    if len(df)<60: raise ValueError('Need at least 60 candles.')
    d=add_basic_indicators(df,cfg.atr_window).dropna().copy(); recent=d.iloc[-cfg.consolidation_lookback:]; pre=d.iloc[-(cfg.impulse_lookback+cfg.consolidation_lookback):-cfg.consolidation_lookback]
    il,ih=float(pre.low.min()),float(pre.high.max()); impulse_pct=_pct(ih-il,il); bh,bl=float(recent.high.max()),float(recent.low.min()); mid=(bh+bl)/2; consolidation_range_pct=_pct(bh-bl,mid)
    sv=float(recent.volume.tail(cfg.volume_short_window).mean()); lv=float(d.volume.tail(cfg.volume_long_window).mean()); volume_dryup_ratio=1.0 if lv==0 else sv/lv; close=float(d.close.iloc[-1]); breakout_distance_pct=max(0.0,_pct(bh-close,close)); lows=recent.low.tail(5).values; higher_low_strength=float((np.diff(lows)>=0).mean()) if len(lows)>=4 else 0.0
    e9,e20,e50=map(float,[d.ema9.iloc[-1],d.ema20.iloc[-1],d.ema50.iloc[-1]]); trend_alignment=sum([close>e9,e9>e20,e20>e50])/3; rvol_now=float(d.rvol20.iloc[-1]); rsi14=float(d.rsi14.iloc[-1]); macd_hist=float(d.macd_hist.iloc[-1]); vwap_gap_pct=_pct(close-float(d.vwap.iloc[-1]),float(d.vwap.iloc[-1])); bb_range=float(d.bb_upper.iloc[-1]-d.bb_lower.iloc[-1]); bb_position=0.5 if bb_range==0 else (close-float(d.bb_lower.iloc[-1]))/bb_range; stoch_k=float(d.stoch_k.iloc[-1]); adx14=float(d.adx14.iloc[-1]); cci20=float(d.cci20.iloc[-1]); mfi14=float(d.mfi14.iloc[-1]); obv_slope=float(d.obv.iloc[-1]-d.obv.iloc[-6]); roc12=float(d.roc12.iloc[-1]); atr_pct=_pct(float(d.atr.iloc[-1]),close); supertrend_dir=float(d.supertrend_dir.iloc[-1]); ia=float(d.ichimoku_a.iloc[-1]); ib=float(d.ichimoku_b.iloc[-1]); ichimoku_bull=float(close>max(ia,ib)); pivot=float(d['pivot'].iloc[-1]); pivot_gap_pct=_pct(close-pivot,pivot)
    support_hold=float((recent.close>=bl+0.5*(bh-bl)).mean()); reds=recent[recent.close<recent.open]; supply=((float(reds.volume.iloc[-1]<=reds.volume.iloc[0])+float(reds.body.iloc[-1]<=reds.body.iloc[0]))/2) if len(reds)>=2 else 0.5; tight=max(0.0,1.0-consolidation_range_pct/max(cfg.consolidation_max_range_pct,0.1)); shallow=max(0.0,min(1.0,(close-bl)/max(bh-bl,1e-9))); base=0.6*tight+0.4*shallow
    return PatternFeatures(impulse_pct,consolidation_range_pct,volume_dryup_ratio,breakout_distance_pct,higher_low_strength,trend_alignment,rvol_now,rsi14,macd_hist,vwap_gap_pct,bb_position,stoch_k,adx14,cci20,mfi14,obv_slope,roc12,atr_pct,supertrend_dir,ichimoku_bull,pivot_gap_pct,support_hold,supply,base,bh,bl)
