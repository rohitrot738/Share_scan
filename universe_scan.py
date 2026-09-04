"""Run Share_scan across a practical NSE universe and persist ranked results.

The scanner deliberately keeps per-symbol failures isolated: one bad provider
response must not abort the entire market scan. Results are written as JSON and
CSV so GitHub Actions and ChatGPT can inspect the exact engine output later.
"""
from __future__ import annotations

import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import pandas as pd

from ghost_pro.full_pipeline import run_full_scan
from reporting import write_scan_bundle

# Liquid NSE universe starter set. This can be replaced/expanded by an exchange
# master-file collector later without changing the ranking/output contract.
DEFAULT_SYMBOLS = [
    "RELIANCE","HDFCBANK","ICICIBANK","SBIN","KOTAKBANK","AXISBANK",
    "INFY","TCS","HCLTECH","WIPRO","TECHM","LT","BHARTIARTL","ITC",
    "HINDUNILVR","MARUTI","M&M","TATAMOTORS","BAJFINANCE","BAJAJFINSV",
    "SUNPHARMA","DRREDDY","CIPLA","DIVISLAB","NTPC","POWERGRID","ONGC",
    "COALINDIA","TATASTEEL","JSWSTEEL","HINDALCO","ULTRACEMCO","GRASIM",
    "ADANIPORTS","BEL","HAL","MAZDOCK","COCHINSHIP","BDL","BHEL",
    "IRCTC","IRFC","IREDA","RVNL","RAILTEL","CDSL","CAMS","MCX",
    "TRENT","DMART","TITAN","ASIANPAINT","PIDILITIND","DIXON","POLYCAB",
    "PERSISTENT","COFORGE","KPITTECH","TATAELXSI","INDIGO","DLF","LODHA",
    "JIOFIN","PFC","RECLTD","CANBK","BANKBARODA","PNB","IDFCFIRSTB",
    "FEDERALBNK","INDHOTEL","TVSMOTOR","EICHERMOT","HEROMOTOCO","BOSCHLTD",
    "ABB","SIEMENS","CUMMINSIND","CGPOWER","SUZLON","WAAREEENER","PREMIERENE"
]


def _clean_symbols(values: Iterable[str]) -> list[str]:
    seen=set(); out=[]
    for raw in values:
        s=str(raw).strip().upper().replace(".NS","")
        if s and s not in seen:
            seen.add(s); out.append(s)
    return out


def scan(symbols: list[str], exchange="NSE", delay=0.25, force_refresh=True):
    rows=[]; details={}; errors={}
    total=len(symbols)
    for i,symbol in enumerate(symbols,1):
        print(f"[{i}/{total}] {symbol}", flush=True)
        try:
            result=run_full_scan(symbol, exchange=exchange, force_refresh=force_refresh)
            details[symbol]=result
            if result.get("status") != "OK":
                errors[symbol]=result.get("error","partial scan")
                continue
            x=result["summary"]
            rows.append({
                "symbol":symbol,
                "state":x.get("state"),
                "fused_score":x.get("fused_score"),
                "technical_score":x.get("technical_score"),
                "cr360_score":x.get("cr360_score"),
                "confidence":x.get("confidence"),
                "data_confidence":x.get("data_confidence"),
                "false_breakout_risk":x.get("false_breakout_risk"),
                "entry":x.get("entry"), "stop":x.get("stop"),
                "target1":x.get("target1"), "target2":x.get("target2"),
                "target3":x.get("target3"), "risk_pct":x.get("risk_pct"),
                "fundamental_bias":x.get("fundamental_bias"),
                "ownership_bias":x.get("ownership_bias"),
            })
        except Exception as exc:
            errors[symbol]=f"{type(exc).__name__}: {exc}"
        if delay: time.sleep(delay)

    # Prefer strong fused score, then lower false-break risk and higher confidence.
    rows.sort(key=lambda r:(
        float(r.get("fused_score") or 0),
        -float(r.get("false_breakout_risk") or 100),
        float(r.get("confidence") or 0),
    ), reverse=True)
    for rank,row in enumerate(rows,1): row["rank"]=rank
    return rows, details, errors


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--symbols", nargs="*", default=None)
    ap.add_argument("--symbols-file")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--delay", type=float, default=0.25)
    ap.add_argument("--output-dir", default="scan_results")
    args=ap.parse_args()

    symbols=list(args.symbols or DEFAULT_SYMBOLS)
    if args.symbols_file:
        p=Path(args.symbols_file)
        symbols += [x for x in p.read_text(encoding="utf-8").replace(",","\n").splitlines() if x.strip()]
    symbols=_clean_symbols(symbols)
    if args.limit>0: symbols=symbols[:args.limit]

    rows,details,errors=scan(symbols,delay=args.delay)
    out=Path(args.output_dir); out.mkdir(parents=True,exist_ok=True)
    stamp=datetime.now(timezone.utc).isoformat()
    payload={"generated_at_utc":stamp,"universe_size":len(symbols),"successful":len(rows),"errors":errors,"ranked":rows,"details":details}
    (out/"latest.json").write_text(json.dumps(payload,indent=2,default=str),encoding="utf-8")
    pd.DataFrame(rows).to_csv(out/"latest.csv",index=False)
    write_scan_bundle(out,payload,title="NSE Universe स्कैन")
    print("\nTOP RESULTS")
    for r in rows[:10]:
        print(f"#{r['rank']} {r['symbol']} {r['state']} fused={r['fused_score']} false={r['false_breakout_risk']} entry={r['entry']} sl={r['stop']} t1={r['target1']}")

if __name__ == "__main__":
    main()
