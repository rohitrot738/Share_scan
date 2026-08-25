from __future__ import annotations
import html, math, re, time
from dataclasses import dataclass
from urllib.parse import quote_plus, urlparse, parse_qs, unquote
import requests
from bs4 import BeautifulSoup
from .collector import collect_market_and_company, merge_regulatory
from .candidate_public_collector import _extract_by_alias

UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36'
OFFICIAL_HOSTS=('nsearchives.nseindia.com','archives.nseindia.com','nseindia.com','bseindia.com')

@dataclass
class SearchHit:
    url:str
    title:str=''
    snippet:str=''
    provider:str=''
    score:float=0.0

class PublicWebSearch:
    """Credential-free public web discovery for standalone 360CR testing.
    Uses normal search-result HTML pages, not private/hidden ChatGPT APIs.
    If a provider blocks CI, it falls back to the next provider.
    """
    def __init__(self,timeout=15,session=None):
        self.timeout=timeout; self.s=session or requests.Session(); self.s.headers.update({'User-Agent':UA,'Accept-Language':'en-US,en;q=0.9'})
    @staticmethod
    def _clean_ddg(u):
        if not u:return None
        if u.startswith('//'):u='https:'+u
        if 'duckduckgo.com/l/' in u:
            q=parse_qs(urlparse(u).query).get('uddg')
            if q:return unquote(q[0])
        return u if u.startswith('http') else None
    def duckduckgo(self,q,limit=12):
        r=self.s.get('https://html.duckduckgo.com/html/',params={'q':q},timeout=self.timeout);r.raise_for_status();s=BeautifulSoup(r.text,'lxml');out=[]
        for a in s.select('.result__a'):
            u=self._clean_ddg(a.get('href')); parent=a.find_parent(class_='result'); sn=''
            if parent:
                z=parent.select_one('.result__snippet'); sn=z.get_text(' ',strip=True) if z else ''
            if u:out.append(SearchHit(u,a.get_text(' ',strip=True),sn,'duckduckgo'))
            if len(out)>=limit:break
        return out
    def bing(self,q,limit=12):
        r=self.s.get('https://www.bing.com/search',params={'q':q,'count':limit},timeout=self.timeout);r.raise_for_status();s=BeautifulSoup(r.text,'lxml');out=[]
        for li in s.select('li.b_algo'):
            a=li.select_one('h2 a'); p=li.select_one('.b_caption p')
            if a and str(a.get('href','')).startswith('http'):out.append(SearchHit(a['href'],a.get_text(' ',strip=True),p.get_text(' ',strip=True) if p else '','bing'))
            if len(out)>=limit:break
        return out
    def search(self,q,limit=12):
        errors=[]
        for fn in (self.duckduckgo,self.bing):
            try:
                got=fn(q,limit)
                if got:return got,errors
            except Exception as e:errors.append(f'{fn.__name__}: {type(e).__name__}: {e}')
        return [],errors

