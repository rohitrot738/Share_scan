from __future__ import annotations
import json, re, sys
from pathlib import Path
import pandas as pd
import yfinance as yf

CSV=Path(sys.argv[1] if len(sys.argv)>1 else 'scan_output/top100_by_volume.csv'); JSON=CSV.with_name('top100_by_volume.json')
POS={'beat','growth','profit','surge','rise','rises','gain','gains','order','contract','upgrade','record','strong','expansion','approval','wins','win'}
NEG={'miss','loss','fall','falls','drop','drops','downgrade','fraud','probe','weak','decline','cuts','cut','lawsuit','penalty','default'}

def title_of(item):
    if not isinstance(item,dict): return ''
    c=item.get('content') if isinstance(item.get('content'),dict) else item
    return str(c.get('title') or '')

def sentiment(titles):
    if not titles:return None
    score=0
    for t in titles:
        words=set(re.findall(r'[a-z]+',t.lower())); score+=len(words&POS)-len(words&NEG)
    return max(0,min(100,50+score*8))

def main():
    df=pd.read_csv(CSV); df['news_sentiment_score']=pd.NA; df['news_count']=0; df['news_data_available']=False
    for i,row in df.head(15).iterrows():
        try:
            items=yf.Ticker(f"{row['symbol']}.NS").news or []; titles=[title_of(x) for x in items[:8] if title_of(x)]; s=sentiment(titles)
            if s is not None:
                df.at[i,'news_sentiment_score']=s; df.at[i,'news_count']=len(titles); df.at[i,'news_data_available']=True
        except Exception as e:print(f"[WARN] news {row['symbol']}: {e}")
    mask=df['news_data_available'].fillna(False)
    if mask.any():
        base=pd.to_numeric(df.loc[mask,'rank_score'],errors='coerce').fillna(0); ns=pd.to_numeric(df.loc[mask,'news_sentiment_score'],errors='coerce').fillna(50); df.loc[mask,'rank_score']=(.95*base+.05*ns).clip(0,100)
    df.to_csv(CSV,index=False)
    if JSON.exists():
        payload=json.loads(JSON.read_text(encoding='utf-8')); payload['mode']='NSE NEWS SENTIMENT BRANCH'; payload['results']=df.where(pd.notna(df),None).to_dict('records'); JSON.write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding='utf-8')
    print('news rows available',int(mask.sum()))
if __name__=='__main__':main()
