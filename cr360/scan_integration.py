from __future__ import annotations
import json
from pathlib import Path
from .collector import collect_360cr
from .engine import analyse_360cr
from .validator import validate_360cr_input
from .nse_public_adapter import NSEPublicAdapter
from execution.batch_engine import run_batches
from execution.worker_pool import run_workers
from reporting import write_scan_bundle


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


def _enrich_batch(rows:list[dict],max_workers:int)->tuple[list[dict],dict[str,str]]:
    report=run_workers(rows,lambda row:{"symbol":row["symbol"],"research":_one(row["symbol"])},
        max_workers=max_workers,hard_limit=8,item_key=lambda row:row["symbol"])
    return report.results,report.errors


def enrich_payload(payload:dict,max_workers:int=4,batch_size:int=50)->dict:
    rows=payload.get("ranked",[]); enriched={}
    report=run_batches(rows,lambda batch:_enrich_batch(batch,max_workers),batch_size=batch_size,item_key=lambda r:r["symbol"])
    for item in report.results:enriched[item["symbol"]]=item["research"]
    errors=report.errors
    for r in rows:
        s=r["symbol"]
        if s in enriched:r.update(enriched[s])
        else:r.update({"cr360_score":None,"cr360_state":"INSUFFICIENT_DATA","cr360_confidence":0,"cr360_complete":False,"cr360_failed_checks":["collector_error"]})
    payload["mode"]="NSE_SCANNER_FULL_GHOST_VOLUME_360CR"
    payload["cr360_integrated"]=True
    payload["cr360_successful"]=len(enriched)
    payload["cr360_complete_count"]=sum(bool(r.get("cr360_complete")) for r in rows)
    payload["cr360_errors"]=errors
    payload["batch_engine"]={"batch_size":batch_size,"completed_batches":report.completed_batches,"total_items":report.total_items,"successful_items":report.successful_items,"failed_items":report.failed_items,"runtime_seconds":round(report.runtime_seconds,3)}
    # Preserve the user's volume-first ordering; 360CR is research confirmation, not a silent ranking replacement.
    return payload


def enrich_file(path="scan_results/latest.json",max_workers=4,batch_size=50):
    p=Path(path); payload=json.loads(p.read_text(encoding="utf-8")); payload=enrich_payload(payload,max_workers=max_workers,batch_size=batch_size)
    p.write_text(json.dumps(payload,indent=2,default=str),encoding="utf-8")
    write_scan_bundle(p.parent,payload,title="Ghost + 360CR स्कैन परिणाम")
    return payload

if __name__=="__main__":
    p=enrich_file()
    print(f"360CR integrated: {p['cr360_successful']}/{p['successful']}; complete={p['cr360_complete_count']}; errors={len(p['cr360_errors'])}")
