"""End-to-end Share_scan pipeline.

symbol -> automatic data collection -> normalized 360CR -> multi-timeframe
Ghost Trade Pro -> screenshot-trained case fusion -> fused conviction -> entry/stop/targets.

No missing fundamental/ownership/event value is invented. Missing data is
surfaced in `data_quality` and reduces confidence instead of being silently filled.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any, Dict, Mapping, Optional
import math
import pandas as pd

from ghost_pro.data_collector import Auto360Collector
from ghost_pro.cr360_engine import analyse_360cr
from ghost_pro.cr360_fusion import fuse_technical_360cr, decision_explanation
from ghost_pro.ultimate_engine import multi_timeframe
from ghost_pro.timeframe_matrix import fetch_all_timeframes, TIMEFRAME_SPECS
from ghost_pro.case_training_fusion import fuse_with_technical

@dataclass
class FullScanSummary:
    symbol:str; exchange:str; state:str; fused_score:float; technical_score:float; cr360_score:float; confidence:float; false_breakout_risk:float
    entry:Optional[float]; stop:Optional[float]; target1:Optional[float]; target2:Optional[float]; target3:Optional[float]
    risk_pct:Optional[float]; reward1_pct:Optional[float]; reward2_pct:Optional[float]; reward3_pct:Optional[float]
    fair_value_low:Optional[float]; fair_value_mid:Optional[float]; fair_value_high:Optional[float]; margin_of_safety_pct:Optional[float]
    fundamental_bias:str; ownership_bias:str; data_confidence:float

def _num(v,default=None):
    try:
        if v is None:return default
        x=float(v); return default if math.isnan(x) or math.isinf(x) else x
    except Exception:return default

def _pct(a,b):
    a=_num(a); b=_num(b)
    if a is None or b in (None,0):return None
    return (a-b)/abs(b)*100.0

def _quarters_for_cr360(packet:Mapping[str,Any]):
    raw=list(packet.get("quarters") or []); rows=[]
    for r in raw:
        debt=_num(r.get("debt")); cash=_num(r.get("cash")); revenue=_num(r.get("revenue")); pat=_num(r.get("pat"))
        rows.append({"period":r.get("period"),"revenue":revenue,"ebitda":_num(r.get("ebitda")),"ebit":_num(r.get("ebit")),"pat":pat,"eps":_num(r.get("eps")),
        "opm":_num(r.get("opm"),_num(r.get("operating_margin_pct"))),"npm":_num(r.get("npm"),(pat/revenue*100 if revenue not in (None,0) and pat is not None else None)),
        "cfo":_num(r.get("cfo")),"capex":_num(r.get("capex")),"fcf":_num(r.get("fcf")),"debt":debt,"cash":cash,"net_debt":(debt-cash if debt is not None and cash is not None else None),
        "equity":_num(r.get("equity")),"assets":_num(r.get("assets")),"receivables":_num(r.get("receivables")),"inventory":_num(r.get("inventory")),"payables":_num(r.get("payables")),
        "depreciation":_num(r.get("depreciation")),"interest":_num(r.get("interest")),"shares":_num(r.get("shares")),"roce":_num(r.get("roce")),"roe":_num(r.get("roe")),
        "working_capital_days":_num(r.get("working_capital_days")),"debtor_days":_num(r.get("debtor_days"))})
    return list(reversed(rows[-20:]))

def _shareholding_for_cr360(packet:Mapping[str,Any]):
    hist=[dict(r) for r in (packet.get("shareholding_history") or (packet.get("ownership") or {}).get("history") or [])]
    pledge_hist=list(packet.get("pledge_history") or (packet.get("ownership") or {}).get("pledge_history") or [])
    pledge_map={str(x.get("period")):x.get("pledge_pct") for x in pledge_hist if x.get("period") and x.get("pledge_pct") is not None}
    if hist:
        out=[]
        for r in hist[-20:]:
            p=str(r.get("period"))
            pledge=r.get("pledge",r.get("pledge_pct"))
            if pledge is None and p in pledge_map:pledge=pledge_map[p]
            out.append({"period":r.get("period"),"promoter":_num(r.get("promoter",r.get("promoter_pct"))),"fii":_num(r.get("fii",r.get("fii_pct"))),
                 "dii":_num(r.get("dii",r.get("dii_pct"))),"mutual_fund":_num(r.get("mutual_fund",r.get("mutual_fund_pct"))),"public":_num(r.get("public",r.get("public_pct"))),
                 "pledge":_num(pledge),"insider":_num(r.get("insider",r.get("insider_pct")))})
        return out
    own=dict(packet.get("ownership") or {})
    latest_pledge=None
    vals=[x.get("pledge_pct") for x in pledge_hist if _num(x.get("pledge_pct")) is not None]
    if vals:latest_pledge=vals[-1]
    current={"period":"current","promoter":_num(own.get("promoter_pct")),"fii":_num(own.get("fii_pct")),"dii":_num(own.get("dii_pct")),"mutual_fund":_num(own.get("mutual_fund_pct")),"pledge":_num(own.get("pledge_pct"),_num(latest_pledge))}
    return [current] if any(v is not None for k,v in current.items() if k!="period") else []

def _valuation_for_cr360(packet:Mapping[str,Any]):
    m=dict(packet.get("market") or {}); quarters=_quarters_for_cr360(packet); eps=[_num(r.get("eps")) for r in quarters if _num(r.get("eps")) is not None]; eps_ttm=sum(eps[-4:]) if eps else None
    return {"price":_num(m.get("price")),"pe":_num(m.get("trailing_pe")),"pb":_num(m.get("price_to_book")),"ev_ebitda":_num(m.get("ev_to_ebitda")),"eps_ttm":eps_ttm,"sector_pe":None,"historical_median_pe":None,"expected_eps_growth":None}

def _events_for_cr360(packet:Mapping[str,Any]):
    events=[dict(e) for e in (packet.get("events") or [])]
    raw=((packet.get("ownership") or {}).get("raw") or packet.get("raw_holder_snapshot") or {})
    for rec in raw.get("insider_transactions",[]) or []:
        text=" ".join(str(v) for v in rec.values()).lower(); typ="insider_buy" if ("buy" in text or "purchase" in text) else "insider_sell" if ("sell" in text or "sale" in text) else None
        if typ:events.append({"type":typ,"materiality":1.0,"source":"Yahoo","raw":rec})
    dedup=[];seen=set()
    for e in events:
        raw=e.get("raw",{})
        key=(e.get("type"),e.get("direction"),e.get("value"),repr(sorted((str(k),str(v)) for k,v in raw.items())) if isinstance(raw,dict) else str(raw))
        if key not in seen:seen.add(key);dedup.append(e)
    return dedup

def _fetch_technical_frames(collector:Auto360Collector,symbol:str,exchange:str):
    return fetch_all_timeframes(collector,symbol,exchange,min_candles=60)

def _data_confidence(packet:Mapping[str,Any],frames:Mapping[str,pd.DataFrame]):
    q=packet.get("data_quality") or {}; quarter_count=int(q.get("quarter_count") or 0); shq=int(q.get("shareholding_quarter_count") or 0); pledge=int(q.get("pledge_observation_count") or 0); events=int(q.get("event_count") or 0); score=0.0
    score+=min(quarter_count/20.0,1.0)*32.0
    score+=10.0 if q.get("has_market_price") else 0.0
    score+=min(shq/12.0,1.0)*17.0
    score+=min(pledge/4.0,1.0)*6.0
    score+=min(events/5.0,1.0)*4.0
    score+=min(len(frames)/max(len(TIMEFRAME_SPECS),1),1.0)*31.0
    return round(min(100.0,score),2)

def run_full_scan(symbol:str,exchange:str="NSE",force_refresh:bool=False,capital:float=100000.0,risk_pct:float=0.5,collector:Auto360Collector|None=None)->Dict[str,Any]:
    collector=collector or Auto360Collector(); symbol=symbol.strip().upper(); exchange=exchange.strip().upper(); packet=collector.collect(symbol,exchange,force_refresh)
    cr=analyse_360cr(symbol=symbol,quarters=_quarters_for_cr360(packet),shareholding=_shareholding_for_cr360(packet),valuation=_valuation_for_cr360(packet),events=_events_for_cr360(packet))
    frames,tech_warnings=_fetch_technical_frames(collector,symbol,exchange)
    if not frames:return {"symbol":symbol,"exchange":exchange,"status":"PARTIAL","error":"No usable technical frames","cr360":cr,"collector_packet":packet,"technical_warnings":tech_warnings}

    # Base Ghost Trade Pro analysis first, then screenshot-derived labelled cases.
    technical_base=multi_timeframe(frames,symbol=symbol,capital=capital,risk_pct=risk_pct)
    technical=fuse_with_technical(technical_base,frames)
    fused=fuse_technical_360cr(technical,cr)

    entry=_num(fused.get("entry")); stop=_num(fused.get("stop")); t1=_num(fused.get("target1")); t2=_num(fused.get("target2")); t3=_num(fused.get("target3")); crd=cr.get("decision",{})
    confidence=_num(technical.get("confidence"),0.0) or 0.0; data_conf=_data_confidence(packet,frames); overall_conf=round(0.72*confidence+0.28*data_conf,2)
    summary=FullScanSummary(symbol,exchange,str(fused.get("final_state")),float(fused.get("final_fused_score",0)),float(fused.get("technical_score",0)),float(fused.get("cr360_score",0)),overall_conf,float(fused.get("technical_false_breakout_risk",100)),entry,stop,t1,t2,t3,
        abs(_pct(stop,entry)) if entry is not None and stop is not None else None,_pct(t1,entry) if t1 is not None and entry is not None else None,_pct(t2,entry) if t2 is not None and entry is not None else None,_pct(t3,entry) if t3 is not None and entry is not None else None,
        _num(crd.get("fair_value_low")),_num(crd.get("fair_value_mid")),_num(crd.get("fair_value_high")),_num(crd.get("margin_of_safety_pct")),str(crd.get("fundamental_bias","UNKNOWN")),str(crd.get("ownership_bias","UNKNOWN")),data_conf)
    return {"status":"OK","summary":asdict(summary),"explanation":decision_explanation(fused),"fused":fused,"technical":technical,"technical_before_case_training":technical_base,"case_training":technical.get("case_training",{}),"cr360":cr,"events_used":_events_for_cr360(packet),"data_quality":packet.get("data_quality",{}),"technical_warnings":tech_warnings,"frames_used":{k:len(v) for k,v in frames.items()},"requested_timeframes":list(TIMEFRAME_SPECS)}

def compact_report(result:Mapping[str,Any])->str:
    if result.get("status")!="OK":return f"{result.get('symbol')} | PARTIAL | {result.get('error','scan incomplete')}"
    s=result["summary"]; case=result.get("case_training") or {}
    return f"{s['symbol']} | {s['state']} | Fused {s['fused_score']:.1f}/100 | Tech {s['technical_score']:.1f} | 360CR {s['cr360_score']:.1f} | Case {case.get('score','NA')} {case.get('state','')} | Entry {s['entry']} | SL {s['stop']} | T1 {s['target1']} | T2 {s['target2']} | FalseBreak {s['false_breakout_risk']:.1f}% | Data {s['data_confidence']:.1f}%"
