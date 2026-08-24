"""Ghost Trade Pro Ultimate orchestration engine.

Combines market structure, volume/order-flow proxies, smart-money proxies,
momentum/volatility, setup detectors, dedicated false-breakout logic and risk
planning into one explainable score. Designed to be calibrated later from
labelled screenshots.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict, Mapping
import json
import numpy as np
import pandas as pd

from ghost_pro.market_structure import market_structure_report
from ghost_pro.volume_orderflow import volume_orderflow_report
from ghost_pro.smart_money import smart_money_report
from ghost_pro.momentum_volatility import momentum_volatility_report
from ghost_pro.setup_detectors import detect_all_setups
from ghost_pro.risk_engine import risk_report
from ghost_pro.false_breakout import false_breakout_report


@dataclass
class GhostDecision:
    symbol: str
    timeframe: str
    score: float
    state: str
    confidence: float
    false_breakout_risk: float
    entry: float
    stop: float
    target1: float
    target2: float
    target3: float
    primary_reason: str
    warning: str


DEFAULT_WEIGHTS = {
    'structure': 0.19,
    'flow': 0.19,
    'smart_money': 0.13,
    'momentum': 0.13,
    'setup': 0.20,
    'risk_quality': 0.11,
    'trap_safety': 0.05,
}

TF_WEIGHTS = {'5m':0.22,'15m':0.30,'30m':0.16,'1h':0.24,'4h':0.08}


def _clip(x):
    return float(np.clip(float(x),0,100))


def validate_ohlcv(df:pd.DataFrame)->pd.DataFrame:
    required=['open','high','low','close','volume']
    missing=[c for c in required if c not in df.columns]
    if missing:raise ValueError(f'missing OHLCV columns: {missing}')
    d=df.copy()
    for c in required:d[c]=pd.to_numeric(d[c],errors='coerce')
    d=d.dropna(subset=required).reset_index(drop=True)
    if len(d)<60:raise ValueError('at least 60 clean candles recommended')
    bad=(d['high']<d[['open','close','low']].max(axis=1))|(d['low']>d[['open','close','high']].min(axis=1))
    d=d.loc[~bad].reset_index(drop=True)
    if len(d)<50:raise ValueError('too few valid candles after cleaning')
    d['volume']=d['volume'].clip(lower=0)
    return d


def regime_adjustment(momentum_report:Dict[str,object])->float:
    snap=momentum_report['snapshot']; regime=snap.get('regime','NORMAL')
    if regime=='TRENDING':return 4.0
    if regime=='LOW_VOL':return 2.0
    if regime=='CHOPPY':return -7.0
    if regime=='HIGH_VOL':return -4.0
    return 0.0


def legacy_false_breakout_risk(structure,flow,smart,momentum,setups)->float:
    s=structure['snapshot']; f=flow['snapshot']; m=momentum['snapshot']
    risk=45.0
    risk += 14 if s.get('bos_down') else 0
    risk += 10 if s.get('choch_down') else 0
    risk += 12*float(f.get('distribution',0))
    risk += 10*float(f.get('absorption_sell',0))
    risk += 8 if m.get('regime')=='CHOPPY' else 0
    risk += 7 if m.get('rsi',50)>80 else 0
    risk += 0.10*float(smart.get('bear_liquidity_sweep_score',0))
    risk -= 12*float(f.get('accumulation',0))
    risk -= 9*float(f.get('dryup',0))
    risk -= 0.10*float(smart.get('bull_liquidity_sweep_score',0))
    risk -= 0.10*float(setups.get('ensemble_score',0))
    risk -= 8 if s.get('bos_up') else 0
    return _clip(risk)


def blended_false_breakout_risk(structure,flow,smart,momentum,setups,traps)->float:
    legacy=legacy_false_breakout_risk(structure,flow,smart,momentum,setups)
    dedicated=float(traps['snapshot']['composite_risk'])
    return _clip(0.42*legacy+0.58*dedicated)


def confidence_from_agreement(scores:Dict[str,float])->float:
    arr=np.array(list(scores.values()),dtype=float)
    if len(arr)==0:return 0.0
    mean=float(np.mean(arr)); std=float(np.std(arr)); agreement=max(0,1-std/35)
    return _clip(0.65*mean+35*agreement)


def choose_state(score:float,false_risk:float,active_setups:int)->str:
    if score>=88 and false_risk<=28 and active_setups>=2:return 'CONFIRMED'
    if score>=78 and false_risk<=40:return 'READY'
    if score>=66:return 'EARLY'
    if score>=55:return 'WATCH'
    return 'IGNORE'


def primary_reason(scores:Dict[str,float])->str:
    if not scores:return 'No dominant signal'
    key=max(scores,key=scores.get)
    labels={'structure':'market structure','flow':'buyer/volume flow','smart_money':'liquidity / smart-money proxy','momentum':'momentum and volatility','setup':'pre-breakout setup cluster','risk_quality':'risk/reward quality','trap_safety':'false-breakout safety'}
    return f"Strongest layer: {labels.get(key,key)} ({scores[key]:.1f}/100)"


def warning_text(false_risk:float,flow:Dict[str,object],momentum:Dict[str,object],traps:Dict[str,object])->str:
    parts=[]
    if false_risk>=55:parts.append('high false-breakout risk')
    f=flow['snapshot']; m=momentum['snapshot']; t=traps['snapshot']
    if float(f.get('distribution',0))>0.65:parts.append('distribution pressure')
    if float(f.get('absorption_sell',0))>0.65:parts.append('upper-wick/sell absorption risk')
    if m.get('regime')=='CHOPPY':parts.append('choppy regime')
    if float(m.get('rsi',50))>80:parts.append('overheated RSI')
    if float(t.get('breakout_failure',0))>65:parts.append('breakout rejection')
    if float(t.get('weak_volume_break',0))>65:parts.append('weak-volume breakout')
    if float(t.get('late_entry_risk',0) if isinstance(t,dict) else 0)>65:parts.append('late-entry risk')
    return ', '.join(parts) if parts else 'No major model warning'


def analyse_single(df:pd.DataFrame,symbol='UNKNOWN',timeframe='15m',capital=100000,risk_pct=.5,weights=None)->Dict[str,object]:
    d=validate_ohlcv(df); w=dict(DEFAULT_WEIGHTS if weights is None else weights)
    structure=market_structure_report(d)
    flow=volume_orderflow_report(d)
    smart=smart_money_report(d)
    momentum=momentum_volatility_report(d)
    setups=detect_all_setups(d)
    risk=risk_report(d,capital,risk_pct)
    traps=false_breakout_report(d)

    trap_safety=100-float(traps['snapshot']['composite_risk'])
    scores={
        'structure':_clip(structure['snapshot']['score']),
        'flow':_clip(flow['snapshot']['score']),
        'smart_money':_clip(smart['score']),
        'momentum':_clip(0.55*momentum['snapshot']['trend_score']+0.45*momentum['snapshot']['momentum_score']),
        'setup':_clip(setups['ensemble_score']),
        'risk_quality':_clip(risk['plan']['quality']),
        'trap_safety':_clip(trap_safety),
    }
    raw=sum(scores[k]*w.get(k,0) for k in scores)/max(sum(w.get(k,0) for k in scores),1e-9)
    raw+=regime_adjustment(momentum)
    false_risk=blended_false_breakout_risk(structure,flow,smart,momentum,setups,traps)
    penalty=max(0,false_risk-35)*0.18
    final=_clip(raw-penalty)
    conf=confidence_from_agreement(scores)
    state=choose_state(final,false_risk,int(setups['active_count']))
    rp=risk['plan']
    decision=GhostDecision(symbol,timeframe,final,state,conf,false_risk,float(rp['entry']),float(rp['stop']),float(rp['target1']),float(rp['target2']),float(rp['target3']),primary_reason(scores),warning_text(false_risk,flow,momentum,traps))
    return {'decision':asdict(decision),'layer_scores':scores,'structure':structure,'flow':flow,'smart_money':smart,'momentum':momentum,'setups':setups,'false_breakout':traps,'risk':risk}


def multi_timeframe(data:Mapping[str,pd.DataFrame],symbol='UNKNOWN',capital=100000,risk_pct=.5)->Dict[str,object]:
    reports={}; weighted=0.0; totalw=0.0
    for tf,df in data.items():
        rep=analyse_single(df,symbol,tf,capital,risk_pct); reports[tf]=rep
        w=TF_WEIGHTS.get(tf,0.10); weighted+=rep['decision']['score']*w; totalw+=w
    final=weighted/max(totalw,1e-9)
    states=[r['decision']['state'] for r in reports.values()]
    risks=[r['decision']['false_breakout_risk'] for r in reports.values()]
    confidences=[r['decision']['confidence'] for r in reports.values()]
    confirmed=sum(s=='CONFIRMED' for s in states); ready=sum(s=='READY' for s in states)
    if final>=86 and (confirmed>=1 or ready>=2):state='CONFIRMED'
    elif final>=76 and (confirmed+ready)>=1:state='READY'
    elif final>=65:state='EARLY'
    elif final>=55:state='WATCH'
    else:state='IGNORE'
    if '15m' in reports:exec_tf='15m'
    elif '5m' in reports:exec_tf='5m'
    else:exec_tf=max(reports,key=lambda t:reports[t]['decision']['score'])
    plan=reports[exec_tf]['decision']
    return {'symbol':symbol,'final_score':round(float(final),2),'final_state':state,'confidence':round(float(np.mean(confidences)),2) if confidences else 0,'false_breakout_risk':round(float(np.mean(risks)),2) if risks else 100,'execution_timeframe':exec_tf,'entry':plan['entry'],'stop':plan['stop'],'target1':plan['target1'],'target2':plan['target2'],'target3':plan['target3'],'timeframes':reports}


def rank_universe(universe:Mapping[str,Mapping[str,pd.DataFrame]],capital=100000,risk_pct=.5,min_score=55)->list[Dict[str,object]]:
    ranked=[]
    for symbol,frames in universe.items():
        try:
            r=multi_timeframe(frames,symbol,capital,risk_pct)
            if r['final_score']>=min_score:ranked.append(r)
        except Exception as e:
            ranked.append({'symbol':symbol,'final_score':0,'final_state':'ERROR','error':str(e)})
    ranked.sort(key=lambda x:x.get('final_score',0),reverse=True)
    return ranked


def compact_summary(result:Dict[str,object])->str:
    return (f"{result['symbol']} | {result['final_state']} | Score {result['final_score']:.1f} | Entry {result['entry']:.2f} | SL {result['stop']:.2f} | T1 {result['target1']:.2f} | T2 {result['target2']:.2f} | FalseBreak {result['false_breakout_risk']:.1f}")


def to_json(result:Dict[str,object],indent=2)->str:
    return json.dumps(result,indent=indent,default=str)
