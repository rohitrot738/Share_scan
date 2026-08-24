"""Case-derived reversal/expansion pattern engine.

Training inspiration: TVS Supply Chain case 002.
The implementation is intentionally generic and contains no stock-specific
absolute price levels. It detects the sequence:
markdown -> selling exhaustion -> compression -> first displacement ->
shallow hold -> second expansion, while penalising late/chasing entries.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict
import numpy as np
import pandas as pd


@dataclass
class ReversalExpansionSnapshot:
    prior_markdown: float
    downside_efficiency_decay: float
    selling_exhaustion: float
    base_compression: float
    volume_dryup: float
    first_displacement: float
    displacement_rvol: float
    shallow_hold: float
    higher_low_quality: float
    second_expansion: float
    price_acceptance: float
    overhead_supply_safety: float
    extension_safety: float
    climax_safety: float
    early_score: float
    continuation_score: float
    chase_penalty: float
    score: float
    state: str


def _clip(x, lo=0.0, hi=1.0):
    return float(np.clip(float(x), lo, hi))


def _atr(d: pd.DataFrame, n=14):
    pc=d['close'].shift(1)
    tr=pd.concat([(d['high']-d['low']),(d['high']-pc).abs(),(d['low']-pc).abs()],axis=1).max(axis=1)
    return tr.rolling(n).mean()


def _ema(s: pd.Series, n: int):
    return s.ewm(span=n, adjust=False).mean()


def _slope(y: np.ndarray):
    if len(y)<3:return 0.0
    x=np.arange(len(y),dtype=float)
    yy=np.asarray(y,dtype=float)
    den=np.sum((x-x.mean())**2)
    return 0.0 if den<=1e-12 else float(np.sum((x-x.mean())*(yy-yy.mean()))/den)


def _rolling_drawdown_efficiency(close: pd.Series, window=8):
    """How efficiently price travels down per unit absolute movement."""
    vals=[]
    c=close.values
    for i in range(window,len(c)+1):
        x=c[i-window:i]
        gross=float(np.abs(np.diff(x)).sum())
        net=max(0.0,float(x[0]-x[-1]))
        vals.append(net/max(gross,1e-9))
    return np.array(vals,dtype=float)


def _find_local_pivots(d:pd.DataFrame, left=2, right=2):
    highs=[];lows=[]
    for i in range(left,len(d)-right):
        h=float(d['high'].iloc[i]); l=float(d['low'].iloc[i])
        if h>=float(d['high'].iloc[i-left:i+right+1].max()):highs.append((i,h))
        if l<=float(d['low'].iloc[i-left:i+right+1].min()):lows.append((i,l))
    return highs,lows


def _overhead_supply_safety(d:pd.DataFrame, atr_now:float):
    """1 = no nearby old supply; 0 = directly under repeated historical highs."""
    if len(d)<80 or not np.isfinite(atr_now) or atr_now<=0:return 0.5
    now=float(d['close'].iloc[-1])
    history=d.iloc[:-20]
    highs,_=_find_local_pivots(history,3,3)
    above=[p for _,p in highs if p>now]
    if not above:return 1.0
    nearest=min(above)
    distance=(nearest-now)/atr_now
    # repeated pivot density near nearest supply increases danger
    band=max(atr_now*1.25, now*0.012)
    repeated=sum(abs(p-nearest)<=band for _,p in highs)
    distance_score=_clip(distance/5.0)
    density_penalty=_clip((repeated-1)/4.0)
    return _clip(distance_score*(1-0.35*density_penalty))


def _extension_safety(d:pd.DataFrame,atr_now:float):
    if len(d)<20 or atr_now<=0 or not np.isfinite(atr_now):return 0.5
    ema20=float(_ema(d['close'],20).iloc[-1])
    close=float(d['close'].iloc[-1])
    ext=max(0.0,(close-ema20)/atr_now)
    return _clip(1.0-ext/6.0)


def _climax_safety(d:pd.DataFrame,atr_now:float):
    if len(d)<25 or atr_now<=0:return 0.5
    x=d.tail(8)
    vbase=float(d['volume'].tail(25).mean())
    latest_v=float(x['volume'].iloc[-1])/max(vbase,1e-9)
    latest_range=float(x['high'].iloc[-1]-x['low'].iloc[-1])/max(atr_now,1e-9)
    upper=float(x['high'].iloc[-1]-max(x['open'].iloc[-1],x['close'].iloc[-1]))
    body=abs(float(x['close'].iloc[-1]-x['open'].iloc[-1]))
    wick=upper/max(body,1e-9)
    danger=_clip(0.45*(latest_v/4.0)+0.35*(latest_range/2.5)+0.20*(wick/2.0))
    return 1.0-danger


def analyse_reversal_expansion(df:pd.DataFrame, context_window=80, base_window=18)->Dict[str,object]:
    required={'open','high','low','close','volume'}
    if not required.issubset(df.columns):raise ValueError(f'required columns: {sorted(required)}')
    if len(df)<max(100,context_window+20):raise ValueError('need at least 100 candles')
    d=df.copy().reset_index(drop=True)
    for c in required:d[c]=pd.to_numeric(d[c],errors='coerce')
    d=d.dropna(subset=list(required)).reset_index(drop=True)
    d['atr']=_atr(d,14)
    atr_now=float(d['atr'].iloc[-1])
    if not np.isfinite(atr_now) or atr_now<=0:raise ValueError('ATR unavailable')

    ctx=d.tail(context_window)
    pre=ctx.iloc[:-base_window]
    base=ctx.tail(base_window)
    now=float(d['close'].iloc[-1])

    # 1) prior markdown: negative trend before local base.
    norm=max(float(pre['close'].mean()),1e-9)
    slope=_slope(pre['close'].values)/norm
    start=float(pre['close'].iloc[0]); end=float(pre['close'].iloc[-1])
    markdown_pct=max(0.0,(start-end)/max(start,1e-9))
    prior_markdown=_clip(0.55*(markdown_pct/0.08)+0.45*(max(0,-slope)/0.0015))

    # 2) downside-efficiency should fade into the base.
    eff=_rolling_drawdown_efficiency(ctx['close'],8)
    if len(eff)>=12:
        early=float(np.mean(eff[:len(eff)//2])); late=float(np.mean(eff[len(eff)//2:]))
        downside_efficiency_decay=_clip((early-late)/max(early,0.10))
    else:downside_efficiency_decay=0.5

    # 3) selling exhaustion: red-candle volume and negative body size fade.
    red=ctx[ctx['close']<ctx['open']].copy()
    if len(red)>=6:
        half=len(red)//2
        ev=float(red['volume'].iloc[:half].mean()); lv=float(red['volume'].iloc[half:].mean())
        eb=float((red['open'].iloc[:half]-red['close'].iloc[:half]).mean()); lb=float((red['open'].iloc[half:]-red['close'].iloc[half:]).mean())
        selling_exhaustion=_clip(0.55*(1-lv/max(ev,1e-9))+0.45*(1-lb/max(eb,1e-9)))
    else:selling_exhaustion=0.5

    # 4) compression/base quality.
    base_range=float(base['high'].max()-base['low'].min())
    base_pct=base_range/max(float(base['close'].mean()),1e-9)
    base_atr=base_range/max(float(base['atr'].mean()),1e-9)
    base_compression=_clip(1-0.50*(base_pct/0.045)-0.50*(base_atr/5.0))

    pre_vol=float(pre['volume'].tail(30).mean())
    base_vol=float(base['volume'].iloc[:-3].mean()) if len(base)>3 else float(base['volume'].mean())
    volume_dryup=_clip((1.10-base_vol/max(pre_vol,1e-9))/0.75)

    # 5) first displacement: strongest bullish candle in recent base/trigger area.
    trig=ctx.tail(max(base_window+8,26)).copy()
    trig['body']=trig['close']-trig['open']
    trig['range']=trig['high']-trig['low']
    trig['rvol']=trig['volume']/d['volume'].tail(40).mean()
    trig['range_atr']=trig['range']/max(atr_now,1e-9)
    bulls=trig[trig['body']>0]
    if len(bulls):
        quality=0.55*bulls['range_atr']+0.45*bulls['rvol']
        idx=quality.idxmax(); disp=d.loc[idx]
        first_displacement=_clip(0.58*((float(disp['high']-disp['low'])/atr_now)/2.2)+0.42*((float(disp['close']-disp['open'])/atr_now)/1.5))
        displacement_rvol=_clip((float(disp['volume'])/max(float(d['volume'].iloc[max(0,idx-20):idx].mean()),1e-9))/3.0)
        disp_idx=int(idx)
    else:
        first_displacement=0.0;displacement_rvol=0.0;disp_idx=len(d)-1

    # 6) hold after first displacement.
    after=d.iloc[disp_idx+1:] if disp_idx+1<len(d) else d.iloc[0:0]
    disp_low=float(d['low'].iloc[disp_idx]); disp_high=float(d['high'].iloc[disp_idx])
    disp_move=max(disp_high-disp_low,1e-9)
    if len(after):
        retrace=max(0.0,disp_high-float(after['low'].min()))/disp_move
        shallow_hold=_clip(1-retrace/0.75)
    else:shallow_hold=0.45

    # higher lows in the most recent window.
    _,lows=_find_local_pivots(d.tail(35).reset_index(drop=True),2,2)
    if len(lows)>=3:
        vals=np.array([p for _,p in lows[-4:]])
        higher_low_quality=float((np.diff(vals)>=0).mean())
    elif len(lows)>=2:higher_low_quality=float(lows[-1][1]>=lows[-2][1])
    else:higher_low_quality=0.5

    # 7) second expansion / continuation after displacement.
    if len(after)>=2:
        new_high=max(0.0,float(after['high'].max()-disp_high))/max(atr_now,1e-9)
        green_share=float((after['close']>after['open']).mean())
        second_expansion=_clip(0.65*(new_high/2.5)+0.35*green_share)
    else:second_expansion=0.0

    # Acceptance above first displacement midpoint / broken balance.
    trigger_mid=(disp_high+disp_low)/2
    recent=d.tail(8)
    price_acceptance=float((recent['close']>=trigger_mid).mean())

    overhead_supply_safety=_overhead_supply_safety(d,atr_now)
    extension_safety=_extension_safety(d,atr_now)
    climax_safety=_climax_safety(d,atr_now)

    early_score=100*(
        0.18*prior_markdown+0.20*downside_efficiency_decay+0.18*selling_exhaustion+
        0.20*base_compression+0.14*volume_dryup+0.10*overhead_supply_safety
    )
    continuation_score=100*(
        0.24*first_displacement+0.20*displacement_rvol+0.16*shallow_hold+
        0.12*higher_low_quality+0.16*second_expansion+0.12*price_acceptance
    )
    chase_penalty=100*(1-(0.42*extension_safety+0.33*climax_safety+0.25*overhead_supply_safety))

    # Blend early setup quality and trigger quality. Penalty prevents late signals.
    score=0.52*early_score+0.48*continuation_score
    score*=1-0.40*(chase_penalty/100)
    score=float(np.clip(score,0,100))

    if chase_penalty>=72 and continuation_score>=55:
        state='DO_NOT_CHASE'
    elif score>=86 and continuation_score>=72 and overhead_supply_safety>=0.35:
        state='CONFIRMED'
    elif score>=76 and first_displacement>=0.50:
        state='READY'
    elif early_score>=68 and continuation_score<60:
        state='EARLY'
    elif score>=55:
        state='WATCH'
    else:
        state='IGNORE'

    snap=ReversalExpansionSnapshot(
        prior_markdown=round(100*prior_markdown,2),
        downside_efficiency_decay=round(100*downside_efficiency_decay,2),
        selling_exhaustion=round(100*selling_exhaustion,2),
        base_compression=round(100*base_compression,2),
        volume_dryup=round(100*volume_dryup,2),
        first_displacement=round(100*first_displacement,2),
        displacement_rvol=round(100*displacement_rvol,2),
        shallow_hold=round(100*shallow_hold,2),
        higher_low_quality=round(100*higher_low_quality,2),
        second_expansion=round(100*second_expansion,2),
        price_acceptance=round(100*price_acceptance,2),
        overhead_supply_safety=round(100*overhead_supply_safety,2),
        extension_safety=round(100*extension_safety,2),
        climax_safety=round(100*climax_safety,2),
        early_score=round(early_score,2),
        continuation_score=round(continuation_score,2),
        chase_penalty=round(chase_penalty,2),
        score=round(score,2),
        state=state,
    )
    return {
        'pattern_family':'BOTTOMING_COMPRESSION_TO_STAIRCASE_EXPANSION',
        'snapshot':asdict(snap),
        'levels':{
            'base_low':float(base['low'].min()),
            'base_high':float(base['high'].max()),
            'last_close':now,
            'first_displacement_low':disp_low,
            'first_displacement_high':disp_high,
        },
        'interpretation':{
            'early_candidate':early_score>=68,
            'trigger_present':first_displacement>=0.50 and displacement_rvol>=0.40,
            'continuation_present':second_expansion>=0.45 and price_acceptance>=0.60,
            'late_entry_risk':chase_penalty>=60,
        }
    }
