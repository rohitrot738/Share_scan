from __future__ import annotations
import re, math
from datetime import date,timedelta
from bs4 import BeautifulSoup
from .models import ResearchInput
from .collector import collect_market_and_company, merge_regulatory
from .nse_public_adapter import NSEPublicAdapter


def _num(v):
    if v is None:return None
    try:
        s=str(v).replace(',','').replace('%','').replace('₹','').strip()
        if s in {'','-','--','NA','N/A'}:return None
        x=float(s);return x if math.isfinite(x) else None
    except:return None

def _local(tag):
    n=getattr(tag,'name','') or ''
    return n.split(':')[-1].split('}')[-1].lower().replace('_','').replace('-','')

def _extract_by_alias(soup, aliases):
    aliases=[a.lower().replace('_','').replace('-','') for a in aliases]
    for tag in soup.find_all(True):
        n=_local(tag)
        if any(a in n for a in aliases):
            v=_num(tag.get_text(' ',strip=True))
            if v is not None:return v
    return None

class NSEDeepPublicCollector(NSEPublicAdapter):
    """Standalone candidate collector. Not wired into scanner.
    Uses official NSE filing metadata + linked XBRL/inline-XBRL documents.
    Missing fields stay None/empty; no fabrication.
    """
    def _absolute(self,u):
        if not u:return None
        u=str(u).strip()
        if u.startswith('http'):return u
        if u.startswith('/'):return 'https://www.nseindia.com'+u
        return u
    def _fetch_doc(self,url):
        if not url:return None
        url=self._absolute(url)
        r=self.s.get(url,timeout=self.timeout,headers={**self.s.headers,'Accept':'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'})
        r.raise_for_status();return r.text
    def financial_filings(self,symbol,years=6):
        end=date.today();start=end-timedelta(days=365*years)
        p={'index':'equities','period':'Quarterly','symbol':symbol,'from_date':start.strftime('%d-%m-%Y'),'to_date':end.strftime('%d-%m-%Y')}
        rows=self._rows(self._get('/api/corporates-financial-results',p))
        if not rows:
            # NSE often returns history when only symbol+period is supplied.
            rows=self._rows(self._get('/api/corporates-financial-results',{'index':'equities','period':'Quarterly','symbol':symbol}))
        rows=[r for r in rows if str(self._pick(r,'symbol','symbolName') or symbol).upper()==symbol.upper()]
        return rows
    def financial_quarters(self,symbol,limit=20):
        out=[]
        for r in self.financial_filings(symbol):
            url=self._pick(r,'xbrl','xbrlFile','xbrlLink','xbrlurl','xbrlUrl')
            if not url:continue
            try:
                html=self._fetch_doc(url);soup=BeautifulSoup(html,'lxml-xml')
                if not soup.find():soup=BeautifulSoup(html,'lxml')
                q={
                    'period':self._pick(r,'toDate','periodEnded','period','relatingTo'),
                    'revenue':_extract_by_alias(soup,['RevenueFromOperations','TotalRevenue','TotalIncome']),
                    'net_profit':_extract_by_alias(soup,['NetProfitLossForThePeriod','ProfitLossForPeriod','NetProfit']),
                    'ebitda':_extract_by_alias(soup,['EBITDA','EarningsBeforeInterestTaxDepreciationAmortisation']),
                    'eps':_extract_by_alias(soup,['BasicEarningsLossPerShare','DilutedEarningsLossPerShare','EarningsPerShare']),
                    'source_url':self._absolute(url),
                }
                if any(q[k] is not None for k in ('revenue','net_profit','ebitda','eps')):out.append(q)
            except Exception:continue
        # de-duplicate by period, prefer records with more populated fields
        best={}
        for q in out:
            key=str(q.get('period') or '')
            filled=sum(q.get(k) is not None for k in ('revenue','net_profit','ebitda','eps'))
            if key not in best or filled>best[key][0]:best[key]=(filled,q)
        vals=[v[1] for v in best.values()];vals.sort(key=lambda x:str(x.get('period') or ''))
        return vals[-limit:]
    def shareholding_deep(self,symbol,limit=20):
        rows=self._rows(self._get('/api/corporate-share-holdings-master',{'index':'equities','symbol':symbol}))
        out=[]
        for r in rows:
            url=self._pick(r,'xbrl','xbrlFile','xbrlLink','xbrlurl','xbrlUrl')
            period=self._pick(r,'asOnDate','date','quarterEndDate','toDate')
            if not url:continue
            try:
                html=self._fetch_doc(url);soup=BeautifulSoup(html,'lxml-xml')
                if not soup.find():soup=BeautifulSoup(html,'lxml')
                q={
                    'period':period,
                    'promoter':_extract_by_alias(soup,['PromoterAndPromoterGroup','PromoterHolding','PromoterShareholding']),
                    'fii_fpi':_extract_by_alias(soup,['ForeignPortfolioInvestors','FII','FPI']),
                    'dii':_extract_by_alias(soup,['DomesticInstitutionalInvestors','DII']),
                    'mutual_fund':_extract_by_alias(soup,['MutualFunds','MutualFund']),
                    'promoter_pledge':_extract_by_alias(soup,['Pledged','EncumberedShares','PromoterPledge']),
                    'source_url':self._absolute(url),
                }
                if any(q[k] is not None for k in ('promoter','fii_fpi','dii','mutual_fund','promoter_pledge')):out.append(q)
            except Exception:continue
        out.sort(key=lambda x:str(x.get('period') or ''));return out[-limit:]
    def bulk_block_fixed(self,symbol):
        snap=self._get('/api/snapshot-capital-market-largedeal')
        rows=[]
        if isinstance(snap,dict):
            rows+=(snap.get('BULK_DEALS_DATA') or [])+(snap.get('BLOCK_DEALS_DATA') or [])
        out=[]
        for r in rows:
            if str(self._pick(r,'symbol') or '').upper()!=symbol.upper():continue
            side=str(self._pick(r,'buySell','side') or '').lower();side='buy' if side.startswith('b') else 'sell' if side.startswith('s') else side
            qty=self._num(self._pick(r,'quantity','qty'));price=self._num(self._pick(r,'price','tradePrice'));value=self._num(self._pick(r,'value','tradedValue'))
            if value is None and qty is not None and price is not None:value=qty*price
            out.append({'date':self._pick(r,'date','tradeDate'),'client':self._pick(r,'clientName','name'),'side':side,'quantity':qty,'price':price,'value':value,'raw':r})
        return out

