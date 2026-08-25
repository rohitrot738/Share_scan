from __future__ import annotations
import csv, io, json, time
from datetime import date, timedelta
from typing import Any
import requests

BASE="https://www.nseindia.com"
ARCH="https://archives.nseindia.com"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"

class NSEPublicAdapter:
    """Best-effort adapter over NSE's public website endpoints.

    It deliberately returns missing datasets instead of fabricating values. NSE may
    change public endpoint shapes; parsers accept common aliases and preserve raw rows.
    """
    def __init__(self, timeout=15, retries=3, sleep=.6, session=None):
        self.timeout=timeout; self.retries=retries; self.sleep=sleep; self.s=session or requests.Session()
        self.s.headers.update({"User-Agent":UA,"Accept":"application/json,text/plain,*/*","Accept-Language":"en-US,en;q=0.9","Referer":BASE+"/"})
        self._primed=False
    def _prime(self):
        if not self._primed:
            r=self.s.get(BASE+"/",timeout=self.timeout); r.raise_for_status(); self._primed=True
    def _get(self,path,params=None,accept_json=True):
        last=None
        for i in range(self.retries):
            try:
                self._prime(); r=self.s.get(BASE+path,params=params,timeout=self.timeout)
                if r.status_code in (401,403): self._primed=False; self._prime(); r=self.s.get(BASE+path,params=params,timeout=self.timeout)
                r.raise_for_status()
                if accept_json:
                    try:return r.json()
                    except ValueError:return {"_text":r.text}
                return r.text
            except Exception as e:
                last=e; time.sleep(self.sleep*(i+1))
        raise RuntimeError(f"NSE request failed {path}: {last}")
    @staticmethod
    def _rows(obj):
        if isinstance(obj,list):return obj
        if not isinstance(obj,dict):return []
        for k in ("data","rows","records","result","results"):
            v=obj.get(k)
            if isinstance(v,list):return v
            if isinstance(v,dict):
                for kk in ("data","rows"):
                    if isinstance(v.get(kk),list):return v[kk]
        return []
    @staticmethod
    def _pick(r,*keys):
        low={str(k).lower().replace(" ","").replace("_",""):v for k,v in r.items()}
        for k in keys:
            kk=k.lower().replace(" ","").replace("_","")
            if kk in low and low[kk] not in (None,""):return low[kk]
        return None
    @staticmethod
    def _num(v):
        try:return float(str(v).replace(",","").replace("%",""))
        except:return None
    def shareholding(self,symbol,quarters=20):
        # NSE corporate-filings public endpoint; custom horizon requests five years.
        end=date.today(); start=end-timedelta(days=365*6)
        candidates=[
            ("/api/corporate-share-holdings-master",{"index":"equities","symbol":symbol,"from_date":start.strftime("%d-%m-%Y"),"to_date":end.strftime("%d-%m-%Y")}),
            ("/api/corporate-share-holdings",{"symbol":symbol,"from_date":start.strftime("%d-%m-%Y"),"to_date":end.strftime("%d-%m-%Y")}),
        ]
        rows=[]
        for path,p in candidates:
            try:
                rows=self._rows(self._get(path,p))
                if rows:break
            except Exception:continue
        out=[]
        for r in rows:
            if str(self._pick(r,"symbol","nseSymbol") or symbol).upper()!=symbol.upper():continue
            out.append({"period":self._pick(r,"asOnDate","date","quarterEndDate"),"promoter":self._num(self._pick(r,"promoterAndPromoterGroup","promoter","promoterHolding")),"public":self._num(self._pick(r,"public","publicHolding")),"fii_fpi":self._num(self._pick(r,"fii","fpi","fiiFpi","foreignPortfolioInvestors")),"dii":self._num(self._pick(r,"dii","domesticInstitutionalInvestors")),"mutual_fund":self._num(self._pick(r,"mutualFunds","mutualFund","mf")),"promoter_pledge":self._num(self._pick(r,"promoterPledge","pledgedSharesPercent")),"raw":r})
        out.sort(key=lambda x:str(x.get("period") or "")); return out[-quarters:]
    def insiders(self,symbol,years=5):
        end=date.today(); start=end-timedelta(days=365*years)
        rows=[]
        for path in ("/api/corporates-pit", "/api/corporate-insider-trading"):
            try:
                rows=self._rows(self._get(path,{"index":"equities","symbol":symbol,"from_date":start.strftime("%d-%m-%Y"),"to_date":end.strftime("%d-%m-%Y")}))
                if rows:break
            except Exception:continue
        out=[]
        for r in rows:
            side=str(self._pick(r,"acquisitionMode","transactionType","buySell","mode") or "").lower(); normalized="buy" if any(x in side for x in ("buy","purchase","acqui")) else "sell" if any(x in side for x in ("sell","sale","dispose")) else side
            qty=self._num(self._pick(r,"securitiesTransacted","quantity","noOfSecurities")); price=self._num(self._pick(r,"price","averagePrice")); value=self._num(self._pick(r,"value","transactionValue"))
            if value is None and qty is not None and price is not None:value=qty*price
            out.append({"date":self._pick(r,"dateOfAllotmentAdviceAcquisition","transactionDate","date"),"name":self._pick(r,"nameOfPerson","acquirerName","name"),"category":self._pick(r,"categoryOfPerson","personCategory"),"side":normalized,"quantity":qty,"price":price,"value":value,"raw":r})
        return out
    def bulk_block(self,symbol,years=5):
        end=date.today(); start=end-timedelta(days=365*years); rows=[]
        for path in ("/api/historical/bulk-deals", "/api/historical/block-deals"):
            try:
                got=self._rows(self._get(path,{"symbol":symbol,"from":start.strftime("%d-%m-%Y"),"to":end.strftime("%d-%m-%Y")})); rows.extend(got)
            except Exception:continue
        out=[]
        for r in rows:
            sym=str(self._pick(r,"symbol","SYMBOL") or "")
            if sym and sym.upper()!=symbol.upper():continue
            side=str(self._pick(r,"buySell","transactionType","side") or "").lower(); side="buy" if side.startswith("b") else "sell" if side.startswith("s") else side
            qty=self._num(self._pick(r,"quantity","qty","tradedQuantity")); price=self._num(self._pick(r,"price","tradePrice")); value=self._num(self._pick(r,"value","tradedValue"))
            if value is None and qty is not None and price is not None:value=qty*price
            out.append({"date":self._pick(r,"date","tradeDate"),"client":self._pick(r,"clientName","name"),"side":side,"quantity":qty,"price":price,"value":value,"raw":r})
        return out
    def corporate_actions(self,symbol,years=5):
        end=date.today(); start=end-timedelta(days=365*years); rows=[]
        for path in ("/api/corporates-corporateActions", "/api/corporate-actions"):
            try:
                rows=self._rows(self._get(path,{"index":"equities","symbol":symbol,"from_date":start.strftime("%d-%m-%Y"),"to_date":end.strftime("%d-%m-%Y")}))
                if rows:break
            except Exception:continue
        return [{"date":self._pick(r,"exDate","date","recordDate"),"type":self._pick(r,"purpose","subject","action"),"record_date":self._pick(r,"recordDate"),"raw":r} for r in rows]
    def __call__(self,symbol):
        symbol=symbol.replace(".NS","").upper(); errors={}
        def safe(name,fn):
            try:return fn()
            except Exception as e:errors[name]=str(e);return []
        sh=safe("shareholding",lambda:self.shareholding(symbol)); ins=safe("insiders",lambda:self.insiders(symbol)); deals=safe("bulk_block",lambda:self.bulk_block(symbol)); actions=safe("corporate_actions",lambda:self.corporate_actions(symbol))
        return {"shareholding_quarters":sh,"insider_transactions":ins,"bulk_block_deals":deals,"corporate_actions":actions,"source":"NSE_PUBLIC","adapter_errors":errors,"coverage":{"shareholding_quarters":len(sh),"insider_transactions":len(ins),"bulk_block_deals":len(deals),"corporate_actions":len(actions)}}

def nse_public_adapter(symbol): return NSEPublicAdapter()(symbol)
