from __future__ import annotations
import json, sys
from pathlib import Path
import pandas as pd
import requests

CSV=Path(sys.argv[1] if len(sys.argv)>1 else 'scan_output/top100_by_volume.csv'); JSON=CSV.with_name('top100_by_volume.json'); HEADERS={'User-Agent':'Mozilla/5.0 ShareScan/1.0'}

def pct(v):
    try:return float(str(v).replace('%','').strip())
    except Exception:return None

def extract(symbol):
    r=requests.get(f'https://www.screener.in/company/{symbol}/consolidated/',headers=HEADERS,timeout=12); r.raise_for_status()
    for t in pd.read_html(r.text):
        if t.empty or t.shape[1]<2:continue
        first=t.iloc[:,0].astype(str).str.strip().str.lower(); m=first.str.contains('promoter',regex=False)
        if not m.any():continue
        row=t.loc[m[m].index[0]].iloc[1:].tolist(); vals=[pct(x) for x in row]; vals=[x for x in vals if x is not None]
        if vals:return {'promoter_latest':vals[-1],'promoter_change':round(vals[-1]-vals[-2],3) if len(vals)>1 else 0.0}
    return {}

def score(d):
    if not d:return None
    holding=d.get('promoter_latest'); change=d.get('promoter_change',0)
    if holding is None:return None
    base=85 if holding>=50 else 72 if holding>=35 else 55 if holding>=20 else 35
    return max(0,min(100,base+change*15))

def main():
    df=pd.read_csv(CSV); df['promoter_latest']=pd.NA; df['promoter_change']=pd.NA; df['promoter_score']=pd.NA; df['promoter_data_available']=False
    for i,row in df.head(12).iterrows():
        try:
            d=extract(str(row['symbol'])); s=score(d)
            if s is not None:
                for k,v in d.items():df.at[i,k]=v
                df.at[i,'promoter_score']=round(s,2); df.at[i,'promoter_data_available']=True
        except Exception as e:print(f"[WARN] promoter {row['symbol']}: {e}")
    mask=df['promoter_data_available'].fillna(False)
    if mask.any():
        base=pd.to_numeric(df.loc[mask,'rank_score'],errors='coerce').fillna(0); ps=pd.to_numeric(df.loc[mask,'promoter_score'],errors='coerce').fillna(50); df.loc[mask,'rank_score']=(.94*base+.06*ps).clip(0,100)
    df.to_csv(CSV,index=False)
    if JSON.exists():
        payload=json.loads(JSON.read_text(encoding='utf-8')); payload['mode']='NSE CUMULATIVE + PROMOTER'; payload['results']=df.where(pd.notna(df),None).to_dict('records'); JSON.write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding='utf-8')
    print('promoter rows available',int(mask.sum()))
if __name__=='__main__':main()
