from __future__ import annotations

from typing import Iterable


def aggregate_results(rows:Iterable[dict],*,top_n:int=100)->list[dict]:
    if top_n<1:raise ValueError("top_n must be positive")
    best={}
    for row in rows:
        symbol=str(row.get("symbol","")).upper()
        if not symbol:continue
        previous=best.get(symbol)
        score=float(row.get("ghost_score") or row.get("rank_score") or 0)
        old=float(previous.get("ghost_score") or previous.get("rank_score") or 0) if previous else -1
        if previous is None or score>old:best[symbol]=dict(row)
    ordered=sorted(best.values(),key=lambda r:(float(r.get("volume") or 0),float(r.get("ghost_score") or 0),-float(r.get("false_breakout_risk") or 100)),reverse=True)[:top_n]
    for index,row in enumerate(ordered,1):row["rank"]=index
    return ordered
