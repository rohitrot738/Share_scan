import numpy as np
import pandas as pd

def true_range(df):
    prev=df['close'].shift(1); return pd.concat([df['high']-df['low'],(df['high']-prev).abs(),(df['low']-prev).abs()],axis=1).max(axis=1)
def atr(df,window=14): return true_range(df).rolling(window).mean()
def rvol(df,window=20): return df['volume']/df['volume'].rolling(window).mean().replace(0,np.nan)
def ema(s,span): return s.ewm(span=span,adjust=False).mean()
def rsi(s,window=14):
    d=s.diff(); g=d.clip(lower=0).rolling(window).mean(); l=(-d.clip(upper=0)).rolling(window).mean(); rs=g/l.replace(0,np.nan); o=100-100/(1+rs); return o.where(l.ne(0),100.0)
def macd(s,fast=12,slow=26,signal=9):
    line=ema(s,fast)-ema(s,slow); sig=ema(line,signal); return line,sig,line-sig
def vwap(df):
    tp=(df['high']+df['low']+df['close'])/3; return (tp*df['volume']).cumsum()/df['volume'].cumsum().replace(0,np.nan)
def bollinger(s,window=20,std=2.0):
    mid=s.rolling(window).mean(); sd=s.rolling(window).std(); return mid,mid+std*sd,mid-std*sd
def stochastic(df,window=14,smooth=3):
    lo=df['low'].rolling(window).min(); hi=df['high'].rolling(window).max(); k=100*(df['close']-lo)/(hi-lo).replace(0,np.nan); d=k.rolling(smooth).mean(); return k,d
def adx(df,window=14):
    up=df['high'].diff(); dn=-df['low'].diff(); plus=up.where((up>dn)&(up>0),0.0); minus=dn.where((dn>up)&(dn>0),0.0); tr=true_range(df); atrv=tr.rolling(window).mean(); p=100*plus.rolling(window).mean()/atrv.replace(0,np.nan); m=100*minus.rolling(window).mean()/atrv.replace(0,np.nan); dx=100*(p-m).abs()/(p+m).replace(0,np.nan); return dx.rolling(window).mean(),p,m
def cci(df,window=20):
    tp=(df['high']+df['low']+df['close'])/3; ma=tp.rolling(window).mean(); md=tp.rolling(window).apply(lambda x: np.mean(np.abs(x-np.mean(x))),raw=True); return (tp-ma)/(0.015*md.replace(0,np.nan))
def mfi(df,window=14):
    tp=(df['high']+df['low']+df['close'])/3; flow=tp*df['volume']; pos=flow.where(tp.diff()>0,0.0).rolling(window).sum(); neg=flow.where(tp.diff()<0,0.0).rolling(window).sum(); return 100-100/(1+pos/neg.replace(0,np.nan))
def obv(df): return (np.sign(df['close'].diff()).fillna(0)*df['volume']).cumsum()
def roc(s,window=12): return s.pct_change(window)*100
def supertrend(df,window=10,mult=3.0):
    a=atr(df,window); mid=(df['high']+df['low'])/2; upper=mid+mult*a; lower=mid-mult*a; st=pd.Series(index=df.index,dtype=float); direction=pd.Series(index=df.index,dtype=float); direction.iloc[0]=1; st.iloc[0]=lower.iloc[0]
    for i in range(1,len(df)):
        direction.iloc[i]=1 if df['close'].iloc[i]>upper.iloc[i-1] else (-1 if df['close'].iloc[i]<lower.iloc[i-1] else direction.iloc[i-1]); st.iloc[i]=lower.iloc[i] if direction.iloc[i]>0 else upper.iloc[i]
    return st,direction
def ichimoku(df):
    tenkan=(df['high'].rolling(9).max()+df['low'].rolling(9).min())/2; kijun=(df['high'].rolling(26).max()+df['low'].rolling(26).min())/2; span_a=(tenkan+kijun)/2; span_b=(df['high'].rolling(52).max()+df['low'].rolling(52).min())/2; return tenkan,kijun,span_a,span_b
def pivot_levels(df):
    p=(df['high']+df['low']+df['close'])/3; return p,2*p-df['low'],2*p-df['high']

def add_basic_indicators(df,atr_window=14):
    out=df.copy(); out['atr']=atr(out,atr_window); out['rvol20']=rvol(out,20); out['ema9']=ema(out['close'],9); out['ema20']=ema(out['close'],20); out['ema50']=ema(out['close'],50); out['rsi14']=rsi(out['close'],14)
    out['macd'],out['macd_signal'],out['macd_hist']=macd(out['close']); out['vwap']=vwap(out); out['bb_mid'],out['bb_upper'],out['bb_lower']=bollinger(out['close']); out['stoch_k'],out['stoch_d']=stochastic(out); out['adx14'],out['plus_di'],out['minus_di']=adx(out); out['cci20']=cci(out); out['mfi14']=mfi(out); out['obv']=obv(out); out['roc12']=roc(out['close']); out['supertrend'],out['supertrend_dir']=supertrend(out); out['ichimoku_tenkan'],out['ichimoku_kijun'],out['ichimoku_a'],out['ichimoku_b']=ichimoku(out); out['pivot'],out['pivot_r1'],out['pivot_s1']=pivot_levels(out)
    out['body']=(out['close']-out['open']).abs(); out['range']=(out['high']-out['low']).replace(0,np.nan); out['upper_wick']=out['high']-out[['open','close']].max(axis=1); out['lower_wick']=out[['open','close']].min(axis=1)-out['low']; return out
