from __future__ import annotations
from .models import ResearchInput

REQUIRED_20Q=("quarterly_financials","balance_sheet_quarters","cashflow_quarters","shareholding_quarters")

def validate_360cr_input(x:ResearchInput|dict)->dict:
    x=x if isinstance(x,ResearchInput) else ResearchInput(**x)
    checks={k:len(getattr(x,k))>=20 for k in REQUIRED_20Q}
    checks.update({"price_156w":len(x.price_history)>=156*5,"insider_transactions":bool(x.insider_transactions),"bulk_block_deals":bool(x.bulk_block_deals),"corporate_actions":bool(x.corporate_actions),"fair_value_methods":sum(x.valuation.get(k) is not None for k in ("dcf_fair_value","earnings_fair_value","book_fair_value","peer_fair_value","analyst_fair_value"))>=2})
    return {"complete":all(checks.values()),"checks":checks,"failed":[k for k,v in checks.items() if not v]}
