from __future__ import annotations
import json
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from .collector import collect_360cr
from .engine import analyse_360cr
from .validator import validate_360cr_input
from .nse_public_adapter import NSEPublicAdapter


def _one(symbol:str)->dict:
    adapter=NSEPublicAdapter(timeout=12,retries=2,sleep=.25)
    x=collect_360cr(symbol,regulatory_adapter=adapter)
    result=analyse_360cr(x)
    validation=validate_360cr_input(x)
    return {
        "cr360_score":result.score,
        "cr360_state":result.state,
        "cr360_confidence":result.confidence,
        "cr360_complete":validation["complete"],
        "cr360_failed_checks":validation["failed"],
        "cr360_coverage":result.coverage,
        "cr360_sections":result.sections,
        "cr360_fair_value":result.fair_value,
        "cr360_risk":result.risk,
        "cr360_missing":result.missing,
        "cr360_warnings":result.warnings,
        "cr360_evidence":result.evidence,
        "cr360_metadata":x.metadata,
    }


def enrich_payload(payload:dict,max_workers:int=4)->dict:
    rows=payload.get("ranked",[]); errors={}; enriched={}
    with ThreadPoolExecutor(max_workers=max(1,min(max_workers,8))) as ex:
        futures={ex.submit(_one,r["symbol"]):r["symbol"] for r in rows}
        for f in as_completed(futures):
            s=futures[f]
            try:enriched[s]=f.result()
            except Exception as e:errors[s]=f"{type(e).__name__}: {e}"
    for r in rows:
        s=r["symbol"]
        if s in enriched:r.update(enriched[s])
        else:r.update({"cr360_score":None,"cr360_state":"INSUFFICIENT_DATA","cr360_confidence":0,"cr360_complete":False,"cr360_failed_checks":["collector_error"]})
    payload["mode"]="NSE_SCANNER_FULL_GHOST_VOLUME_360CR"
    payload["cr360_integrated"]=True
    payload["cr360_successful"]=len(enriched)
    payload["cr360_complete_count"]=sum(bool(r.get("cr360_complete")) for r in rows)
    payload["cr360_errors"]=errors
    # Preserve the user's volume-first ordering; 360CR is research confirmation, not a silent ranking replacement.
    return payload


def enrich_file(path="scan_results/latest.json",max_workers=4):
    p=Path(path); payload=json.loads(p.read_text(encoding="utf-8")); payload=enrich_payload(payload,max_workers=max_workers)
    p.write_text(json.dumps(payload,indent=2,default=str),encoding="utf-8")
    return payload

if __name__=="__main__":
    p=enrich_file()
    print(f"360CR integrated: {p['cr360_successful']}/{p['successful']}; complete={p['cr360_complete_count']}; errors={len(p['cr360_errors'])}")
