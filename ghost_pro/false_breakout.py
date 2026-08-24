"""Dedicated false-breakout and trap detector for Ghost Trade Pro Ultimate."""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict
import numpy as np
import pandas as pd


@dataclass
class TrapSnapshot:
    breakout_failure: float
    upper_wick_trap: float
    weak_volume_break: float
    exhaustion_gap: float
    failed_followthrough: float
    distribution_risk: float
    liquidity_sweep_risk: float
    composite_risk: float


def atr(df,n=14):
    pc=df['close'].shift(1)
    tr=pd.concat([df['high']-df['low'],(df['high']-pc).abs(),(df['low']-pc).abs()],axis=1).max(axis=1)
    return tr.rolling(n,min_periods=5).mean()


def rvol(df,n=20):
    return df['volume']/df['volume'].rolling(n,min_periods=5).mean().replace(0,np.nan)


def prior_resistance(df,n=20):
    if len(df)<n+1:return float(df['high'].iloc[:-1].max())
    return float(df['high'].iloc[-n-1:-1].max())


def prior_support(df,n=20):
    if len(df)<n+1:return float(df['low'].iloc[:-1].min())
    return float(df['low'].iloc[-n-1:-1].min())


def breakout_failure_score(df,n=20):
    if len(df)<n+2:return 0.0
    level=prior_resistance(df,n); last=df.iloc[-1]; prev=df.iloc[-2]
    attempted=float(prev['high']>level or last['high']>level)
    closed_back=float(last['close']<level)
    depth=max(0,(level-float(last['close']))/max(level,1e-9)*100)
    return float(np.clip(.45*attempted+.40*closed_back+.15*min(depth/.8,1),0,1)*100)


def upper_wick_trap_score(df,length=5):
    r=df.tail(length); rng=(r['high']-r['low']).replace(0,np.nan); upper=r['high']-r[['open','close']].max(axis=1); ratio=(upper/rng).fillna(0); rv=rvol(df).tail(length).fillna(1)
    signal=(ratio*rv).clip(0,3); return float(np.clip(signal.mean()/1.2,0,1)*100)


def weak_volume_break_score(df,n=20):
    if len(df)<n+2:return 0.0
    level=prior_resistance(df,n); broke=float(df['close'].iloc[-1]>level); rv=float(rvol(df).iloc[-1]) if np.isfinite(rvol(df).iloc[-1]) else 0; weakness=max(0,1-rv/1.5)
    return float(np.clip(broke*weakness,0,1)*100)


def exhaustion_gap_score(df):
    if len(df)<5:return 0.0
    pc=float(df['close'].iloc[-2]); o=float(df['open'].iloc[-1]); c=float(df['close'].iloc[-1]); h=float(df['high'].iloc[-1]); l=float(df['low'].iloc[-1]); gap=max(0,(o-pc)/max(pc,1e-9)*100); rng=max(h-l,1e-9); upper=h-max(o,c); rejection=upper/rng; rv=float(rvol(df).iloc[-1]) if np.isfinite(rvol(df).iloc[-1]) else 0
    return float(np.clip(.35*min(gap/3,1)+.35*min(rejection*2,1)+.30*min(rv/3,1),0,1)*100)


def failed_followthrough_score(df):
    if len(df)<4:return 0.0
    a=float(atr(df).iloc[-1]); a=a if np.isfinite(a) and a>0 else max(float(df['close'].iloc[-1])*.01,1e-9)
    p=df.iloc[-2]; c=df.iloc[-1]; pbody=abs(float(p['close']-p['open']))/a; pgreen=float(p['close']>p['open']); continuation=(float(c['close'])-float(p['close']))/a; reversal=float(c['close']<c['open'])
    score=.40*min(pbody/1.5,1)*pgreen+.35*max(0,min(-continuation,1))+.25*reversal
    return float(np.clip(score,0,1)*100)


def distribution_risk_score(df,length=20):
    r=df.tail(length); rng=(r['high']-r['low']).replace(0,np.nan); upper=r['high']-r[['open','close']].max(axis=1); upper_ratio=(upper/rng).fillna(0); red=r['close']<r['open']; red_vol=float(r.loc[red,'volume'].mean()) if red.any() else 0; green_vol=float(r.loc[~red,'volume'].mean()) if (~red).any() else 0; vol_bias=red_vol/max(red_vol+green_vol,1e-9); close_location=((r['close']-r['low'])/rng).fillna(.5); weak_close=float((close_location<.4).mean()); return float(np.clip(.40*min(float(upper_ratio.mean())*2,1)+.35*vol_bias+.25*weak_close,0,1)*100)


