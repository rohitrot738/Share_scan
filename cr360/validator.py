from __future__ import annotations
from datetime import datetime
from .models import ResearchInput

REQUIRED_20Q=("quarterly_financials","balance_sheet_quarters","cashflow_quarters","shareholding_quarters")

def _parse_date(v):
    if not v:return None
    try:return datetime.fromisoformat(str(v).replace("Z","+00:00"))
    except:return None

def _has_156_weeks(rows):
    if len(rows)>=780:return True
    if len(rows)>=156:
        dates=[_parse_date(r.get("date") or r.get("datetime") or r.get("timestamp")) for r in rows]
        dates=[d for d in dates if d is not None]
        if len(dates)>=2 and (max(dates)-min(dates)).days>=1085:return True
        # 156+ observations are acceptable only when the collector explicitly says weekly.
        intervals={str(r.get("interval","")).lower() for r in rows}
        if intervals & {"1wk","1w","week","weekly"}:return True
    return False

def validate_360cr_input(x:ResearchInput|dict)->dict:
    x=x if isinstance(x,ResearchInput) else ResearchInput(**x)
    checks={k:len(getattr(x,k))>=20 for k in REQUIRED_20Q}
    fv=sum(1 for k in ("dcf_fair_value","earnings_fair_value","book_fair_value","peer_fair_value","analyst_fair_value") if x.valuation.get(k) is not None)
    checks.update({"price_156w":_has_156_weeks(x.price_history),"insider_transactions":bool(x.insider_transactions),"bulk_block_deals":bool(x.bulk_block_deals),"corporate_actions":bool(x.corporate_actions),"fair_value_methods":fv>=2})
    return {"complete":all(checks.values()),"checks":checks,"failed":[k for k,v in checks.items() if not v]}
