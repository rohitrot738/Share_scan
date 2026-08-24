"""Advanced cross-confirmation engine for Ghost Trade Pro.

The goal is not line-count padding. This module adds independent confirmation
families that can veto, downgrade or strengthen a setup. It only uses signals
that can be derived from OHLCV data and never fabricates unavailable order-book
information.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any, Dict, List, Mapping
import math
import numpy as np
import pandas as pd


@dataclass
class ConfirmationResult:
    score: float
    confidence: float
    bullish_votes: int
    bearish_votes: int
    neutral_votes: int
    veto_count: int
    regime: str
    strongest_family: str


def _clip(v, lo=0.0, hi=100.0):
    return float(max(lo, min(hi, float(v))))


def _safe_div(a, b, default=0.0):
    try:
        if b is None or abs(float(b)) < 1e-12:
            return default
        return float(a) / float(b)
    except Exception:
        return default


def _ema(s: pd.Series, n: int):
    return s.ewm(span=n, adjust=False).mean()


def _sma(s: pd.Series, n: int):
    return s.rolling(n).mean()


def _atr(d: pd.DataFrame, n: int = 14):
    pc = d['close'].shift(1)
    tr = pd.concat([(d['high']-d['low']).abs(), (d['high']-pc).abs(), (d['low']-pc).abs()], axis=1).max(axis=1)
    return tr.rolling(n).mean()


def _rsi(s: pd.Series, n: int = 14):
    delta=s.diff(); up=delta.clip(lower=0); dn=-delta.clip(upper=0)
    rs=up.ewm(alpha=1/n,adjust=False).mean()/dn.ewm(alpha=1/n,adjust=False).mean().replace(0,np.nan)
    return 100-(100/(1+rs))


def _zscore(s: pd.Series, n: int = 20):
    m=s.rolling(n).mean(); sd=s.rolling(n).std(ddof=0)
    return (s-m)/sd.replace(0,np.nan)


def _slope(s: pd.Series, n: int = 10):
    x=np.arange(n,dtype=float)
    def f(a):
        if len(a)<n or np.isnan(a).any(): return np.nan
        return float(np.polyfit(x,a,1)[0])
    return s.rolling(n).apply(f,raw=True)


def enrich(df: pd.DataFrame) -> pd.DataFrame:
    d=df.copy()
    for c in ['open','high','low','close','volume']:
        d[c]=pd.to_numeric(d[c],errors='coerce')
    d=d.dropna(subset=['open','high','low','close','volume']).reset_index(drop=True)
    d['range']=(d['high']-d['low']).replace(0,np.nan)
    d['body']=(d['close']-d['open']).abs()
    d['body_pct']=d['body']/d['range']
    d['upper_wick']=d['high']-d[['open','close']].max(axis=1)
    d['lower_wick']=d[['open','close']].min(axis=1)-d['low']
    d['upper_wick_pct']=d['upper_wick']/d['range']
    d['lower_wick_pct']=d['lower_wick']/d['range']
    d['green']=(d['close']>d['open']).astype(float)
    d['red']=(d['close']<d['open']).astype(float)
    d['ret']=d['close'].pct_change()*100
    d['atr14']=_atr(d,14)
    d['atr_pct']=d['atr14']/d['close']*100
    d['ema5']=_ema(d['close'],5); d['ema9']=_ema(d['close'],9); d['ema20']=_ema(d['close'],20)
    d['ema50']=_ema(d['close'],50); d['ema100']=_ema(d['close'],100); d['ema200']=_ema(d['close'],200)
    d['rsi14']=_rsi(d['close'],14)
    d['rsi7']=_rsi(d['close'],7)
    d['vol20']=_sma(d['volume'],20)
    d['vol50']=_sma(d['volume'],50)
    d['rvol20']=d['volume']/d['vol20'].replace(0,np.nan)
    d['rvol50']=d['volume']/d['vol50'].replace(0,np.nan)
    typical=(d['high']+d['low']+d['close'])/3
    d['pv']=typical*d['volume']
    d['vwap20']=d['pv'].rolling(20).sum()/d['volume'].rolling(20).sum().replace(0,np.nan)
    d['vwap50']=d['pv'].rolling(50).sum()/d['volume'].rolling(50).sum().replace(0,np.nan)
    d['hh20']=d['high'].rolling(20).max(); d['ll20']=d['low'].rolling(20).min()
    d['hh50']=d['high'].rolling(50).max(); d['ll50']=d['low'].rolling(50).min()
    d['close_pos20']=(d['close']-d['ll20'])/(d['hh20']-d['ll20']).replace(0,np.nan)
    d['close_pos50']=(d['close']-d['ll50'])/(d['hh50']-d['ll50']).replace(0,np.nan)
    d['range20']=(d['hh20']-d['ll20'])/d['close']*100
    d['range50']=(d['hh50']-d['ll50'])/d['close']*100
    d['ret_z20']=_zscore(d['ret'],20)
    d['vol_z20']=_zscore(d['volume'],20)
    d['range_z20']=_zscore(d['range'],20)
    d['close_slope10']=_slope(d['close'],10)
    d['close_slope20']=_slope(d['close'],20)
    d['vol_slope10']=_slope(d['volume'],10)
    d['atr_slope10']=_slope(d['atr_pct'],10)
    d['ema20_slope10']=_slope(d['ema20'],10)
    d['ema50_slope10']=_slope(d['ema50'],10)
    return d


def _vote(name:str, value:float, bull:float, bear:float, weight:float=1.0, invert:bool=False):
    if value is None or not np.isfinite(value):
        return {'name':name,'vote':'NEUTRAL','weight':weight,'value':None}
    if not invert:
        vote='BULL' if value>=bull else 'BEAR' if value<=bear else 'NEUTRAL'
    else:
        vote='BULL' if value<=bull else 'BEAR' if value>=bear else 'NEUTRAL'
    return {'name':name,'vote':vote,'weight':float(weight),'value':float(value)}


def trend_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]; prev=d.iloc[-6]
    tests=[]
    tests.append(_vote('close_above_ema9',float(x['close']-x['ema9']),0,-0.001,1.0))
    tests.append(_vote('ema9_above_ema20',float(x['ema9']-x['ema20']),0,-0.001,1.1))
    tests.append(_vote('ema20_above_ema50',float(x['ema20']-x['ema50']),0,-0.001,1.2))
    tests.append(_vote('ema50_above_ema100',float(x['ema50']-x['ema100']),0,-0.001,0.8))
    tests.append(_vote('ema100_above_ema200',float(x['ema100']-x['ema200']),0,-0.001,0.6))
    tests.append(_vote('ema20_slope',float(x['ema20_slope10']),0,-0.001,1.1))
    tests.append(_vote('ema50_slope',float(x['ema50_slope10']),0,-0.001,0.9))
    tests.append(_vote('close_slope10',float(x['close_slope10']),0,-0.001,1.0))
    tests.append(_vote('close_slope20',float(x['close_slope20']),0,-0.001,1.0))
    tests.append(_vote('six_bar_progress',float(x['close']-prev['close']),0,-0.001,0.9))
    return _family_score('trend',tests)


def structure_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]; r=d.tail(20); p=d.iloc[-21:-1]
    prior_high=float(p['high'].max()) if len(p) else float(x['high'])
    prior_low=float(p['low'].min()) if len(p) else float(x['low'])
    recent_lows=r['low'].tail(5).values; recent_highs=r['high'].tail(5).values
    hl=float((np.diff(recent_lows)>=0).mean()) if len(recent_lows)>=2 else .5
    hh=float((np.diff(recent_highs)>=0).mean()) if len(recent_highs)>=2 else .5
    retrace=_safe_div(prior_high-float(x['close']),max(prior_high-prior_low,1e-9),0.5)
    tests=[
        _vote('position_in_20bar_range',float(x['close_pos20']),0.70,0.35,1.2),
        _vote('position_in_50bar_range',float(x['close_pos50']),0.68,0.32,1.0),
        _vote('higher_low_ratio',hl,0.65,0.35,1.2),
        _vote('higher_high_ratio',hh,0.60,0.30,0.9),
        _vote('shallow_retrace',retrace,0.30,0.65,1.1,invert=True),
        _vote('close_vs_prior_high',_safe_div(float(x['close']),prior_high,1),0.985,0.94,1.1),
        _vote('close_vs_prior_low',_safe_div(float(x['close']),prior_low,1),1.05,1.01,0.8),
        _vote('range20_tightness',float(x['range20']),2.8,7.5,1.0,invert=True),
        _vote('range50_context',float(x['range50']),6.0,18.0,0.6,invert=True),
    ]
    return _family_score('structure',tests)


def volume_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]; r=d.tail(12); reds=r[r['red']>0]; greens=r[r['green']>0]
    red_vol=float(reds['volume'].mean()) if len(reds) else np.nan
    green_vol=float(greens['volume'].mean()) if len(greens) else np.nan
    gv_rv=_safe_div(green_vol,red_vol,1.0)
    dry=_safe_div(float(r['volume'].tail(5).mean()),float(d['volume'].tail(20).mean()),1.0)
    tests=[
        _vote('rvol20_now',float(x['rvol20']),1.25,0.55,1.0),
        _vote('rvol50_now',float(x['rvol50']),1.20,0.60,0.8),
        _vote('green_vs_red_volume',gv_rv,1.12,0.85,1.2),
        _vote('volume_dryup_base',dry,0.72,1.35,1.0,invert=True),
        _vote('volume_z20',float(x['vol_z20']),0.8,-1.2,0.8),
        _vote('volume_slope10',float(x['vol_slope10']),0,-0.001,0.5),
        _vote('green_count_12',float(r['green'].sum()),7,4,0.7),
        _vote('red_count_12',float(r['red'].sum()),4,8,0.7,invert=True),
    ]
    return _family_score('volume',tests)


def candle_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]; r=d.tail(8)
    bull_body=float(r.loc[r['green']>0,'body_pct'].mean()) if (r['green']>0).any() else 0
    bear_body=float(r.loc[r['red']>0,'body_pct'].mean()) if (r['red']>0).any() else 0
    tests=[
        _vote('current_body_quality',float(x['body_pct']),0.55,0.18,0.8),
        _vote('current_lower_wick_support',float(x['lower_wick_pct']),0.25,0.05,0.5),
        _vote('current_upper_wick_rejection',float(x['upper_wick_pct']),0.18,0.55,1.0,invert=True),
        _vote('bull_vs_bear_body',_safe_div(bull_body,bear_body,1),1.12,0.85,1.1),
        _vote('close_near_high',_safe_div(float(x['close']-x['low']),float(x['range']),0.5),0.72,0.35,1.1),
        _vote('range_expansion',float(x['range_z20']),0.5,-1.0,0.6),
        _vote('return_impulse',float(x['ret_z20']),0.6,-0.8,0.7),
    ]
    return _family_score('candles',tests)


def momentum_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]
    macd=_ema(d['close'],12)-_ema(d['close'],26); signal=_ema(macd,9); hist=macd-signal
    roc5=d['close'].pct_change(5)*100; roc10=d['close'].pct_change(10)*100
    tests=[
        _vote('rsi14',float(x['rsi14']),56,42,1.0),
        _vote('rsi7',float(x['rsi7']),58,40,0.7),
        _vote('rsi_not_overheated',float(x['rsi14']),72,84,0.7,invert=True),
        _vote('macd_hist',float(hist.iloc[-1]),0,-0.001,1.0),
        _vote('macd_hist_slope',float(hist.iloc[-1]-hist.iloc[-4]),0,-0.001,0.8),
        _vote('roc5',float(roc5.iloc[-1]),0.6,-1.0,0.9),
        _vote('roc10',float(roc10.iloc[-1]),1.0,-1.8,0.9),
    ]
    return _family_score('momentum',tests)


def volatility_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]; a=d['atr_pct']; p20=float(a.tail(20).quantile(.35)); p80=float(a.tail(20).quantile(.80))
    recent=float(a.iloc[-1]); tests=[
        _vote('atr_compression',recent,p20,p80,1.0,invert=True),
        _vote('atr_slope10',float(x['atr_slope10']),0.0,0.08,0.7,invert=True),
        _vote('range20_compression',float(x['range20']),2.7,8.0,1.0,invert=True),
        _vote('range_z_not_extreme',abs(float(x['range_z20'])),1.4,2.8,0.7,invert=True),
    ]
    return _family_score('volatility',tests)


def vwap_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]
    dist20=_safe_div(float(x['close']-x['vwap20']),float(x['close']),0)*100
    dist50=_safe_div(float(x['close']-x['vwap50']),float(x['close']),0)*100
    tests=[
        _vote('above_vwap20',dist20,0,-1.0,1.0),
        _vote('above_vwap50',dist50,0,-1.5,0.8),
        _vote('not_too_extended_vwap20',abs(dist20),1.4,3.5,0.8,invert=True),
        _vote('vwap20_above_vwap50',float(x['vwap20']-x['vwap50']),0,-0.001,0.8),
    ]
    return _family_score('vwap',tests)


def acceptance_family(d:pd.DataFrame)->Dict[str,Any]:
    r=d.tail(12); hi=float(r['high'].max()); lo=float(r['low'].min()); mid=(hi+lo)/2
    top=float((r['close']>=mid).mean()); near_hi=float((r['close']>=lo+.72*(hi-lo)).mean())
    red_fail=float((r.loc[r['red']>0,'lower_wick_pct']>.25).mean()) if (r['red']>0).any() else .5
    tests=[
        _vote('top_half_acceptance',top,.70,.35,1.2),
        _vote('near_high_acceptance',near_hi,.45,.15,1.1),
        _vote('red_candle_recovery',red_fail,.55,.20,.8),
        _vote('base_close_location',_safe_div(float(r['close'].iloc[-1]-lo),hi-lo,.5),.72,.35,1.0),
    ]
    return _family_score('acceptance',tests)


def exhaustion_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]; r=d.tail(6)
    upper=float(r['upper_wick_pct'].mean()); body=float(r['body_pct'].mean()); rv=float(r['rvol20'].mean())
    extension=_safe_div(float(x['close']-x['ema20']),float(x['atr14']),0)
    tests=[
        _vote('upper_wick_exhaustion',upper,.18,.42,1.2,invert=True),
        _vote('body_decay',body,.48,.20,.8),
        _vote('volume_climax',rv,1.6,3.0,.8,invert=True),
        _vote('ema20_atr_extension',extension,1.5,3.5,1.1,invert=True),
        _vote('rsi_overheat',float(x['rsi14']),70,84,1.0,invert=True),
    ]
    return _family_score('exhaustion',tests)


def breakout_quality_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]; p=d.iloc[-21:-1]; ph=float(p['high'].max()); pl=float(p['low'].min())
    break_pct=_safe_div(float(x['close']-ph),ph,0)*100
    close_loc=_safe_div(float(x['close']-x['low']),float(x['range']),0.5)
    retest_low=float(d['low'].tail(3).min())
    hold=_safe_div(retest_low,ph,1)
    tests=[
        _vote('breakout_close',break_pct,.05,-.35,1.2),
        _vote('breakout_close_location',close_loc,.72,.38,1.1),
        _vote('breakout_rvol',float(x['rvol20']),1.45,.75,1.2),
        _vote('retest_hold',hold,.995,.975,1.0),
        _vote('breakout_not_extreme',break_pct,1.4,4.5,.7,invert=True),
        _vote('prior_range_quality',_safe_div(ph-pl,float(x['close']),0)*100,3.0,9.0,.6,invert=True),
    ]
    return _family_score('breakout_quality',tests)


def false_break_family(d:pd.DataFrame)->Dict[str,Any]:
    x=d.iloc[-1]; p=d.iloc[-21:-1]; ph=float(p['high'].max());
    broke=float(x['high']>ph); closed_above=float(x['close']>ph)
    rejection=_safe_div(float(x['high']-x['close']),float(x['range']),0)
    weak_vol=float(x['rvol20'])
    tests=[
        _vote('break_and_hold',closed_above,1,.1,1.2),
        _vote('rejection_after_break',rejection,.15,.55,1.2,invert=True),
        _vote('volume_on_break',weak_vol,1.3,.65,1.0),
        _vote('break_without_close',broke-closed_above,.1,.9,1.0,invert=True),
    ]
    return _family_score('false_break_safety',tests)


def _family_score(name:str, tests:List[Dict[str,Any]])->Dict[str,Any]:
    bull=sum(t['weight'] for t in tests if t['vote']=='BULL')
    bear=sum(t['weight'] for t in tests if t['vote']=='BEAR')
    neutral=sum(t['weight'] for t in tests if t['vote']=='NEUTRAL')
    total=max(bull+bear+neutral,1e-9)
    score=_clip(50+50*(bull-bear)/total)
    return {'name':name,'score':round(score,2),'bull_weight':round(bull,2),'bear_weight':round(bear,2),'neutral_weight':round(neutral,2),'tests':tests}


def detect_vetoes(d:pd.DataFrame, families:Mapping[str,Dict[str,Any]])->List[str]:
    x=d.iloc[-1]; veto=[]
    if float(x['rsi14'])>88 and float(x['upper_wick_pct'])>.35:
        veto.append('parabolic_rsi_plus_rejection')
    if float(x['rvol20'])<.55 and float(x['close'])>float(d['high'].iloc[-21:-1].max()):
        veto.append('weak_volume_breakout')
    if float(x['close'])<float(x['ema20']) and float(x['ema20'])<float(x['ema50']):
        veto.append('trend_structure_bearish')
    if families['false_break_safety']['score']<28:
        veto.append('false_breakout_cluster')
    if families['exhaustion']['score']<25:
        veto.append('late_stage_exhaustion')
    if families['acceptance']['score']<28 and families['structure']['score']<35:
        veto.append('poor_price_acceptance')
    return veto


def infer_regime(d:pd.DataFrame)->str:
    x=d.iloc[-1]
    trend=abs(_safe_div(float(x['ema20']-x['ema50']),float(x['close']),0))*100
    atr=float(x['atr_pct'])
    range20=float(x['range20'])
    if trend>.8 and range20>3.5:
        return 'TRENDING'
    if atr<.45 and range20<2.8:
        return 'COMPRESSION'
    if atr>2.4:
        return 'HIGH_VOLATILITY'
    if range20<2.5:
        return 'TIGHT_BASE'
    return 'NORMAL'


def advanced_confirmation_report(df:pd.DataFrame)->Dict[str,Any]:
    d=enrich(df).dropna().reset_index(drop=True)
    if len(d)<220:
        # EMA200 is useful but not mandatory; still accept shorter histories.
        d=enrich(df).dropna(subset=['ema50','atr14','rsi14']).reset_index(drop=True)
    if len(d)<60:
        raise ValueError('advanced confirmation needs at least 60 valid candles')

    families={
        'trend':trend_family(d),
        'structure':structure_family(d),
        'volume':volume_family(d),
        'candles':candle_family(d),
        'momentum':momentum_family(d),
        'volatility':volatility_family(d),
        'vwap':vwap_family(d),
        'acceptance':acceptance_family(d),
        'exhaustion':exhaustion_family(d),
        'breakout_quality':breakout_quality_family(d),
        'false_break_safety':false_break_family(d),
    }
    weights={'trend':1.05,'structure':1.20,'volume':1.20,'candles':.75,'momentum':.75,'volatility':.70,'vwap':.65,'acceptance':1.10,'exhaustion':1.00,'breakout_quality':1.25,'false_break_safety':1.30}
    weighted=sum(families[k]['score']*weights[k] for k in families)/sum(weights.values())
    vetoes=detect_vetoes(d,families)
    weighted-=min(24,len(vetoes)*6)
    score=_clip(weighted)

    tests=[t for f in families.values() for t in f['tests']]
    bull=sum(t['vote']=='BULL' for t in tests); bear=sum(t['vote']=='BEAR' for t in tests); neutral=sum(t['vote']=='NEUTRAL' for t in tests)
    agreement=abs(bull-bear)/max(bull+bear,1)
    confidence=_clip(55+35*agreement+10*(1-neutral/max(len(tests),1)))
    strongest=max(families,key=lambda k:families[k]['score'])
    regime=infer_regime(d)
    result=ConfirmationResult(score=round(score,2),confidence=round(confidence,2),bullish_votes=bull,bearish_votes=bear,neutral_votes=neutral,veto_count=len(vetoes),regime=regime,strongest_family=strongest)
    return {'summary':asdict(result),'families':families,'vetoes':vetoes,'tests_total':len(tests)}
