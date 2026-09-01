from __future__ import annotations
import json, sys
from pathlib import Path
import pandas as pd
import requests

CSV=Path(sys.argv[1] if len(sys.argv)>1 else 'scan_output/top100_by_volume.csv'); JSON=CSV.with_name('top100_by_volume.json')
HEADERS={'User-Agent':'Mozilla/5.0 ShareScan/1.0'}

def pct(v):
    try:return float(str(v).replace('%','').strip())
    except Exception:return None

def extract(symbol):
    r=requests.get(f'https://www.screener.in/company/{symbol}/consolidated/',headers=HEADERS,timeout=12); r.raise_for_status(); tables=pd.read_html(r.text)
    for t in tables:
        if t.empty or t.shape[1]<2:continue
        first=t.iloc[:,0].astype(str).str.strip().str.lower()
        if not first.str.contains('promoter').any():continue
        out={}
        for key,patterns in {'fii':['fii','foreign institutional','foreign portfolio'],'dii':['dii','domestic institutional']}.items():
            idx=None
            for p in patterns:
                m=first.str.contains(p,regex=False)
                if m.any():idx=m[m].index[0];break
            if idx is None:continue
            vals=[pct(x) for x in t.loc[idx].iloc[1:].tolist()]; vals=[x for x in vals if x is not None]
            if vals:out[key+'_latest']=vals[-1]; out[key+'_change']=round(vals[-1]-vals[-2],3) if len(vals)>1 else 0.0
        return out
    return {}

def score(d):
    if not d:return None
    changes=[d.get('fii_change'),d.get('dii_change')]; changes=[x for x in changes if x is not None]
    if not changes:return None
    return max(0,min(100,50+sum(changes)*18))

def main():
    df=pd.read_csv(CSV)
    for c in ['fii_latest','fii_change','dii_latest','dii_change','fii_dii_score']:df[c]=pd.NA
    df['fii_dii_data_available']=False
    for i,row in df.head(12).iterrows():
        try:
            d=extract(str(row['symbol'])); s=score(d)
            if s is not None:
                for k,v in d.items():df.at[i,k]=v
                df.at[i,'fii_dii_score']=round(s,2); df.at[i,'fii_dii_data_available']=True
        except Exception as e:print(f"[WARN] FII/DII {row['symbol']}: {e}")
    mask=df['fii_dii_data_available'].fillna(False)
    if mask.any():
        base=pd.to_numeric(df.loc[mask,'rank_score'],errors='coerce').fillna(0); fs=pd.to_numeric(df.loc[mask,'fii_dii_score'],errors='coerce').fillna(50); df.loc[mask,'rank_score']=(.93*base+.07*fs).clip(0,100)
    df.to_csv(CSV,index=False)
    if JSON.exists():
        payload=json.loads(JSON.read_text(encoding='utf-8')); payload['mode']='NSE CUMULATIVE + FII-DII'; payload['results']=df.where(pd.notna(df),None).to_dict('records'); JSON.write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding='utf-8')
    print('FII/DII rows available',int(mask.sum()))
if __name__=='__main__':main()
