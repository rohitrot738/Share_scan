from __future__ import annotations

import argparse
import json
from ghost_pro.data_collector import Auto360Collector


def main():
    p=argparse.ArgumentParser(description="Automatic 360CR data collector")
    p.add_argument("symbol")
    p.add_argument("--exchange",default="NSE",choices=["NSE","BSE"])
    p.add_argument("--refresh",action="store_true")
    p.add_argument("--out")
    args=p.parse_args()

    collector=Auto360Collector()
    packet=collector.collect(args.symbol,args.exchange,args.refresh)
    text=json.dumps(packet,indent=2,default=str)
    print(text)
    if args.out:
        with open(args.out,"w",encoding="utf-8") as f:
            f.write(text)


if __name__=="__main__":
    main()