class SearchFirst360Collector:
    """Search -> discover -> fetch -> parse -> verify candidate collector.
    It is intentionally standalone and must not be wired into the main scanner until diagnostics pass.
    """
    def __init__(self,timeout=15,max_hits=12):
        self.timeout=timeout;self.max_hits=max_hits;self.searcher=PublicWebSearch(timeout);self.s=self.searcher.s
    def _rank(self,hit:SearchHit,kind:str):
        u=hit.url.lower();t=(hit.title+' '+hit.snippet).lower();score=0
        if any(h in u for h in OFFICIAL_HOSTS):score+=80
        if 'investor' in u or 'shareholder' in u:score+=25
        key={'financial':'financial result quarterly xbrl revenue profit','shareholding':'shareholding promoter fii fpi mutual fund','insider':'insider trading regulation 7(2) acquisition disposal','deal':'bulk deal block deal','action':'corporate action dividend bonus split'}[kind]
        score+=sum(5 for k in key.split() if k in t or k in u)
        if u.endswith('.pdf'):score-=5
        return score
    def discover(self,symbol,kind):
        qs={
          'financial':[f'{symbol} quarterly financial results iXBRL NSE',f'site:nsearchives.nseindia.com {symbol} financial results quarter iXBRL',f'{symbol} investor quarterly results'],
          'shareholding':[f'{symbol} shareholding pattern promoter FII FPI quarter',f'site:nsearchives.nseindia.com {symbol} shareholding pattern xbrl',f'{symbol} investor shareholding pattern'],
          'insider':[f'{symbol} insider trading regulation 7(2) NSE',f'site:nsearchives.nseindia.com {symbol} insider trading 7(2)'],
          'deal':[f'{symbol} bulk deal block deal NSE',f'{symbol} bulk block deals'],
          'action':[f'{symbol} corporate actions dividend bonus split NSE',f'{symbol} investor corporate actions'],
        }[kind]
        allhits=[];errors=[];seen=set()
        for q in qs:
            hits,errs=self.searcher.search(q,self.max_hits);errors+=errs
            for h in hits:
                if h.url in seen:continue
                seen.add(h.url);h.score=self._rank(h,kind);allhits.append(h)
        allhits.sort(key=lambda h:h.score,reverse=True)
        return allhits[:self.max_hits],errors
    def _get(self,url):
        r=self.s.get(url,timeout=self.timeout,allow_redirects=True);r.raise_for_status();return r.text,r.url
    @staticmethod
    def _num(v):
        try:
            s=re.sub(r'[^0-9.\-]','',str(v));return float(s) if s not in ('','-','.') and math.isfinite(float(s)) else None
        except:return None
    def parse_financial_page(self,url):
        text,final=self._get(url);s=BeautifulSoup(text,'lxml-xml')
        if not s.find():s=BeautifulSoup(text,'lxml')
        q={'period':None,'revenue':_extract_by_alias(s,['RevenueFromOperations','TotalRevenue','TotalIncome']), 'net_profit':_extract_by_alias(s,['NetProfitLossForThePeriod','ProfitLossForPeriod','ProfitLoss','NetProfit']), 'ebitda':_extract_by_alias(s,['EBITDA','EarningsBeforeInterestTaxDepreciationAmortisation']), 'eps':_extract_by_alias(s,['BasicEarningsLossPerShare','DilutedEarningsLossPerShare','EarningsPerShare']), 'source_url':final}
        # best-effort period from visible text/title/url
        raw=s.get_text(' ',strip=True)[:12000]
        m=re.search(r'(?:quarter|period)\s+(?:ended|ending)?\s*([0-3]?\d[-/ .][A-Za-z]{3,9}[-/ .]20\d{2}|20\d{2}[-/]\d{2}[-/]\d{2})',raw,re.I)
        if m:q['period']=m.group(1)
        return q if any(q[k] is not None for k in ('revenue','net_profit','ebitda','eps')) else None
    def financials(self,symbol,limit=20):
        hits,errs=self.discover(symbol,'financial');rows=[];sources=[]
        for h in hits:
            try:
                q=self.parse_financial_page(h.url)
                if q:rows.append(q);sources.append({'url':h.url,'provider':h.provider,'score':h.score})
            except Exception as e:errs.append(f'financial fetch {h.url}: {type(e).__name__}: {e}')
        # dedupe by source/period + values; search may return the same quarter through multiple URLs.
        best=[];seen=set()
        for q in rows:
            sig=(q.get('period'),q.get('revenue'),q.get('net_profit'),q.get('eps'))
            if sig in seen:continue
            seen.add(sig);best.append(q)
        return best[-limit:],sources,errs
    def _tables(self,url):
        text,final=self._get(url);s=BeautifulSoup(text,'lxml');out=[]
        for table in s.find_all('table'):
            rows=[]
            for tr in table.find_all('tr'):
                cells=[c.get_text(' ',strip=True) for c in tr.find_all(['th','td'])]
                if cells:rows.append(cells)
            if rows:out.append(rows)
        return out,final
    def shareholding(self,symbol,limit=20):
        hits,errs=self.discover(symbol,'shareholding');out=[];sources=[]
        for h in hits:
            try:
                # First try XBRL-style tags.
                text,final=self._get(h.url);s=BeautifulSoup(text,'lxml-xml')
                if not s.find():s=BeautifulSoup(text,'lxml')
                q={'period':None,'promoter':_extract_by_alias(s,['PromoterAndPromoterGroup','PromoterHolding','PromoterShareholding']), 'fii_fpi':_extract_by_alias(s,['ForeignPortfolioInvestors','FII','FPI']), 'dii':_extract_by_alias(s,['DomesticInstitutionalInvestors','DII']), 'mutual_fund':_extract_by_alias(s,['MutualFunds','MutualFund']), 'promoter_pledge':_extract_by_alias(s,['Pledged','EncumberedShares','PromoterPledge']), 'source_url':final}
                title=s.get_text(' ',strip=True)[:8000];m=re.search(r'(?:as on|quarter ended)\s*([0-3]?\d[-/ .][A-Za-z]{3,9}[-/ .]20\d{2}|20\d{2}[-/]\d{2}[-/]\d{2})',title,re.I)
                if m:q['period']=m.group(1)
                if any(q[k] is not None for k in ('promoter','fii_fpi','dii','mutual_fund','promoter_pledge')):
                    out.append(q);sources.append({'url':h.url,'provider':h.provider,'score':h.score})
            except Exception as e:errs.append(f'shareholding fetch {h.url}: {type(e).__name__}: {e}')
        seen=set();ded=[]
        for q in out:
            sig=(q.get('period'),q.get('promoter'),q.get('fii_fpi'),q.get('mutual_fund'))
            if sig in seen:continue
            seen.add(sig);ded.append(q)
        return ded[-limit:],sources,errs
    def generic_evidence(self,symbol,kind):
        hits,errs=self.discover(symbol,kind);evidence=[]
        for h in hits[:8]: evidence.append({'url':h.url,'title':h.title,'snippet':h.snippet,'provider':h.provider,'score':h.score})
        return evidence,errs

def collect_search_first_360cr(symbol:str):
    base=collect_market_and_company(symbol);c=SearchFirst360Collector(timeout=15,max_hits=15);errors={};evidence={}
    fq,src,err=c.financials(symbol);errors['financial']=err;evidence['financial_sources']=src
    sh,src,err=c.shareholding(symbol);errors['shareholding']=err;evidence['shareholding_sources']=src
    insiders,err=c.generic_evidence(symbol,'insider');errors['insider']=err;evidence['insider_search']=insiders
    deals,err=c.generic_evidence(symbol,'deal');errors['deal']=err;evidence['deal_search']=deals
    actions,err=c.generic_evidence(symbol,'action');errors['action']=err;evidence['action_search']=actions
    if len(fq)>len(base.quarterly_financials):base.quarterly_financials=fq
    if sh:base.shareholding_quarters=sh[-20:]
    # Search evidence is preserved separately; it is not converted into fabricated structured transactions.
    base.metadata.update({'search_first_candidate':True,'search_first_errors':errors,'search_first_evidence':evidence,'search_first_counts':{'financial_quarters':len(base.quarterly_financials),'balance_sheet_quarters':len(base.balance_sheet_quarters),'cashflow_quarters':len(base.cashflow_quarters),'shareholding_quarters':len(base.shareholding_quarters),'insider_search_hits':len(insiders),'deal_search_hits':len(deals),'action_search_hits':len(actions),'price_records':len(base.price_history)}})
    return base
