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

def _ticker(symbol:str):
    return yf.Ticker(symbol if symbol.endswith(".NS") else symbol+".NS")

def _collect_market(ticker) -> tuple[float|None,list[dict],dict]:
    hist=ticker.history(period="5y",interval="1d",auto_adjust=False)
    price=_n(hist["Close"].dropna().iloc[-1]) if hist is not None and not hist.empty else None
    info={}
    try:info=ticker.info or {}
    except:pass
    valuation={}
    target=_n(info.get("targetMeanPrice")); book=_n(info.get("bookValue")); eps=_n(info.get("trailingEps")); pe=_n(info.get("forwardPE") or info.get("trailingPE"))
    if target and target>0:valuation["analyst_fair_value"]=target
    if book and book>0:
        pb=_n(info.get("priceToBook")); valuation["book_fair_value"]=book*(pb if pb and pb>0 else 1.0)
    if eps and eps>0 and pe and pe>0:valuation["earnings_fair_value"]=eps*pe
    return price,_price_records(hist),valuation

def _collect_financials(ticker) -> tuple[list[dict],list[dict],list[dict]]:
    inc=_records(ticker.quarterly_income_stmt,{"revenue":["Total Revenue","Operating Revenue"],"net_profit":["Net Income","Net Income Common Stockholders"],"ebitda":["EBITDA","Normalized EBITDA"],"eps":["Diluted EPS","Basic EPS"]})
    bs=_records(ticker.quarterly_balance_sheet,{"total_debt":["Total Debt"],"equity":["Stockholders Equity","Total Equity Gross Minority Interest"],"cash":["Cash Cash Equivalents And Short Term Investments","Cash And Cash Equivalents"]})
    cf=_records(ticker.quarterly_cash_flow,{"operating_cash_flow":["Operating Cash Flow","Total Cash From Operating Activities"],"free_cash_flow":["Free Cash Flow"]})
    return inc,bs,cf

def collect_market_and_company(symbol:str)->ResearchInput:
    ticker=_ticker(symbol)
    price,history,valuation=_collect_market(ticker)
    inc,bs,cf=_collect_financials(ticker)
    return ResearchInput(symbol=symbol.replace(".NS",""),price=price,price_history=history,quarterly_financials=inc,balance_sheet_quarters=bs,cashflow_quarters=cf,valuation=valuation,metadata={"market_source":"yfinance","regulatory_source":"not_loaded"})

def _refresh_market(symbol:str) -> ResearchInput:
    price,history,valuation=_collect_market(_ticker(symbol))
    return ResearchInput(symbol=symbol.replace(".NS",""),price=price,price_history=history,valuation=valuation,metadata={"market_source":"yfinance"})

def _refresh_financials(symbol:str) -> ResearchInput:
    inc,bs,cf=_collect_financials(_ticker(symbol))
    return ResearchInput(symbol=symbol.replace(".NS",""),quarterly_financials=inc,balance_sheet_quarters=bs,cashflow_quarters=cf,metadata={})

def merge_regulatory(base:ResearchInput, *, shareholding=None, insiders=None, deals=None, actions=None, valuation=None, source="NSE"):
    if shareholding is not None:base.shareholding_quarters=list(shareholding)[-20:]
    if insiders is not None:base.insider_transactions=list(insiders)
    if deals is not None:base.bulk_block_deals=list(deals)
    if actions is not None:base.corporate_actions=list(actions)
    if valuation:base.valuation.update({k:v for k,v in valuation.items() if _n(v) is not None})
    base.metadata["regulatory_source"]=source
    return base

def _merge_cached(base:ResearchInput, cached:ResearchInput) -> ResearchInput:
    if base.price is None and cached.price is not None:base.price=cached.price
    if not base.price_history:base.price_history=cached.price_history
    if not base.quarterly_financials:base.quarterly_financials=cached.quarterly_financials
    if not base.balance_sheet_quarters:base.balance_sheet_quarters=cached.balance_sheet_quarters
    if not base.cashflow_quarters:base.cashflow_quarters=cached.cashflow_quarters
    if not base.shareholding_quarters:base.shareholding_quarters=cached.shareholding_quarters
    if not base.insider_transactions:base.insider_transactions=cached.insider_transactions
    if not base.bulk_block_deals:base.bulk_block_deals=cached.bulk_block_deals
    if not base.corporate_actions:base.corporate_actions=cached.corporate_actions
    if not base.valuation:base.valuation=dict(cached.valuation)
    base.metadata={**cached.metadata, **base.metadata}
    return base

