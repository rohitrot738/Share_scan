from __future__ import annotations
import math
from typing import Any
import pandas as pd
import yfinance as yf
from .models import ResearchInput
from .persistent_cache import PersistentResearchCache

# Collector policy: never fabricate unavailable regulatory data. Public market/company
# data is collected where available; NSE regulatory datasets can be injected by an
# adapter or JSON cache after download from official filings/reports.

def _n(v):
    try:
        x=float(v); return x if math.isfinite(x) else None
    except:return None

def _records(df:pd.DataFrame, mapping:dict[str,list[str]], limit=20):
    if df is None or df.empty:return []
    out=[]
    # yfinance statements normally have line-items as index and periods as columns.
    for col in list(df.columns)[::-1][-limit:]:
        row={"period":str(col)}
        for dest,names in mapping.items():
            val=None
            for name in names:
                if name in df.index:
                    val=_n(df.loc[name,col]);break
            row[dest]=val
        out.append(row)
    return out[-limit:]

def _price_records(df):
    if df is None or df.empty:return []
    rows=[]
    for idx,r in df.iterrows():
        rows.append({"date":str(idx.date() if hasattr(idx,"date") else idx),"interval":"1d","open":_n(r.get("Open")),"high":_n(r.get("High")),"low":_n(r.get("Low")),"close":_n(r.get("Close")),"volume":_n(r.get("Volume"))})
    return rows

def collect_market_and_company(symbol:str)->ResearchInput:
    ticker=symbol if symbol.endswith(".NS") else symbol+".NS"; t=yf.Ticker(ticker)
    hist=t.history(period="5y",interval="1d",auto_adjust=False)
    price=_n(hist["Close"].dropna().iloc[-1]) if hist is not None and not hist.empty else None
    inc=_records(t.quarterly_income_stmt,{"revenue":["Total Revenue","Operating Revenue"],"net_profit":["Net Income","Net Income Common Stockholders"],"ebitda":["EBITDA","Normalized EBITDA"],"eps":["Diluted EPS","Basic EPS"]})
    bs=_records(t.quarterly_balance_sheet,{"total_debt":["Total Debt"],"equity":["Stockholders Equity","Total Equity Gross Minority Interest"],"cash":["Cash Cash Equivalents And Short Term Investments","Cash And Cash Equivalents"]})
    cf=_records(t.quarterly_cash_flow,{"operating_cash_flow":["Operating Cash Flow","Total Cash From Operating Activities"],"free_cash_flow":["Free Cash Flow"]})
    info={}
    try:info=t.info or {}
    except:pass
    valuation={}
    # These are evidence inputs, not invented DCF values. Only populate when source exposes a target/fair-value proxy.
    target=_n(info.get("targetMeanPrice")); book=_n(info.get("bookValue")); eps=_n(info.get("trailingEps")); pe=_n(info.get("forwardPE") or info.get("trailingPE"))
    if target and target>0:valuation["analyst_fair_value"]=target
    if book and book>0:
        pb=_n(info.get("priceToBook")); valuation["book_fair_value"]=book*(pb if pb and pb>0 else 1.0)
    if eps and eps>0 and pe and pe>0:valuation["earnings_fair_value"]=eps*pe
    return ResearchInput(symbol=symbol.replace(".NS",""),price=price,price_history=_price_records(hist),quarterly_financials=inc,balance_sheet_quarters=bs,cashflow_quarters=cf,valuation=valuation,metadata={"market_source":"yfinance","regulatory_source":"not_loaded"})

def merge_regulatory(base:ResearchInput, *, shareholding=None, insiders=None, deals=None, actions=None, valuation=None, source="NSE"):
    if shareholding is not None:base.shareholding_quarters=list(shareholding)[-20:]
    if insiders is not None:base.insider_transactions=list(insiders)
    if deals is not None:base.bulk_block_deals=list(deals)
    if actions is not None:base.corporate_actions=list(actions)
    if valuation:base.valuation.update({k:v for k,v in valuation.items() if _n(v) is not None})
    base.metadata["regulatory_source"]=source
    return base

def collect_360cr(symbol:str, regulatory_adapter=None, cache:PersistentResearchCache|None=None, force_refresh:bool=False)->ResearchInput:
    cache = cache or PersistentResearchCache()
    cached, states = cache.load_research(symbol, allow_stale=True)
    market_fresh = cached is not None and all(states.get(x) == "HIT" for x in ("market", "financials"))
    regulatory_fresh = cached is not None and all(states.get(x) == "HIT" for x in ("shareholding", "regulatory"))
    if market_fresh and not force_refresh:
        if regulatory_adapter is None or regulatory_fresh:
            cached.metadata["cache_status"] = "HIT"
            return cached
        try:
            payload=regulatory_adapter(symbol) or {}
            merge_regulatory(cached,shareholding=payload.get("shareholding_quarters"),insiders=payload.get("insider_transactions"),deals=payload.get("bulk_block_deals"),actions=payload.get("corporate_actions"),valuation=payload.get("valuation"),source=payload.get("source","regulatory_adapter"))
            cached.metadata["cache_status"] = "REGULATORY_REFRESHED"
            cache.store_regulatory(cached)
            return cached
        except Exception as exc:
            cached.metadata["cache_status"] = "STALE_FALLBACK"
            cached.metadata["cache_refresh_error"] = f"{type(exc).__name__}: {exc}"
            return cached
    try:
        base=collect_market_and_company(symbol)
        if cached is not None:
            merge_regulatory(base,shareholding=cached.shareholding_quarters,
                insiders=cached.insider_transactions,deals=cached.bulk_block_deals,
                actions=cached.corporate_actions,valuation=cached.valuation,
                source=cached.metadata.get("regulatory_source","cache"))
        if regulatory_adapter is not None and (force_refresh or not regulatory_fresh):
            payload=regulatory_adapter(symbol) or {}
            merge_regulatory(base,shareholding=payload.get("shareholding_quarters"),insiders=payload.get("insider_transactions"),deals=payload.get("bulk_block_deals"),actions=payload.get("corporate_actions"),valuation=payload.get("valuation"),source=payload.get("source","regulatory_adapter"))
        base.metadata["cache_status"] = "REFRESHED" if cached is not None else "MISS_FETCHED"
        cache.store_research(base)
        return base
    except Exception as exc:
        if cached is None:
            raise
        cached.metadata["cache_status"] = "STALE_FALLBACK"
        cached.metadata["cache_refresh_error"] = f"{type(exc).__name__}: {exc}"
        return cached
