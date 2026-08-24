"""Pattern/setup detector library used by Ghost Trade Pro Ultimate."""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict, List
import numpy as np
import pandas as pd


@dataclass
class SetupResult:
    name: str
    active: bool
    score: float
    trigger: float
    invalidation: float
    notes: str


def atr(df,n=14):
    pc=df['close'].shift(1)
    tr=pd.concat([df['high']-df['low'],(df['high']-pc).abs(),(df['low']-pc).abs()],axis=1).max(axis=1)
    return tr.rolling(n,min_periods=5).mean()


def rvol(df,n=20):
    return df['volume']/df['volume'].rolling(n,min_periods=5).mean().replace(0,np.nan)


def pct_range(df,n):
    r=df.tail(n); lo=float(r['low'].min()); hi=float(r['high'].max()); mid=(lo+hi)/2
    return 0 if mid==0 else (hi-lo)/mid*100


def body_ratio(row):
    rng=max(float(row['high']-row['low']),1e-9); return abs(float(row['close']-row['open']))/rng


def bull_flag(df)->SetupResult:
    if len(df)<40:return SetupResult('bull_flag',False,0,0,0,'insufficient data')
    pre=df.iloc[-30:-10]; base=df.tail(10)
    low=float(pre['low'].min()); high=float(pre['high'].max()); impulse=(high-low)/max(low,1e-9)*100
    br=pct_range(base,10); support=float(base['low'].min()); resistance=float(base['high'].max()); close=float(base['close'].iloc[-1])
    shallow=max(0,min(1,(close-support)/max(resistance-support,1e-9)))
    vol_ratio=float(base['volume'].mean()/max(pre['volume'].mean(),1e-9))
    score=100*np.clip(0.35*min(impulse/6,1)+0.30*max(0,1-br/3.5)+0.20*max(0,1-vol_ratio)+0.15*shallow,0,1)
    return SetupResult('bull_flag',score>=68,score,resistance,support,f'impulse={impulse:.2f}% range={br:.2f}%')


def tight_base(df,n=12)->SetupResult:
    if len(df)<n+20:return SetupResult('tight_base',False,0,0,0,'insufficient data')
    base=df.tail(n); br=pct_range(base,n); a=float(atr(df).iloc[-1]); close=float(base['close'].iloc[-1]); support=float(base['low'].min()); resistance=float(base['high'].max())
    avg_rng=float((base['high']-base['low']).mean()); atr_ratio=avg_rng/max(a,1e-9)
    closes_upper=float((base['close']>support+(resistance-support)*0.55).mean())
    score=100*np.clip(0.45*max(0,1-br/3)+0.30*max(0,1-atr_ratio/1.2)+0.25*closes_upper,0,1)
    return SetupResult('tight_base',score>=70,score,resistance,support,f'range={br:.2f}% atr_ratio={atr_ratio:.2f}')


def volatility_contraction(df)->SetupResult:
    if len(df)<45:return SetupResult('volatility_contraction',False,0,0,0,'insufficient data')
    ranges=(df['high']-df['low']).abs(); r5=float(ranges.tail(5).mean()); r10=float(ranges.tail(10).mean()); r20=float(ranges.tail(20).mean()); r40=float(ranges.tail(40).mean())
    chain=float(r5<=r10<=r20<=r40); ratio=r5/max(r40,1e-9)
    resistance=float(df['high'].tail(12).max()); support=float(df['low'].tail(12).min())
    score=100*np.clip(0.55*chain+0.45*max(0,1-ratio),0,1)
    return SetupResult('volatility_contraction',score>=65,score,resistance,support,f'compression_ratio={ratio:.2f}')


def low_volume_pullback(df)->SetupResult:
    if len(df)<35:return SetupResult('low_volume_pullback',False,0,0,0,'insufficient data')
    base=df.iloc[-30:-8]; pb=df.tail(8); prior=float(base['volume'].mean()); pvol=float(pb['volume'].mean()); ratio=pvol/max(prior,1e-9)
    impulse=(float(base['high'].max())-float(base['low'].min()))/max(float(base['low'].min()),1e-9)*100
    draw=(float(base['high'].max())-float(pb['low'].min()))/max(float(base['high'].max())-float(base['low'].min()),1e-9)
    green=float((pb['close'].iloc[-1]>pb['open'].iloc[-1]))
    support=float(pb['low'].min()); resistance=float(pb['high'].max())
    score=100*np.clip(0.35*max(0,1-ratio)+0.30*max(0,1-draw/0.6)+0.20*min(impulse/5,1)+0.15*green,0,1)
    return SetupResult('low_volume_pullback',score>=68,score,resistance,support,f'volume_ratio={ratio:.2f} retrace={draw:.2f}')