def liquidity_sweep_risk_score(df,n=20):
    if len(df)<n+2:return 0.0
    level=prior_resistance(df,n); last=df.iloc[-1]; swept=float(last['high']>level and last['close']<level); extreme=max(0,(float(last['high'])-level)/max(level,1e-9)*100); rv=float(rvol(df).iloc[-1]) if np.isfinite(rvol(df).iloc[-1]) else 0; return float(np.clip(.55*swept+.25*min(extreme/.7,1)+.20*min(rv/2.5,1),0,1)*100)


def bear_engulfing_risk(df):
    if len(df)<2:return 0.0
    p=df.iloc[-2]; c=df.iloc[-1]; prev_green=float(p['close']>p['open']); cur_red=float(c['close']<c['open']); engulf=float(c['open']>=p['close'] and c['close']<=p['open']); rv=float(rvol(df).iloc[-1]) if np.isfinite(rvol(df).iloc[-1]) else 0; return float(np.clip(.35*prev_green+.35*cur_red+.20*engulf+.10*min(rv/2,1),0,1)*100)


def close_below_vwap_risk(df):
    tp=(df['high']+df['low']+df['close'])/3; v=df['volume'].clip(lower=0); vw=(tp*v).cumsum()/v.cumsum().replace(0,np.nan); cur=float(df['close'].iloc[-1]); now=float(vw.iloc[-1]) if np.isfinite(vw.iloc[-1]) else cur; dist=max(0,(now-cur)/max(now,1e-9)*100); return float(np.clip(dist/1.2,0,1)*100)


def repeated_rejection_risk(df,n=15):
    r=df.tail(n); level=float(r['high'].max()); a=float(atr(df).iloc[-1]); a=a if np.isfinite(a) and a>0 else level*.01; near=(level-r['high'])<=a*.25; touched=r[near];
    if touched.empty:return 0.0
    rejection=float((touched['close']<level-a*.20).mean()); touches=min(len(touched)/5,1); return float(np.clip(.55*rejection+.45*touches,0,1)*100)


def late_entry_risk(df,n=20):
    a=float(atr(df).iloc[-1]); a=a if np.isfinite(a) and a>0 else float(df['close'].iloc[-1])*.01; ema20=df['close'].ewm(span=20,adjust=False).mean(); dist=(float(df['close'].iloc[-1])-float(ema20.iloc[-1]))/max(a,1e-9); roc=(float(df['close'].iloc[-1])/max(float(df['close'].iloc[-5]),1e-9)-1)*100 if len(df)>=5 else 0; return float(np.clip(.55*max(0,(dist-1.2)/2)+.45*max(0,(roc-3)/5),0,1)*100)


def trap_snapshot(df)->TrapSnapshot:
    b=breakout_failure_score(df); u=upper_wick_trap_score(df); w=weak_volume_break_score(df); e=exhaustion_gap_score(df); f=failed_followthrough_score(df); d=distribution_risk_score(df); l=liquidity_sweep_risk_score(df)
    extras=[bear_engulfing_risk(df),close_below_vwap_risk(df),repeated_rejection_risk(df),late_entry_risk(df)]
    composite=.20*b+.15*u+.13*w+.10*e+.12*f+.15*d+.15*l
    composite=.80*composite+.20*np.mean(extras)
    return TrapSnapshot(b,u,w,e,f,d,l,float(np.clip(composite,0,100)))


def false_breakout_report(df)->Dict[str,object]:
    snap=trap_snapshot(df)
    return {'snapshot':asdict(snap),'bear_engulfing_risk':bear_engulfing_risk(df),'below_vwap_risk':close_below_vwap_risk(df),'repeated_rejection_risk':repeated_rejection_risk(df),'late_entry_risk':late_entry_risk(df),'risk_band':'HIGH' if snap.composite_risk>=65 else 'MEDIUM' if snap.composite_risk>=40 else 'LOW'}
