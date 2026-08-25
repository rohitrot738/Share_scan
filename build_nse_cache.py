from __future__ import annotations
import os, time
from pathlib import Path
import pandas as pd
from market_data import build_universe, download_batch
from live_scan import daily_prefilter_score

CACHE_DIR=Path(os.getenv("SCAN_CACHE_DIR",".scan_cache")); CACHE_FILE=CACHE_DIR/"nse_stage1.csv"
BATCH_SIZE=int(os.getenv("CACHE_BATCH_SIZE","300")); SLEEP=float(os.getenv("CACHE_BATCH_SLEEP","2.0"))

def main():
    universe=[x for x in build_universe(include_nse=True,include_bse=False) if x.exchange=="NSE"]
    by={x.yahoo_symbol:x for x in universe}; syms=list(by); rows=[]
    print(f"Building NSE cache for {len(syms)} symbols in batches of {BATCH_SIZE}")
    for i in range(0,len(syms),BATCH_SIZE):
        batch=syms[i:i+BATCH_SIZE]
        print(f"Batch {i//BATCH_SIZE+1}/{(len(syms)+BATCH_SIZE-1)//BATCH_SIZE}: {len(batch)}")
        try:data=download_batch(batch,"3mo","1d",retries=1)
        except Exception as e:
            print(f"[WARN] batch failed: {e}"); data={}
        for sym in batch:
            m=daily_prefilter_score(data.get(sym,pd.DataFrame()))
            if m.get("score",0)>0:
                inst=by[sym]; rows.append({"symbol":inst.symbol,"exchange":"NSE","yahoo_symbol":sym,"name":inst.name,**m})
        if i+BATCH_SIZE<len(syms):time.sleep(SLEEP)
    if not rows:raise SystemExit("Cache build produced no usable rows")
    df=pd.DataFrame(rows).sort_values(["score","turnover_proxy"],ascending=[False,False]).reset_index(drop=True)
    CACHE_DIR.mkdir(parents=True,exist_ok=True); df.to_csv(CACHE_FILE,index=False)
    print(f"Saved {len(df)} NSE cache rows to {CACHE_FILE}")
if __name__=="__main__":main()