def _persist_refreshed_sections(cache:PersistentResearchCache, value:ResearchInput, *, market=False, financials=False, regulatory=False):
    sections={}
    if market:
        sections["market"]={"symbol":value.symbol,"price":value.price,"price_history":value.price_history,"valuation":value.valuation}
    if financials:
        sections["financials"]={"quarterly_financials":value.quarterly_financials,"balance_sheet_quarters":value.balance_sheet_quarters,"cashflow_quarters":value.cashflow_quarters}
    if regulatory:
        sections["shareholding"]={"shareholding_quarters":value.shareholding_quarters}
        sections["regulatory"]={"insider_transactions":value.insider_transactions,"bulk_block_deals":value.bulk_block_deals,"corporate_actions":value.corporate_actions}
    if sections:cache.put_many(value.symbol,sections)

def collect_360cr(symbol:str, regulatory_adapter=None, cache:PersistentResearchCache|None=None, force_refresh:bool=False)->ResearchInput:
    cache = cache or PersistentResearchCache()
    cached, states = cache.load_research(symbol, allow_stale=True)
    market_fresh = cached is not None and states.get("market") == "HIT"
    financials_fresh = cached is not None and states.get("financials") == "HIT"
    regulatory_fresh = cached is not None and all(states.get(x) == "HIT" for x in ("shareholding", "regulatory"))
    if cached is not None and not force_refresh:
        if market_fresh and financials_fresh and (regulatory_adapter is None or regulatory_fresh):
            cached.metadata["cache_status"]="HIT"
            return cached
        try:
            base=ResearchInput(symbol=symbol.replace(".NS",""))
            market_refresh=not market_fresh
            financial_refresh=not financials_fresh
            if market_refresh:
                refreshed=_refresh_market(symbol)
                base.price,base.price_history,base.valuation=refreshed.price,refreshed.price_history,refreshed.valuation
            if financial_refresh:
                refreshed=_refresh_financials(symbol)
                base.quarterly_financials=refreshed.quarterly_financials
                base.balance_sheet_quarters=refreshed.balance_sheet_quarters
                base.cashflow_quarters=refreshed.cashflow_quarters
            _merge_cached(base,cached)
            regulatory_refresh=False
            if regulatory_adapter is not None and not regulatory_fresh:
                payload=regulatory_adapter(symbol) or {}
                merge_regulatory(base,shareholding=payload.get("shareholding_quarters"),insiders=payload.get("insider_transactions"),deals=payload.get("bulk_block_deals"),actions=payload.get("corporate_actions"),valuation=payload.get("valuation"),source=payload.get("source","regulatory_adapter"))
                regulatory_refresh=True
            base.metadata["cache_status"]="REGULATORY_REFRESHED" if regulatory_refresh else "SECTION_REFRESHED"
            _persist_refreshed_sections(cache,base,market=market_refresh,financials=financial_refresh,regulatory=regulatory_refresh)
            return base
        except Exception as exc:
            cached.metadata["cache_status"]="STALE_FALLBACK"
            cached.metadata["cache_refresh_error"]=f"{type(exc).__name__}: {exc}"
            return cached
    try:
        base=collect_market_and_company(symbol)
        if cached is not None:_merge_cached(base,cached)
        if regulatory_adapter is not None and (force_refresh or not regulatory_fresh):
            payload=regulatory_adapter(symbol) or {}
            merge_regulatory(base,shareholding=payload.get("shareholding_quarters"),insiders=payload.get("insider_transactions"),deals=payload.get("bulk_block_deals"),actions=payload.get("corporate_actions"),valuation=payload.get("valuation"),source=payload.get("source","regulatory_adapter"))
        base.metadata["cache_status"]="REFRESHED" if cached is not None else "MISS_FETCHED"
        cache.store_research(base)
        return base
    except Exception as exc:
        if cached is None:raise
        cached.metadata["cache_status"]="STALE_FALLBACK"
        cached.metadata["cache_refresh_error"]=f"{type(exc).__name__}: {exc}"
        return cached
