from __future__ import annotations

import argparse
import json
from ghost_pro.full_pipeline import run_full_scan, compact_report


def main():
    p=argparse.ArgumentParser(description="Share_scan: Ghost Trade Pro + 360CR full pipeline")
    p.add_argument("symbol",help="Stock symbol, e.g. CDSL")
    p.add_argument("--exchange",default="NSE",choices=["NSE","BSE"])
    p.add_argument("--capital",type=float,default=100000.0)
    p.add_argument("--risk-pct",type=float,default=0.5,help="Capital risk percentage per trade")
    p.add_argument("--refresh",action="store_true")
    p.add_argument("--json",action="store_true",help="Print full JSON instead of compact report")
    p.add_argument("--out",help="Optional path to save full JSON")
    args=p.parse_args()

    result=run_full_scan(
        args.symbol,
        exchange=args.exchange,
        force_refresh=args.refresh,
        capital=args.capital,
        risk_pct=args.risk_pct,
    )

    if args.json:
        print(json.dumps(result,indent=2,default=str))
    else:
        print(compact_report(result))

    if args.out:
        with open(args.out,"w",encoding="utf-8") as f:
            json.dump(result,f,indent=2,default=str)


if __name__=="__main__":
    main()
