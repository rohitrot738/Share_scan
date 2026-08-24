"""Risk, invalidation and target engine for Ghost Trade Pro Ultimate."""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict, List
import numpy as np
import pandas as pd


@dataclass
class RiskPlan:
    entry: float
    stop: float
    risk_per_share: float
    target1: float
    target2: float
    target3: float
    rr1: float
    rr2: float
    rr3: float
    position_size: int
    capital_at_risk: float
    quality: float


def atr(df,n=14):
    pc=df['close'].shift(1)
    tr=pd.concat([df['high']-df['low'],(df['high']-pc).abs(),(df['low']-pc).abs()],axis=1).max(axis=1)
    return tr.rolling(n,min_periods=5).mean()


def swing_low(df,n=12):
    return float(df['low'].tail(n).min())


def swing_high(df,n=12):
    return float(df['high'].tail(n).max())


def nearest_support(df,lookbacks=(5,10,20,40)):
    close=float(df['close'].iloc[-1]); candidates=[]
    for n in lookbacks:
        if len(df)>=n:
            x=float(df['low'].tail(n).min())
            if x<close:candidates.append(x)
    return max(candidates) if candidates else swing_low(df,12)


def nearest_resistance(df,lookbacks=(5,10,20,40,80)):
    close=float(df['close'].iloc[-1]); candidates=[]
    for n in lookbacks:
        if len(df)>=n:
            x=float(df['high'].tail(n).max())
            if x>close:candidates.append(x)
    return min(candidates) if candidates else swing_high(df,12)


def structure_stop(df,entry=None,atr_mult=0.35):
    entry=float(df['close'].iloc[-1]) if entry is None else float(entry); a=float(atr(df).iloc[-1]); a=a if np.isfinite(a) and a>0 else entry*0.01; support=nearest_support(df); return min(entry-a*0.5,support-a*atr_mult)


def atr_stop(df,entry=None,mult=1.25):
    entry=float(df['close'].iloc[-1]) if entry is None else float(entry); a=float(atr(df).iloc[-1]); a=a if np.isfinite(a) and a>0 else entry*0.01; return entry-a*mult


def blended_stop(df,entry=None):
    entry=float(df['close'].iloc[-1]) if entry is None else float(entry); s1=structure_stop(df,entry); s2=atr_stop(df,entry,1.15); stop=max(s1,s2); return min(stop,entry*0.999)


def risk_reward(entry,stop,target):
    risk=max(entry-stop,1e-9); return max(0.0,(target-entry)/risk)


def target_from_rr(entry,stop,rr):
    return entry+(entry-stop)*rr


def measured_move_target(df,entry=None,base_len=12,impulse_len=25):
    entry=float(df['close'].iloc[-1]) if entry is None else float(entry); pre=df.iloc[-(base_len+impulse_len):-base_len] if len(df)>=base_len+impulse_len else df.head(max(1,len(df)-base_len)); impulse=float(pre['high'].max()-pre['low'].min()) if not pre.empty else 0; return entry+max(impulse,0)


def resistance_targets(df,entry=None):
    entry=float(df['close'].iloc[-1]) if entry is None else float(entry); highs=[]
    for n in (10,20,40,80,120):
        if len(df)>=n:
            h=float(df['high'].tail(n).max())
            if h>entry:highs.append(h)
    highs=sorted(set(round(x,8) for x in highs))
    return highs


def choose_targets(df,entry,stop):
    rr15=target_from_rr(entry,stop,1.5); rr2=target_from_rr(entry,stop,2.0); rr3=target_from_rr(entry,stop,3.0); measured=measured_move_target(df,entry); resist=resistance_targets(df,entry)
    t1=rr15; t2=rr2; t3=min(max(rr3,rr2),measured) if measured>entry else rr3
    if resist:
        above=[x for x in resist if x>entry]
        if above:
            t1=min(t1,above[0]) if above[0]>entry+(entry-stop)*0.8 else t1
            if len(above)>1:t2=max(t1, min(t2,above[1]))
    return float(t1),float(t2),float(max(t3,t2))


def position_size(entry,stop,capital=100000,risk_pct=0.5,max_exposure_pct=20):
    risk_budget=capital*risk_pct/100; rps=max(entry-stop,1e-9); qty_risk=int(risk_budget//rps); qty_exposure=int((capital*max_exposure_pct/100)//max(entry,1e-9)); return max(0,min(qty_risk,qty_exposure))


def gap_risk(df)->float:
    if len(df)<3:return 0.0
    prev=df['close'].shift(1); gaps=((df['open']-prev).abs()/prev.replace(0,np.nan)*100).dropna(); cur=float(gaps.iloc[-1]) if len(gaps) else 0; p95=float(gaps.tail(100).quantile(.95)) if len(gaps) else 0; return float(np.clip(cur/max(p95,0.5),0,1)*100)


def volatility_risk(df)->float:
    a=atr(df); ap=(a/df['close'].replace(0,np.nan)*100).dropna();
    if len(ap)<5:return 50.0
    cur=float(ap.iloc[-1]); hist=ap.tail(100); return float(100*(hist<=cur).mean())


def liquidity_risk(df)->float:
    value=(df['close']*df['volume']).tail(20); med=float(value.median());
    if med<=0:return 100.0
    # Scale deliberately broad; relative ranking is more important than absolute rupees.
    return float(np.clip(100-(np.log10(max(med,1))-5)*20,0,100))


def stop_distance_risk(entry,stop):
    pct=(entry-stop)/max(entry,1e-9)*100; return float(np.clip((pct-0.4)/3.5,0,1)*100)


def risk_quality(df,entry,stop,rr2):
    vr=volatility_risk(df); gr=gap_risk(df); lr=liquidity_risk(df); sr=stop_distance_risk(entry,stop); reward=np.clip(rr2/3,0,1)*100; risk=(.30*vr+.20*gr+.20*lr+.30*sr); return float(np.clip(.60*reward+.40*(100-risk),0,100))


def build_risk_plan(df,capital=100000,risk_pct=.5,entry=None)->RiskPlan:
    entry=float(df['close'].iloc[-1]) if entry is None else float(entry); stop=blended_stop(df,entry); t1,t2,t3=choose_targets(df,entry,stop); rps=max(entry-stop,1e-9); rr1=risk_reward(entry,stop,t1); rr2=risk_reward(entry,stop,t2); rr3=risk_reward(entry,stop,t3); qty=position_size(entry,stop,capital,risk_pct); car=qty*rps; quality=risk_quality(df,entry,stop,rr2)
    return RiskPlan(entry,stop,rps,t1,t2,t3,rr1,rr2,rr3,qty,car,quality)


def risk_report(df,capital=100000,risk_pct=.5)->Dict[str,object]:
    plan=build_risk_plan(df,capital,risk_pct); return {'plan':asdict(plan),'gap_risk':gap_risk(df),'volatility_risk':volatility_risk(df),'liquidity_risk':liquidity_risk(df),'nearest_support':nearest_support(df),'nearest_resistance':nearest_resistance(df)}
