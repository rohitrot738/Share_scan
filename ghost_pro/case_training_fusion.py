"""Fuse labelled-case pattern engines into an existing Ghost Trade Pro result.

This layer lets new screenshot-derived pattern families influence live scoring
without hard-coding any stock name. It currently combines:
- Mazagon-style impulse -> compression -> breakout family
- TVS Supply Chain-style bottoming -> compression -> staircase expansion family

It is intentionally conservative: a case engine can add confirmation only when
its own features are strong, while DO_NOT_CHASE / exhaustion can veto late entries.
"""
from __future__ import annotations

from typing import Dict, Mapping
import numpy as np
import pandas as pd

from ghost_pro.case_pattern_engine import analyse_case_pattern
from ghost_pro.reversal_expansion_engine import analyse_reversal_expansion


TF_CASE_WEIGHTS={
    '1m':0.06,'3m':0.08,'5m':0.14,'10m':0.10,'15m':0.18,
    '30m':0.12,'1h':0.14,'4h':0.10,'1d':0.08,
}


def _clip(x):
    return float(np.clip(float(x),0,100))


def _run_safe(fn,df):
    try:return fn(df)
    except Exception as exc:return {'error':str(exc),'snapshot':{'score':50.0,'state':'UNAVAILABLE'}}


def analyse_case_families(data:Mapping[str,pd.DataFrame])->Dict[str,object]:
    per_tf={}; weighted_score=0.0; total_w=0.0; chase_votes=0; early_votes=0; ready_votes=0; confirmed_votes=0
    family_scores=[]
    for tf,df in data.items():
        maz=_run_safe(analyse_case_pattern,df)
        tvs=_run_safe(analyse_reversal_expansion,df)
        ms=maz.get('snapshot',{}); ts=tvs.get('snapshot',{})
        maz_score=float(ms.get('score',50)); tvs_score=float(ts.get('score',50))
        # Let the stronger valid family lead, but retain 30% of the alternate family.
        hi=max(maz_score,tvs_score); lo=min(maz_score,tvs_score)
        ensemble=0.70*hi+0.30*lo
        tvs_state=str(ts.get('state',''))
        maz_state=str(ms.get('state',''))
        chase=float(ts.get('chase_penalty',0))
        exhaustion=float(ms.get('exhaustion_penalty',0))
        late_penalty=max(chase,exhaustion)
        ensemble*=1-0.18*(late_penalty/100)
        ensemble=_clip(ensemble)
        w=TF_CASE_WEIGHTS.get(tf,0.06)
        weighted_score+=ensemble*w; total_w+=w
        family_scores.append(ensemble)
        if tvs_state=='DO_NOT_CHASE' or chase>=65 or exhaustion>=65:chase_votes+=1
        if tvs_state=='EARLY' or maz_state=='EARLY':early_votes+=1
        if tvs_state=='READY' or maz_state=='READY':ready_votes+=1
        if tvs_state=='CONFIRMED' or maz_state=='CONFIRMED':confirmed_votes+=1
        per_tf[tf]={
            'mazagon_family':maz,
            'tvs_reversal_family':tvs,
            'case_ensemble_score':round(ensemble,2),
            'late_penalty':round(late_penalty,2),
        }
    score=weighted_score/max(total_w,1e-9)
    agreement=0.0 if not family_scores else max(0.0,1-float(np.std(family_scores))/30.0)
    if chase_votes>=2:
        state='DO_NOT_CHASE'
    elif confirmed_votes>=2 and score>=80:
        state='CONFIRMED'
    elif confirmed_votes+ready_votes>=2 and score>=72:
        state='READY'
    elif early_votes>=2 or score>=64:
        state='EARLY'
    elif score>=55:
        state='WATCH'
    else:
        state='IGNORE'
    return {
        'score':round(_clip(score),2),
        'state':state,
        'agreement':round(100*agreement,2),
        'chase_votes':chase_votes,
        'early_votes':early_votes,
        'ready_votes':ready_votes,
        'confirmed_votes':confirmed_votes,
        'timeframes':per_tf,
    }


def fuse_with_technical(technical:Dict[str,object],data:Mapping[str,pd.DataFrame])->Dict[str,object]:
    """Blend screenshot-trained case intelligence into a technical result dict."""
    case=analyse_case_families(data)
    out=dict(technical)
    base=float(technical.get('final_score',0))
    case_score=float(case.get('score',50))
    fused=0.82*base+0.18*case_score

    # Positive confirmation is modest; late-entry veto is strong.
    if case['state']=='CONFIRMED':fused+=3.0
    elif case['state']=='READY':fused+=1.5
    elif case['state']=='DO_NOT_CHASE':fused-=12.0
    fused=_clip(fused)

    old_state=str(technical.get('final_state','IGNORE'))
    if case['state']=='DO_NOT_CHASE':
        state='AVOID' if base<78 else 'WATCH'
    elif fused>=86 and case['state'] in {'READY','CONFIRMED'}:state='CONFIRMED'
    elif fused>=76 and case['state'] in {'EARLY','READY','CONFIRMED'}:state='READY'
    elif fused>=65:state='EARLY'
    elif fused>=55:state='WATCH'
    else:state='IGNORE'

    # Do not upgrade a technical AVOID into a bullish state just from one case family.
    if old_state=='AVOID' and state not in {'AVOID','IGNORE'}:state='WATCH'

    false_risk=float(technical.get('false_breakout_risk',100))
    if case['state']=='DO_NOT_CHASE':false_risk=min(100,false_risk+14)
    elif case['state']=='CONFIRMED' and case['agreement']>=60:false_risk=max(0,false_risk-6)

    out['pre_case_score']=round(base,2)
    out['final_score']=round(fused,2)
    out['final_state']=state
    out['false_breakout_risk']=round(false_risk,2)
    out['case_training']=case
    return out