def ascending_triangle(df,n=25)->SetupResult:
    if len(df)<n:return SetupResult('ascending_triangle',False,0,0,0,'insufficient data')
    r=df.tail(n); highs=r['high'].to_numpy(float); lows=r['low'].to_numpy(float); x=np.arange(n)
    hs=np.polyfit(x,highs,1)[0]/max(np.mean(highs),1e-9)*100; ls=np.polyfit(x,lows,1)[0]/max(np.mean(lows),1e-9)*100
    resistance=float(np.percentile(highs,90)); support=float(r['low'].tail(6).min())
    flat_high=max(0,1-abs(hs)/0.03); rising_low=np.clip(ls/0.05,0,1); compression=max(0,1-pct_range(r,8)/pct_range(r,n)) if pct_range(r,n)>0 else 0
    score=100*np.clip(0.4*flat_high+0.4*rising_low+0.2*compression,0,1)
    return SetupResult('ascending_triangle',score>=68,score,resistance,support,f'high_slope={hs:.3f} low_slope={ls:.3f}')


def resistance_absorption(df,n=16)->SetupResult:
    if len(df)<n+20:return SetupResult('resistance_absorption',False,0,0,0,'insufficient data')
    r=df.tail(n); resistance=float(r['high'].max()); a=float(atr(df).iloc[-1]); near=(resistance-r['close'])<=a*0.7
    near_r=r[near]; touches=len(near_r); lows=r['low'].to_numpy(float); slope=np.polyfit(np.arange(len(lows)),lows,1)[0] if len(lows)>2 else 0
    low_rising=float(slope>0); red=r[r['close']<r['open']]; dry=0.5
    if len(red)>=3:
        dry=float(np.clip(1-float(red['volume'].tail(2).mean())/max(float(red['volume'].head(2).mean()),1e-9),0,1))
    score=100*np.clip(0.35*min(touches/5,1)+0.30*low_rising+0.35*dry,0,1); support=float(r['low'].min())
    return SetupResult('resistance_absorption',score>=68,score,resistance,support,f'touches={touches} dry={dry:.2f}')


def opening_range_breakout(df,bars=6)->SetupResult:
    if len(df)<bars+3:return SetupResult('opening_range_breakout',False,0,0,0,'insufficient data')
    opening=df.iloc[:bars]; resistance=float(opening['high'].max()); support=float(opening['low'].min()); close=float(df['close'].iloc[-1]); rv=float(rvol(df).iloc[-1]) if np.isfinite(rvol(df).iloc[-1]) else 0
    above=max(0,(close-resistance)/max(resistance,1e-9)*100); hold=float(df['low'].iloc[-1]>=support)
    score=100*np.clip(0.45*min(above/1.0,1)+0.35*min(rv/2,1)+0.20*hold,0,1)
    return SetupResult('opening_range_breakout',close>resistance and score>=65,score,resistance,support,f'rvol={rv:.2f}')


def gap_hold(df)->SetupResult:
    if len(df)<3:return SetupResult('gap_hold',False,0,0,0,'insufficient data')
    prev_close=float(df['close'].iloc[-2]); o=float(df['open'].iloc[-1]); l=float(df['low'].iloc[-1]); c=float(df['close'].iloc[-1]); gap=(o-prev_close)/max(prev_close,1e-9)*100
    held=float(l>=prev_close); green=float(c>=o); score=100*np.clip(0.5*min(max(gap,0)/2,1)+0.3*held+0.2*green,0,1)
    return SetupResult('gap_hold',gap>0.5 and held and score>=60,score,max(o,c),prev_close,f'gap={gap:.2f}%')


def reclaim_vwap(df)->SetupResult:
    tp=(df['high']+df['low']+df['close'])/3; v=df['volume'].clip(lower=0); vw=(tp*v).cumsum()/v.cumsum().replace(0,np.nan)
    if len(df)<3 or not np.isfinite(vw.iloc[-1]):return SetupResult('reclaim_vwap',False,0,0,0,'vwap unavailable')
    prev=float(df['close'].iloc[-2]); cur=float(df['close'].iloc[-1]); vprev=float(vw.iloc[-2]); vcur=float(vw.iloc[-1]); rv=float(rvol(df).iloc[-1]) if np.isfinite(rvol(df).iloc[-1]) else 0
    reclaim=prev<=vprev and cur>vcur; score=100*np.clip(0.55*float(reclaim)+0.45*min(rv/2,1),0,1)
    return SetupResult('reclaim_vwap',reclaim and score>=60,score,vcur,float(df['low'].tail(4).min()),f'rvol={rv:.2f}')


