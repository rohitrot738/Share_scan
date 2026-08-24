from __future__ import annotations

from typing import Any, Dict, Iterable
import math
import statistics


def _num(v):
    try:
        if v is None:
            return None
        x = float(v)
        if math.isnan(x) or math.isinf(x):
            return None
        return x
    except Exception:
        return None


def _pick(record: Dict[str, Any], names: Iterable[str]):
    lower = {str(k).lower(): v for k, v in record.items()}
    for n in names:
        if n.lower() in lower:
            return _num(lower[n.lower()])
    for k, v in lower.items():
        if any(n.lower() in k for n in names):
            x = _num(v)
            if x is not None:
                return x
    return None


def _growth(old, new):
    if old in (None, 0) or new is None:
        return None
    return (new / old - 1.0) * 100.0


def normalize_quarters(financials: Dict[str, Any], max_quarters: int = 20):
    inc = financials.get("quarterly_income_statement", [])[:max_quarters]
    bs = financials.get("quarterly_balance_sheet", [])[:max_quarters]
    cf = financials.get("quarterly_cash_flow", [])[:max_quarters]
    by_period: Dict[str, Dict[str, Any]] = {}
    for src, bucket in [(inc,"inc"),(bs,"bs"),(cf,"cf")]:
        for rec in src:
            p = str(rec.get("period"))
            by_period.setdefault(p,{"period":p})[bucket] = rec
    rows=[]
    for p, group in by_period.items():
        i=group.get("inc",{}); b=group.get("bs",{}); c=group.get("cf",{})
        revenue=_pick(i,["Total Revenue","Operating Revenue","Revenue"])
        ebit=_pick(i,["EBIT","Operating Income"])
        ebitda=_pick(i,["EBITDA","Normalized EBITDA"])
        pat=_pick(i,["Net Income Common Stockholders","Net Income","Net Income Continuous Operations"])
        eps=_pick(i,["Diluted EPS","Basic EPS"])
        equity=_pick(b,["Stockholders Equity","Total Equity Gross Minority Interest","Common Stock Equity"])
        debt=_pick(b,["Total Debt"])
        cash=_pick(b,["Cash Cash Equivalents And Short Term Investments","Cash And Cash Equivalents"])
        assets=_pick(b,["Total Assets"])
        receivables=_pick(b,["Accounts Receivable","Receivables"])
        cfo=_pick(c,["Operating Cash Flow","Total Cash From Operating Activities"])
        capex=_pick(c,["Capital Expenditure","Capital Expenditures"])
        fcf=_pick(c,["Free Cash Flow"])
        if fcf is None and cfo is not None and capex is not None:
            fcf=cfo+capex if capex<0 else cfo-capex
        margin=None if revenue in (None,0) else ((ebitda if ebitda is not None else ebit) or 0)/revenue*100
        rows.append({
            "period":p,"revenue":revenue,"ebit":ebit,"ebitda":ebitda,"pat":pat,"eps":eps,
            "equity":equity,"debt":debt,"cash":cash,"assets":assets,"receivables":receivables,
            "cfo":cfo,"capex":capex,"fcf":fcf,"operating_margin_pct":margin,
        })
    rows.sort(key=lambda x:x["period"], reverse=True)
    return rows[:max_quarters]


def derive_financial_metrics(quarters):
    q=list(quarters)
    rev=[r.get("revenue") for r in q if r.get("revenue") is not None]
    pat=[r.get("pat") for r in q if r.get("pat") is not None]
    margins=[r.get("operating_margin_pct") for r in q if r.get("operating_margin_pct") is not None]
    cfo=[r.get("cfo") for r in q if r.get("cfo") is not None]
    fcf=[r.get("fcf") for r in q if r.get("fcf") is not None]
    latest=q[0] if q else {}
    oldest=q[-1] if q else {}
    debt=latest.get("debt"); equity=latest.get("equity")
    debt_equity=None if debt is None or equity in (None,0) else debt/equity
    cfo_pat=None
    if cfo and pat:
        denom=sum(pat[:min(len(pat),8)])
        if denom:
            cfo_pat=sum(cfo[:min(len(cfo),8)])/denom
    return {
        "quarter_count":len(q),
        "revenue_growth_span_pct":_growth(oldest.get("revenue"),latest.get("revenue")),
        "pat_growth_span_pct":_growth(oldest.get("pat"),latest.get("pat")),
        "latest_operating_margin_pct":latest.get("operating_margin_pct"),
        "median_operating_margin_pct":statistics.median(margins) if margins else None,
        "margin_stability_std":statistics.pstdev(margins) if len(margins)>1 else None,
        "debt_to_equity":debt_equity,
        "cash_flow_conversion":cfo_pat,
        "positive_cfo_ratio":sum(x>0 for x in cfo)/len(cfo) if cfo else None,
        "positive_fcf_ratio":sum(x>0 for x in fcf)/len(fcf) if fcf else None,
        "latest_debt":latest.get("debt"),
        "latest_cash":latest.get("cash"),
    }


def normalize_ownership(raw: Dict[str, Any]):
    """Best-effort ownership extraction. Missing fields stay null, never invented."""
    result={"promoter_pct":None,"fii_pct":None,"dii_pct":None,"mutual_fund_pct":None,"pledge_pct":None,"history":[],"raw":raw}
    major=raw.get("major_holders",[]) or []
    for row in major:
        text=" ".join(str(v) for v in row.values()).lower()
        nums=[_num(v) for v in row.values()]
        nums=[x for x in nums if x is not None]
        if "insider" in text and nums:
            result["promoter_pct"]=nums[0]*100 if nums[0] <= 1 else nums[0]
        if "institution" in text and nums:
            result["dii_pct"]=nums[0]*100 if nums[0] <= 1 else nums[0]
    mf=raw.get("mutualfund_holders",[]) or []
    if mf:
        vals=[]
        for r in mf:
            for k,v in r.items():
                if "pct" in str(k).lower() or "percent" in str(k).lower():
                    x=_num(v)
                    if x is not None: vals.append(x*100 if x<=1 else x)
        if vals: result["mutual_fund_pct"]=sum(vals)
    return result
