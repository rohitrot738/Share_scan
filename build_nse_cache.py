from __future__ import annotations
import os, time
from pathlib import Path
import pandas as pd
from market_data import build_universe, download_batch
from market_cap import (
    DEFAULT_MIN_MARKET_CAP_CR,
    apply_market_cap_filter,
    fetch_nse_issued_capital,
)
from live_scan import daily_prefilter_score

CACHE_DIR=Path(os.getenv("SCAN_CACHE_DIR",".scan_cache")); CACHE_FILE=CACHE_DIR/"nse_stage1.csv"
BATCH_SIZE=int(os.getenv("CACHE_BATCH_SIZE","300")); SLEEP=float(os.getenv("CACHE_BATCH_SLEEP","2.0"))
MIN_MARKET_CAP_CR=float(os.getenv("MIN_MARKET_CAP_CR",str(DEFAULT_MIN_MARKET_CAP_CR)))

def main():
    capital_snapshot=fetch_nse_issued_capital()
    print(
        f"NSE issued-capital snapshot: {capital_snapshot.as_of.isoformat()} "
        f"({len(capital_snapshot.issued_shares)} EQ symbols)"
    )
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
    rows,stats=apply_market_cap_filter(
        rows,
        capital_snapshot,
        min_market_cap_cr=MIN_MARKET_CAP_CR,
    )
    print(
        f"Market-cap filter > Rs {MIN_MARKET_CAP_CR:.2f} crore: "
        f"eligible={stats['eligible_rows']}/{stats['input_rows']} "
        f"missing={stats['missing_market_cap']}"
    )
    if not rows:
        raise SystemExit("Cache build produced no rows above the market-cap limit")
    df=pd.DataFrame(rows).sort_values(["score","turnover_proxy"],ascending=[False,False]).reset_index(drop=True)
    CACHE_DIR.mkdir(parents=True,exist_ok=True); df.to_csv(CACHE_FILE,index=False)
    print(f"Saved {len(df)} NSE cache rows to {CACHE_FILE}")
if __name__=="__main__":main()