def inside_bar_break(df)->SetupResult:
    if len(df)<3:return SetupResult('inside_bar_break',False,0,0,0,'insufficient data')
    mother=df.iloc[-3]; inside=df.iloc[-2]; cur=df.iloc[-1]
    is_inside=float(inside['high']<=mother['high'] and inside['low']>=mother['low']); broke=float(cur['close']>mother['high']); rv=float(rvol(df).iloc[-1]) if np.isfinite(rvol(df).iloc[-1]) else 0
    score=100*np.clip(0.45*is_inside+0.35*broke+0.20*min(rv/2,1),0,1)
    return SetupResult('inside_bar_break',bool(is_inside and broke and score>=65),score,float(mother['high']),float(inside['low']),f'rvol={rv:.2f}')


def narrow_range_break(df,n=7)->SetupResult:
    if len(df)<n+2:return SetupResult('narrow_range_break',False,0,0,0,'insufficient data')
    ranges=(df['high']-df['low']).tail(n); narrow=float(ranges.iloc[-2]<=ranges.iloc[:-1].min()); trigger=float(df['high'].iloc[-2]); broke=float(df['close'].iloc[-1]>trigger); rv=float(rvol(df).iloc[-1]) if np.isfinite(rvol(df).iloc[-1]) else 0
    score=100*np.clip(0.45*narrow+0.35*broke+0.20*min(rv/2,1),0,1)
    return SetupResult('narrow_range_break',bool(narrow and broke and score>=65),score,trigger,float(df['low'].iloc[-2]),f'rvol={rv:.2f}')


def three_bar_pressure(df)->SetupResult:
    if len(df)<5:return SetupResult('three_bar_pressure',False,0,0,0,'insufficient data')
    r=df.tail(3); green=float((r['close']>r['open']).sum())/3; rising=float(np.all(np.diff(r['low'].to_numpy(float))>=0)); close_high=float(((r['close']-r['low'])/(r['high']-r['low']).replace(0,np.nan)).fillna(.5).mean()); score=100*np.clip(.35*green+.35*rising+.30*close_high,0,1)
    return SetupResult('three_bar_pressure',score>=70,score,float(r['high'].max()),float(r['low'].min()),f'green_ratio={green:.2f}')


def failed_breakdown_reclaim(df,n=20)->SetupResult:
    if len(df)<n+2:return SetupResult('failed_breakdown_reclaim',False,0,0,0,'insufficient data')
    prior=df.iloc[-n-1:-1]; level=float(prior['low'].min()); cur=df.iloc[-1]; swept=float(cur['low']<level and cur['close']>level); rv=float(rvol(df).iloc[-1]) if np.isfinite(rvol(df).iloc[-1]) else 0
    wick=(min(float(cur['open']),float(cur['close']))-float(cur['low']))/max(float(cur['high']-cur['low']),1e-9); score=100*np.clip(.5*swept+.3*min(rv/2,1)+.2*min(wick*2,1),0,1)
    return SetupResult('failed_breakdown_reclaim',bool(swept and score>=65),score,float(cur['high']),float(cur['low']),f'rvol={rv:.2f}')


def pre_breakout_pressure(df,n=15)->SetupResult:
    if len(df)<n+20:return SetupResult('pre_breakout_pressure',False,0,0,0,'insufficient data')
    r=df.tail(n); resistance=float(r['high'].max()); support=float(r['low'].min()); a=float(atr(df).iloc[-1]); close=float(r['close'].iloc[-1]); distance=(resistance-close)/max(a,1e-9)
    lows=r['low'].to_numpy(float); slope=np.polyfit(np.arange(len(lows)),lows,1)[0]/max(np.mean(lows),1e-9)*100; near=float(distance<=1.0); rising=np.clip(slope/0.04,0,1); dry=max(0,1-float(r['volume'].tail(5).mean())/max(float(df['volume'].tail(30).mean()),1e-9)); score=100*np.clip(.40*near+.35*rising+.25*dry,0,1)
    return SetupResult('pre_breakout_pressure',score>=68,score,resistance,support,f'distance_atr={distance:.2f} low_slope={slope:.3f}')


def detect_all_setups(df)->Dict[str,object]:
    funcs=[bull_flag,tight_base,volatility_contraction,low_volume_pullback,ascending_triangle,resistance_absorption,opening_range_breakout,gap_hold,reclaim_vwap,inside_bar_break,narrow_range_break,three_bar_pressure,failed_breakdown_reclaim,pre_breakout_pressure]
    results=[]
    for f in funcs:
        try: results.append(f(df))
        except Exception as e: results.append(SetupResult(f.__name__,False,0,0,0,f'error:{e}'))
    active=[r for r in results if r.active]; best=max(results,key=lambda x:x.score) if results else SetupResult('none',False,0,0,0,'')
    ensemble=np.mean(sorted([r.score for r in results],reverse=True)[:5]) if results else 0
    return {'ensemble_score':float(ensemble),'active_count':len(active),'best':asdict(best),'setups':[asdict(r) for r in results]}