def collect_candidate_360cr(symbol:str):
    base=collect_market_and_company(symbol);a=NSEDeepPublicCollector(timeout=20,retries=2,sleep=.4)
    errors={}
    def safe(name,fn,default):
        try:return fn()
        except Exception as e:errors[name]=f'{type(e).__name__}: {e}';return default
    fq=safe('financial_quarters',lambda:a.financial_quarters(symbol),[])
    sh=safe('shareholding',lambda:a.shareholding_deep(symbol),[])
    ins=safe('insiders',lambda:a.insiders(symbol),[])
    deals=safe('bulk_block',lambda:a.bulk_block_fixed(symbol),[])
    actions=safe('corporate_actions',lambda:a.corporate_actions(symbol),[])
    if len(fq)>len(base.quarterly_financials):base.quarterly_financials=fq
    merge_regulatory(base,shareholding=sh,insiders=ins,deals=deals,actions=actions,source='NSE_DEEP_PUBLIC_CANDIDATE')
    base.metadata['candidate_errors']=errors
    base.metadata['candidate_counts']={'financial_quarters':len(base.quarterly_financials),'balance_sheet_quarters':len(base.balance_sheet_quarters),'cashflow_quarters':len(base.cashflow_quarters),'shareholding_quarters':len(base.shareholding_quarters),'insider_transactions':len(base.insider_transactions),'bulk_block_deals':len(base.bulk_block_deals),'corporate_actions':len(base.corporate_actions),'price_records':len(base.price_history)}
    return base
