"""Indian event/ownership enrichment for 360CR.

Collects explicit promoter-pledge, insider, bulk/block-deal and corporate-action
signals where a provider exposes them. The module is deliberately defensive:
failed endpoints return warnings and never fabricate values.

Primary transport is NSE public JSON endpoints. NSE frequently changes endpoint
shape and applies anti-bot controls, so every parser is best-effort and isolated.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any, Dict, Iterable, List, Optional
import re
import time

try:
    import requests
except Exception:  # pragma: no cover
    requests = None


class IndiaEventsError(RuntimeError):
    pass


def _num(v):
    try:
        if v is None:
            return None
        s=str(v).strip().replace(",","").replace("%","")
        if s in {"","-","--","nan","None"}:return None
        return float(s)
    except Exception:
        return None


def _txt(v):
    return "" if v is None else str(v).strip()


def _lower_record(r: Dict[str,Any]):
    return {str(k).lower():v for k,v in (r or {}).items()}


def _pick(r: Dict[str,Any], *names):
    d=_lower_record(r)
    for n in names:
        if n.lower() in d:return d[n.lower()]
    for k,v in d.items():
        if any(n.lower() in k for n in names):return v
    return None


def _materiality_from_value(value: Optional[float], market_cap: Optional[float]=None):
    if value is None:return 1.0
    if market_cap and market_cap>0:
        pct=abs(value)/market_cap*100
        return max(0.5,min(3.0,0.5+pct*4))
    return 1.0


@dataclass
class NSEConfig:
    base_url: str = "https://www.nseindia.com"
    timeout: float = 12.0
    sleep_seconds: float = 0.18
    user_agent: str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"


class NSEIndiaEventProvider:
    """Best-effort NSE public-data event provider.

    Nothing in this class is treated as present unless the response explicitly
    contains the requested company/symbol information.
    """

    def __init__(self, config: NSEConfig|None=None):
        if requests is None:
            raise IndiaEventsError("requests is not installed")
        self.cfg=config or NSEConfig()
        self.session=requests.Session()
        self.session.headers.update({
            "User-Agent":self.cfg.user_agent,
            "Accept":"application/json,text/plain,*/*",
            "Accept-Language":"en-US,en;q=0.9",
            "Referer":self.cfg.base_url+"/",
        })
        self._primed=False

    def _prime(self):
        if self._primed:return
        try:
            self.session.get(self.cfg.base_url+"/",timeout=self.cfg.timeout)
        finally:
            self._primed=True

    def _get(self,path:str,params:Dict[str,Any]|None=None):
        self._prime(); time.sleep(self.cfg.sleep_seconds)
        url=path if path.startswith("http") else self.cfg.base_url+path
        r=self.session.get(url,params=params or {},timeout=self.cfg.timeout)
        if r.status_code!=200:
            raise IndiaEventsError(f"NSE HTTP {r.status_code} for {path}")
        ctype=(r.headers.get("content-type") or "").lower()
        if "json" not in ctype:
            # NSE sometimes returns JSON with generic content type.
            text=r.text.lstrip()
            if not text.startswith(("{","[")):
                raise IndiaEventsError(f"non-JSON NSE response for {path}")
        return r.json()

    @staticmethod
    def _records(obj:Any)->List[Dict[str,Any]]:
        if isinstance(obj,list):return [x for x in obj if isinstance(x,dict)]
        if not isinstance(obj,dict):return []
        for key in ("data","records","rows","result","results"):
            v=obj.get(key)
            if isinstance(v,list):return [x for x in v if isinstance(x,dict)]
            if isinstance(v,dict):
                for k2 in ("data","records","rows"):
                    vv=v.get(k2)
                    if isinstance(vv,list):return [x for x in vv if isinstance(x,dict)]
        return []

    @staticmethod
    def _matches_symbol(r:Dict[str,Any],symbol:str)->bool:
        symbol=symbol.upper().strip()
        vals=[_txt(_pick(r,"symbol","sm_symbol","symbolname","companysymbol","issuer"))]
        return any(v.upper().strip()==symbol for v in vals if v)

    def corporate_actions(self,symbol:str,lookback_days:int=730)->Dict[str,Any]:
        warnings=[]; rows=[]
        # Endpoint currently used by NSE corporate-actions page; parser is defensive.
        candidates=[
            ("/api/corporates-corporateActions",{"index":"equities","symbol":symbol}),
            ("/api/corporate-actions",{"symbol":symbol}),
        ]
        for path,params in candidates:
            try:
                recs=self._records(self._get(path,params))
                if recs:
                    rows=[r for r in recs if self._matches_symbol(r,symbol) or not _pick(r,"symbol","sm_symbol")]
                    if rows:break
            except Exception as e:warnings.append(str(e))
        events=[]
        for r in rows:
            text=" ".join(_txt(v) for v in r.values()).lower()
            typ="corporate_action"
            if "dividend" in text:typ="dividend"
            elif "bonus" in text:typ="bonus"
            elif "split" in text or "sub-division" in text:typ="stock_split"
            elif "buyback" in text:typ="buyback"
            elif "rights" in text:typ="rights_issue"
            events.append({"type":typ,"materiality":1.0,"source":"NSE","raw":r})
        return {"events":events,"raw":rows,"warnings":warnings}

    def insider_trading(self,symbol:str,lookback_days:int=730)->Dict[str,Any]:
        warnings=[]; rows=[]
        candidates=[
            ("/api/corporates-pit",{"index":"equities","symbol":symbol}),
            ("/api/corporate-insider-trading",{"symbol":symbol}),
        ]
        for path,params in candidates:
            try:
                recs=self._records(self._get(path,params))
                if recs:
                    rows=[r for r in recs if self._matches_symbol(r,symbol) or not _pick(r,"symbol","sm_symbol")]
                    if rows:break
            except Exception as e:warnings.append(str(e))
        events=[]
        for r in rows:
            text=" ".join(_txt(v) for v in r.values()).lower()
            acq=any(x in text for x in ("acquisition","acquire","buy","purchase"))
            disp=any(x in text for x in ("disposal","dispose","sell","sale"))
            if acq and not disp:typ="insider_buy"
            elif disp and not acq:typ="insider_sell"
            else:typ="insider_transaction"
            qty=_num(_pick(r,"quantity","qty","securities acquired","securities disposed"))
            price=_num(_pick(r,"price","average price","value per security"))
            value=(qty*price if qty is not None and price is not None else _num(_pick(r,"value","transaction value")))
            events.append({"type":typ,"materiality":_materiality_from_value(value),"value":value,"source":"NSE","raw":r})
        return {"events":events,"raw":rows,"warnings":warnings}

    def bulk_block_deals(self,symbol:str,lookback_days:int=365)->Dict[str,Any]:
        warnings=[]; rows=[]
        candidates=[
            ("/api/snapshot-capital-market-largedeal",{}),
            ("/api/historical/bulk-deals",{"symbol":symbol}),
            ("/api/historical/block-deals",{"symbol":symbol}),
        ]
        for path,params in candidates:
            try:
                recs=self._records(self._get(path,params))
                rows.extend([r for r in recs if self._matches_symbol(r,symbol)])
            except Exception as e:warnings.append(str(e))
        # Deduplicate using stable text representation.
        uniq=[]; seen=set()
        for r in rows:
            key=repr(sorted((str(k),str(v)) for k,v in r.items()))
            if key not in seen:seen.add(key);uniq.append(r)
        events=[]
        for r in uniq:
            text=" ".join(_txt(v) for v in r.values()).lower()
            side="sell" if any(x in text for x in ("sell","seller","sale")) else "buy" if any(x in text for x in ("buy","buyer","purchase")) else "unknown"
            deal_kind="block" if "block" in text else "bulk" if "bulk" in text else "large"
            typ=f"{deal_kind}_{side}" if side!="unknown" else f"{deal_kind}_deal"
            qty=_num(_pick(r,"quantity","qty","traded quantity"));price=_num(_pick(r,"price","trade price"))
            value=(qty*price if qty is not None and price is not None else _num(_pick(r,"value","turnover")))
            events.append({"type":typ,"direction":side,"materiality":_materiality_from_value(value),"value":value,"source":"NSE","raw":r})
        return {"events":events,"raw":uniq,"warnings":warnings}

    def promoter_pledge(self,symbol:str)->Dict[str,Any]:
        warnings=[]; rows=[]
        candidates=[
            ("/api/corporates-pledgedata",{"index":"equities","symbol":symbol}),
            ("/api/corporate-pledge",{"symbol":symbol}),
        ]
        for path,params in candidates:
            try:
                recs=self._records(self._get(path,params))
                if recs:
                    rows=[r for r in recs if self._matches_symbol(r,symbol) or not _pick(r,"symbol","sm_symbol")]
                    if rows:break
            except Exception as e:warnings.append(str(e))
        history=[];events=[]
        for r in rows:
            pct=_num(_pick(r,"pledge percentage","pledged percentage","% pledged","percentage of shares pledged","pledge"))
            period=_txt(_pick(r,"date","period","quarter","as on date","as_on")) or None
            history.append({"period":period,"pledge_pct":pct,"raw":r})
        # Compare consecutive explicit pledge observations only.
        vals=[x for x in history if x.get("pledge_pct") is not None]
        for a,b in zip(vals,vals[1:]):
            delta=b["pledge_pct"]-a["pledge_pct"]
            if abs(delta)>=0.10:
                events.append({"type":"pledge_increase" if delta>0 else "pledge_release","materiality":min(3.0,max(0.5,abs(delta)/5)),"delta_pct":delta,"source":"NSE"})
        return {"history":history,"events":events,"raw":rows,"warnings":warnings}

    def collect(self,symbol:str)->Dict[str,Any]:
        symbol=symbol.strip().upper()
        out={"symbol":symbol,"events":[],"pledge_history":[],"warnings":[],"sources":[]}
        collectors=[("corporate_actions",self.corporate_actions),("insider",self.insider_trading),("deals",self.bulk_block_deals),("pledge",self.promoter_pledge)]
        for name,fn in collectors:
            try:
                r=fn(symbol)
                out[name]=r
                out["events"].extend(r.get("events",[]))
                out["warnings"].extend(r.get("warnings",[]))
                if name=="pledge":out["pledge_history"]=r.get("history",[])
                if r.get("raw"):out["sources"].append(name)
            except Exception as e:
                out["warnings"].append(f"{name}: {e}")
        return out


class CompositeIndiaEventProvider:
    """Chain providers without inventing or silently overwriting evidence."""
    def __init__(self,providers:Iterable[Any]|None=None):
        self.providers=list(providers or [NSEIndiaEventProvider()])

    def collect(self,symbol:str)->Dict[str,Any]:
        merged={"symbol":symbol.upper(),"events":[],"pledge_history":[],"warnings":[],"sources":[]}
        for p in self.providers:
            try:
                r=p.collect(symbol)
                merged["events"].extend(r.get("events",[]))
                if r.get("pledge_history") and not merged["pledge_history"]:
                    merged["pledge_history"]=r["pledge_history"]
                merged["warnings"].extend(r.get("warnings",[]))
                merged["sources"].extend(r.get("sources",[]))
            except Exception as e:
                merged["warnings"].append(f"{p.__class__.__name__}: {e}")
        # Deduplicate normalized events.
        dedup=[];seen=set()
        for e in merged["events"]:
            raw=e.get("raw",{})
            key=(e.get("type"),e.get("direction"),e.get("value"),repr(sorted((str(k),str(v)) for k,v in raw.items())) if isinstance(raw,dict) else str(raw))
            if key not in seen:seen.add(key);dedup.append(e)
        merged["events"]=dedup
        merged["sources"]=sorted(set(merged["sources"]))
        return merged
