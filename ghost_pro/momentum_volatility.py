"""Momentum, trend, volatility and regime engine for Ghost Trade Pro."""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Dict
import numpy as np
import pandas as pd


@dataclass
class MomentumSnapshot:
    rsi: float
    macd_hist: float
    stochastic_k: float
    adx: float
    atr_pct: float
    bb_width: float
    squeeze: float
    trend_score: float
    momentum_score: float
    regime: str


def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False).mean()


def sma(s: pd.Series, n: int) -> pd.Series:
    return s.rolling(n, min_periods=max(2,n//4)).mean()


def true_range(df: pd.DataFrame) -> pd.Series:
    pc = df["close"].shift(1)
    return pd.concat([
        df["high"]-df["low"],
        (df["high"]-pc).abs(),
        (df["low"]-pc).abs(),
    ],axis=1).max(axis=1)


def atr(df: pd.DataFrame,n:int=14)->pd.Series:
    return true_range(df).rolling(n,min_periods=max(3,n//3)).mean()


def rsi(s: pd.Series,n:int=14)->pd.Series:
    d=s.diff(); up=d.clip(lower=0); dn=(-d).clip(lower=0)
    au=up.ewm(alpha=1/n,adjust=False).mean(); ad=dn.ewm(alpha=1/n,adjust=False).mean()
    rs=au/ad.replace(0,np.nan)
    return 100-(100/(1+rs))


def macd(s: pd.Series,fast:int=12,slow:int=26,signal:int=9):
    m=ema(s,fast)-ema(s,slow); sig=ema(m,signal); hist=m-sig
    return m,sig,hist


def stochastic(df: pd.DataFrame,n:int=14,smooth:int=3):
    lo=df["low"].rolling(n,min_periods=3).min(); hi=df["high"].rolling(n,min_periods=3).max()
    k=100*(df["close"]-lo)/(hi-lo).replace(0,np.nan); d=k.rolling(smooth,min_periods=1).mean()
    return k,d


def adx(df: pd.DataFrame,n:int=14):
    up=df["high"].diff(); down=-df["low"].diff()
    plus=np.where((up>down)&(up>0),up,0.0); minus=np.where((down>up)&(down>0),down,0.0)
    tr=true_range(df)
    atrn=tr.ewm(alpha=1/n,adjust=False).mean().replace(0,np.nan)
    pdi=100*pd.Series(plus,index=df.index).ewm(alpha=1/n,adjust=False).mean()/atrn
    mdi=100*pd.Series(minus,index=df.index).ewm(alpha=1/n,adjust=False).mean()/atrn
    dx=100*(pdi-mdi).abs()/(pdi+mdi).replace(0,np.nan)
    return dx.ewm(alpha=1/n,adjust=False).mean(),pdi,mdi


def bollinger(s:pd.Series,n:int=20,mult:float=2.0):
    mid=sma(s,n); sd=s.rolling(n,min_periods=5).std(ddof=0); return mid,mid+mult*sd,mid-mult*sd


def keltner(df:pd.DataFrame,n:int=20,mult:float=1.5):
    mid=ema(df["close"],n); a=atr(df,n); return mid,mid+mult*a,mid-mult*a


def donchian(df:pd.DataFrame,n:int=20):
    return df["high"].rolling(n,min_periods=3).max(),df["low"].rolling(n,min_periods=3).min()


def rate_of_change(s:pd.Series,n:int=10):
    return (s/s.shift(n)-1)*100


def momentum(s:pd.Series,n:int=10):
    return s-s.shift(n)


def efficiency_ratio(s:pd.Series,n:int=10):
    change=(s-s.shift(n)).abs(); noise=s.diff().abs().rolling(n,min_periods=3).sum(); return change/noise.replace(0,np.nan)


def choppiness(df:pd.DataFrame,n:int=14):
    trsum=true_range(df).rolling(n,min_periods=3).sum(); hh=df["high"].rolling(n,min_periods=3).max(); ll=df["low"].rolling(n,min_periods=3).min()
    return 100*np.log10(trsum/(hh-ll).replace(0,np.nan))/np.log10(n)


def bb_width(df:pd.DataFrame,n:int=20):
    mid,up,lo=bollinger(df["close"],n); return (up-lo)/mid.replace(0,np.nan)


def squeeze_score(df:pd.DataFrame,n:int=20)->float:
    _,bu,bl=bollinger(df["close"],n); _,ku,kl=keltner(df,n)
    if any(not np.isfinite(x.iloc[-1]) for x in (bu,bl,ku,kl)): return 0.0
    inside=float((bu.iloc[-1]<ku.iloc[-1]) and (bl.iloc[-1]>kl.iloc[-1]))
    bw=bb_width(df,n); current=float(bw.iloc[-1]); med=float(bw.tail(60).median()) if len(bw.dropna()) else current
    compression=0.0 if med<=0 else float(np.clip(1-current/med,0,1))
    return 100*(0.55*inside+0.45*compression)


def ema_alignment(df:pd.DataFrame)->float:
    c=float(df["close"].iloc[-1]); e9=float(ema(df["close"],9).iloc[-1]); e20=float(ema(df["close"],20).iloc[-1]); e50=float(ema(df["close"],50).iloc[-1]); e100=float(ema(df["close"],100).iloc[-1]) if len(df)>=100 else e50
    checks=[c>e9,e9>e20,e20>e50,e50>=e100]
    return 100*sum(checks)/len(checks)


def slope_score(s:pd.Series,n:int=20)->float:
    y=s.tail(n).to_numpy(float)
    if len(y)<3:return 50.0
    x=np.arange(len(y),dtype=float); m=np.polyfit(x,y,1)[0]; base=max(np.mean(np.abs(y)),1e-9)
    normalized=m/base*100
    return float(np.clip(50+normalized*400,0,100))


def higher_timeframe_trend_proxy(df:pd.DataFrame)->float:
    scores=[ema_alignment(df),slope_score(df["close"],20),slope_score(df["close"],50)]
    return float(np.mean(scores))


def rsi_quality(df:pd.DataFrame)->float:
    x=rsi(df["close"],14); cur=float(x.iloc[-1]) if np.isfinite(x.iloc[-1]) else 50
    prev=float(x.iloc[-4]) if len(x)>=4 and np.isfinite(x.iloc[-4]) else cur
    if 52<=cur<=72:base=85
    elif 45<=cur<52:base=60
    elif 72<cur<=80:base=55
    elif cur>80:base=30
    else:base=35
    accel=np.clip((cur-prev)/8,-1,1)*15
    return float(np.clip(base+accel,0,100))


def macd_quality(df:pd.DataFrame)->float:
    m,s,h=macd(df["close"]); cur=float(h.iloc[-1]) if np.isfinite(h.iloc[-1]) else 0; prev=float(h.iloc[-3]) if len(h)>=3 and np.isfinite(h.iloc[-3]) else cur
    scale=max(float(df["close"].tail(30).mean()),1e-9)
    norm=cur/scale*100; improving=cur>prev
    return float(np.clip(50+norm*500+(15 if improving else -10),0,100))


def stochastic_quality(df:pd.DataFrame)->float:
    k,d=stochastic(df); kv=float(k.iloc[-1]) if np.isfinite(k.iloc[-1]) else 50; dv=float(d.iloc[-1]) if np.isfinite(d.iloc[-1]) else 50
    cross=kv-dv
    base=75 if 40<=kv<=80 else 50 if kv<40 else 40
    return float(np.clip(base+cross*1.2,0,100))


def adx_quality(df:pd.DataFrame)->float:
    a,p,m=adx(df); av=float(a.iloc[-1]) if np.isfinite(a.iloc[-1]) else 0; pv=float(p.iloc[-1]) if np.isfinite(p.iloc[-1]) else 0; mv=float(m.iloc[-1]) if np.isfinite(m.iloc[-1]) else 0
    trend=np.clip((av-15)/25,0,1); direction=np.clip((pv-mv+20)/40,0,1)
    return float(100*(0.6*trend+0.4*direction))


def atr_percentile(df:pd.DataFrame,length:int=100)->float:
    a=atr(df,14); ap=(a/df["close"].replace(0,np.nan)*100).dropna()
    if ap.empty:return 50.0
    cur=float(ap.iloc[-1]); hist=ap.tail(length); return float(100*(hist<=cur).mean())


def volatility_regime(df:pd.DataFrame)->str:
    p=atr_percentile(df,100); chop=choppiness(df,14); c=float(chop.iloc[-1]) if np.isfinite(chop.iloc[-1]) else 50
    if p>=80:return "HIGH_VOL"
    if p<=25:return "LOW_VOL"
    if c>=60:return "CHOPPY"
    if c<=40:return "TRENDING"
    return "NORMAL"


def breakout_energy(df:pd.DataFrame)->float:
    roc=rate_of_change(df["close"],5).iloc[-1]; roc=0.0 if not np.isfinite(roc) else float(roc)
    er=efficiency_ratio(df["close"],10).iloc[-1]; er=0.0 if not np.isfinite(er) else float(er)
    a=atr(df,14).iloc[-1]; a=float(a) if np.isfinite(a) else 0.0
    body=abs(float(df["close"].iloc[-1]-df["open"].iloc[-1])); body_atr=body/max(a,1e-9)
    return float(np.clip(0.35*np.clip(roc/3,0,1)+0.35*np.clip(er,0,1)+0.30*np.clip(body_atr/1.5,0,1),0,1)*100)


def contraction_expansion_cycle(df:pd.DataFrame)->Dict[str,float|str]:
    bw=bb_width(df,20).dropna(); ap=(atr(df,14)/df["close"].replace(0,np.nan)).dropna()
    if len(bw)<10 or len(ap)<10:return {"contraction":0.0,"expansion":0.0,"phase":"UNKNOWN"}
    bcur=float(bw.iloc[-1]); bmed=float(bw.tail(50).median()); acur=float(ap.iloc[-1]); amed=float(ap.tail(50).median())
    contraction=float(np.clip(0.5*(1-bcur/max(bmed,1e-9))+0.5*(1-acur/max(amed,1e-9)),0,1))
    expansion=float(np.clip(0.5*(bcur/max(bmed,1e-9)-1)+0.5*(acur/max(amed,1e-9)-1),0,1))
    phase="CONTRACTION" if contraction>0.35 else "EXPANSION" if expansion>0.35 else "NEUTRAL"
    return {"contraction":100*contraction,"expansion":100*expansion,"phase":phase}


def momentum_snapshot(df:pd.DataFrame)->MomentumSnapshot:
    rs=rsi(df["close"],14); _,_,mh=macd(df["close"]); sk,_=stochastic(df); ax,_,_=adx(df); a=atr(df,14); bw=bb_width(df,20)
    close=max(float(df["close"].iloc[-1]),1e-9)
    rsn=float(rs.iloc[-1]) if np.isfinite(rs.iloc[-1]) else 50; mhn=float(mh.iloc[-1]) if np.isfinite(mh.iloc[-1]) else 0; skn=float(sk.iloc[-1]) if np.isfinite(sk.iloc[-1]) else 50; axn=float(ax.iloc[-1]) if np.isfinite(ax.iloc[-1]) else 0
    ap=float(a.iloc[-1])/close*100 if np.isfinite(a.iloc[-1]) else 0; bwn=float(bw.iloc[-1]) if np.isfinite(bw.iloc[-1]) else 0
    trend=0.45*ema_alignment(df)+0.30*adx_quality(df)+0.25*slope_score(df["close"],20)
    mom=0.35*rsi_quality(df)+0.30*macd_quality(df)+0.20*stochastic_quality(df)+0.15*breakout_energy(df)
    return MomentumSnapshot(rsn,mhn,skn,axn,ap,bwn,squeeze_score(df),float(np.clip(trend,0,100)),float(np.clip(mom,0,100)),volatility_regime(df))


def momentum_volatility_report(df:pd.DataFrame)->Dict[str,object]:
    snap=momentum_snapshot(df)
    return {"snapshot":asdict(snap),"cycle":contraction_expansion_cycle(df),"ema_alignment":ema_alignment(df),"higher_tf_trend_proxy":higher_timeframe_trend_proxy(df),"breakout_energy":breakout_energy(df),"atr_percentile":atr_percentile(df)}
