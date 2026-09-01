from __future__ import annotations
import json, math, sys
from pathlib import Path
import pandas as pd
import yfinance as yf

CSV=Path(sys.argv[1] if len(sys.argv)>1 else 'scan_output/top100_by_volume.csv')
JSON=CSV.with_name('top100_by_volume.json')

def num(v):
    try:
        x=float(v); return x if math.isfinite(x) else None
    except Exception:return None

def score_info(info):
    roe=num(info.get('returnOnEquity')); margin=num(info.get('profitMargins')); growth=num(info.get('revenueGrowth')); debt=num(info.get('debtToEquity')); pe=num(info.get('trailingPE'))
    parts=[]
    if roe is not None: parts.append(max(0,min(100,50+roe*180)))
    if margin is not None: parts.append(max(0,min(100,45+margin*180)))
    if growth is not None: parts.append(max(0,min(100,50+growth*160)))
    if debt is not None: parts.append(max(0,min(100,100-debt*.55)))
    if pe is not None: parts.append(85 if 8<=pe<=35 else 65 if 0<pe<60 else 35)
    return round(sum(parts)/len(parts),2) if parts else None

def main():
    df=pd.read_csv(CSV); df['fundamental_score']=pd.NA; df['fundamental_data_available']=False
    for i,row in df.head(15).iterrows():
        try:
            info=yf.Ticker(f"{row['symbol']}.NS").info or {}; s=score_info(info)
            if s is not None:
                df.at[i,'fundamental_score']=s; df.at[i,'fundamental_data_available']=True
        except Exception as e: print(f"[WARN] fundamentals {row['symbol']}: {e}")
    mask=df['fundamental_data_available'].fillna(False)
    if mask.any():
        base=pd.to_numeric(df.loc[mask,'rank_score'],errors='coerce').fillna(0); fs=pd.to_numeric(df.loc[mask,'fundamental_score'],errors='coerce').fillna(50)
        df.loc[mask,'rank_score']=(.90*base+.10*fs).clip(0,100)
    df.to_csv(CSV,index=False)
    if JSON.exists():
        payload=json.loads(JSON.read_text(encoding='utf-8')); payload['mode']='NSE FUNDAMENTALS BRANCH'; payload['results']=df.where(pd.notna(df),None).to_dict('records'); JSON.write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding='utf-8')
    print('fundamental rows available',int(mask.sum()))
if __name__=='__main__': main()
