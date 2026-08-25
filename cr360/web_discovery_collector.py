from __future__ import annotations
import math,re
from dataclasses import dataclass
from datetime import datetime
from urllib.parse import parse_qs,unquote,urlparse
import requests
from bs4 import BeautifulSoup
from .collector import collect_market_and_company
from .candidate_public_collector import _extract_by_alias

UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36'
OFFICIAL_HOSTS=('nsearchives.nseindia.com','archives.nseindia.com','nseindia.com','bseindia.com')
TRUSTED_HOST_HINTS=('moneycontrol.com','screener.in','trendlyne.com','tickertape.in','economictimes.indiatimes.com')

@dataclass
class SearchHit:
    url:str; title:str=''; snippet:str=''; provider:str=''; score:float=0.0

class PublicWebSearch:
    def __init__(self,timeout=15,session=None):
        self.timeout=timeout;self.s=session or requests.Session();self.s.headers.update({'User-Agent':UA,'Accept-Language':'en-US,en;q=0.9'})
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
            u=self._clean_ddg(a.get('href'));parent=a.find_parent(class_='result');sn=''
            if parent:
                z=parent.select_one('.result__snippet');sn=z.get_text(' ',strip=True) if z else ''
            if u:out.append(SearchHit(u,a.get_text(' ',strip=True),sn,'duckduckgo'))
            if len(out)>=limit:break
        return out
    def bing(self,q,limit=12):
        r=self.s.get('https://www.bing.com/search',params={'q':q,'count':limit},timeout=self.timeout);r.raise_for_status();s=BeautifulSoup(r.text,'lxml');out=[]
        for li in s.select('li.b_algo'):
            a=li.select_one('h2 a');p=li.select_one('.b_caption p')
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
    def __init__(self,timeout=15,max_hits=16):
        self.timeout=timeout;self.max_hits=max_hits;self.searcher=PublicWebSearch(timeout);self.s=self.searcher.s
    def _rank(self,h,kind):
        u=h.url.lower();t=(h.title+' '+h.snippet).lower();score=0
        if any(x in u for x in OFFICIAL_HOSTS):score+=100
        elif any(x in u for x in TRUSTED_HOST_HINTS):score+=40
        if 'investor' in u or 'shareholder' in u:score+=25
        keys={'financial':'quarter financial revenue profit eps xbrl','balance':'balance sheet debt equity cash','cashflow':'cash flow operating free cash flow','shareholding':'shareholding promoter fii fpi mutual fund pledge','insider':'insider regulation 7(2) acquisition disposal buy sell','deal':'bulk deal block deal quantity price','action':'corporate action dividend bonus split rights'}[kind]
        score+=sum(6 for k in keys.split() if k in t or k in u)
        return score
    def discover(self,symbol,kind):
        qs={
          'financial':[f'{symbol} quarterly results revenue net profit EPS',f'site:nsearchives.nseindia.com {symbol} iXBRL quarterly financial results',f'{symbol} investor quarterly results'],
          'balance':[f'{symbol} quarterly balance sheet total debt equity cash',f'{symbol} balance sheet quarter investor'],
          'cashflow':[f'{symbol} quarterly cash flow operating cash flow free cash flow',f'{symbol} cash flow quarter investor'],
          'shareholding':[f'{symbol} shareholding pattern promoter FII FPI mutual fund quarter',f'site:nsearchives.nseindia.com {symbol} shareholding xbrl',f'{symbol} investor shareholding pattern'],
          'insider':[f'{symbol} insider trading regulation 7(2) acquisition disposal NSE',f'{symbol} insider buy sell disclosure'],
          'deal':[f'{symbol} bulk deal block deal NSE quantity price',f'{symbol} bulk block deals'],
          'action':[f'{symbol} corporate actions dividend bonus split rights NSE',f'{symbol} investor corporate actions'],
        }[kind]
        seen=set();allhits=[];errors=[]
        for q in qs:
            hits,errs=self.searcher.search(q,self.max_hits);errors+=errs
            for h in hits:
                if h.url in seen:continue
                seen.add(h.url);h.score=self._rank(h,kind);allhits.append(h)
        allhits.sort(key=lambda x:x.score,reverse=True);return allhits[:self.max_hits],errors
    def _get(self,url):
        r=self.s.get(url,timeout=self.timeout,allow_redirects=True);r.raise_for_status();return r.text,r.url
    @staticmethod
    def _num(v):
        try:
            s=str(v).replace(',','').replace('₹','').replace('%','').strip()
            m=re.search(r'-?\d+(?:\.\d+)?',s)
            if not m:return None
            x=float(m.group());return x if math.isfinite(x) else None
        except:return None
    @staticmethod
    def _date(text):
        pats=(r'\b(20\d{2}[-/]\d{1,2}[-/]\d{1,2})\b',r'\b(\d{1,2}[-/. ](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*[-/. ]20\d{2})\b',r'\b((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\s+\d{1,2},?\s+20\d{2})\b')
        for p in pats:
            m=re.search(p,text,re.I)
            if m:return m.group(1)
        return None
    @staticmethod
    def _text_rows(soup):
        rows=[]
        for tr in soup.find_all('tr'):
            c=[x.get_text(' ',strip=True) for x in tr.find_all(['th','td'])]
            if c:rows.append(c)
        return rows
    def _label_value(self,soup,aliases):
        aliases=[a.lower() for a in aliases]
        for row in self._text_rows(soup):
            if not row:continue
            label=row[0].lower()
            if any(a in label for a in aliases):
                for v in reversed(row[1:]):
                    n=self._num(v)
                    if n is not None:return n
        return None
    def _soup(self,url):
        text,final=self._get(url);s=BeautifulSoup(text,'lxml-xml')
        if not s.find():s=BeautifulSoup(text,'lxml')
        return s,final
    def parse_financial_page(self,url):
        s,final=self._soup(url);raw=s.get_text(' ',strip=True)[:30000]
        q={'period':self._date(raw),'revenue':_extract_by_alias(s,['RevenueFromOperations','TotalRevenue','TotalIncome']),'net_profit':_extract_by_alias(s,['NetProfitLossForThePeriod','ProfitLossForPeriod','ProfitLoss','NetProfit']),'ebitda':_extract_by_alias(s,['EBITDA','EarningsBeforeInterestTaxDepreciationAmortisation']),'eps':_extract_by_alias(s,['BasicEarningsLossPerShare','DilutedEarningsLossPerShare','EarningsPerShare']),'source_url':final}
        if q['revenue'] is None:q['revenue']=self._label_value(s,['revenue from operations','total revenue','total income','sales'])
        if q['net_profit'] is None:q['net_profit']=self._label_value(s,['net profit','profit after tax','profit for the period'])
        if q['ebitda'] is None:q['ebitda']=self._label_value(s,['ebitda','operating profit'])
        if q['eps'] is None:q['eps']=self._label_value(s,['earnings per share','eps'])
        return q if any(q[k] is not None for k in ('revenue','net_profit','ebitda','eps')) else None
    def _generic_quarters(self,symbol,kind,fields,limit=20):
        hits,errs=self.discover(symbol,kind);rows=[];src=[]
        for h in hits:
            try:
                s,final=self._soup(h.url);raw=s.get_text(' ',strip=True)[:30000];q={'period':self._date(raw),'source_url':final}
                for key,aliases in fields.items():q[key]=self._label_value(s,aliases)
                if any(q[k] is not None for k in fields):rows.append(q);src.append({'url':final,'provider':h.provider,'score':h.score})
            except Exception as e:errs.append(f'{kind} fetch {h.url}: {type(e).__name__}: {e}')
        return self._dedupe(rows,tuple(fields),limit),src,errs
    @staticmethod
    def _dedupe(rows,keys,limit=20):
        seen=set();out=[]
        for q in rows:
            sig=(q.get('period'),)+tuple(q.get(k) for k in keys)
            if sig in seen:continue
            seen.add(sig);out.append(q)
        return out[-limit:]
    def financials(self,symbol,limit=20):
        hits,errs=self.discover(symbol,'financial');rows=[];src=[]
        for h in hits:
            try:
                q=self.parse_financial_page(h.url)
                if q:rows.append(q);src.append({'url':q['source_url'],'provider':h.provider,'score':h.score})
            except Exception as e:errs.append(f'financial fetch {h.url}: {type(e).__name__}: {e}')
        return self._dedupe(rows,('revenue','net_profit','ebitda','eps'),limit),src,errs
    def balance_sheets(self,symbol,limit=20):
        return self._generic_quarters(symbol,'balance',{'total_debt':['total debt','borrowings','total borrowings'],'equity':['total equity','shareholders funds','shareholder equity'],'cash':['cash and cash equivalents','cash equivalents','cash balance']},limit)
    def cashflows(self,symbol,limit=20):
        return self._generic_quarters(symbol,'cashflow',{'operating_cash_flow':['cash flow from operating activities','net cash from operating activities','operating cash flow'],'free_cash_flow':['free cash flow']},limit)
    def shareholding(self,symbol,limit=20):
        hits,errs=self.discover(symbol,'shareholding');out=[];src=[]
        for h in hits:
            try:
                s,final=self._soup(h.url);raw=s.get_text(' ',strip=True)[:30000];q={'period':self._date(raw),'promoter':_extract_by_alias(s,['PromoterAndPromoterGroup','PromoterHolding','PromoterShareholding']),'fii_fpi':_extract_by_alias(s,['ForeignPortfolioInvestors','FII','FPI']),'dii':_extract_by_alias(s,['DomesticInstitutionalInvestors','DII']),'mutual_fund':_extract_by_alias(s,['MutualFunds','MutualFund']),'promoter_pledge':_extract_by_alias(s,['Pledged','EncumberedShares','PromoterPledge']),'source_url':final}
                fallbacks={'promoter':['promoter & promoter group','promoter holding','promoters'],'fii_fpi':['fii','fpi','foreign portfolio investors'],'dii':['dii','domestic institutional investors'],'mutual_fund':['mutual funds','mutual fund'],'promoter_pledge':['pledged','encumbered']}
                for k,a in fallbacks.items():
                    if q[k] is None:q[k]=self._label_value(s,a)
                if any(q[k] is not None for k in fallbacks):out.append(q);src.append({'url':final,'provider':h.provider,'score':h.score})
            except Exception as e:errs.append(f'shareholding fetch {h.url}: {type(e).__name__}: {e}')
        return self._dedupe(out,('promoter','fii_fpi','dii','mutual_fund','promoter_pledge'),limit),src,errs
    def _structured_events(self,symbol,kind):
        hits,errs=self.discover(symbol,kind);out=[];evidence=[]
        for h in hits[:10]:
            evidence.append({'url':h.url,'title':h.title,'snippet':h.snippet,'provider':h.provider,'score':h.score})
            try:
                s,final=self._soup(h.url);txt=s.get_text(' ',strip=True)[:40000];date=self._date(txt)
                if kind=='insider':
                    side='buy' if re.search(r'\b(acquisition|purchase|bought|buy)\b',txt,re.I) else 'sell' if re.search(r'\b(disposal|sale|sold|sell)\b',txt,re.I) else None
                    val=None
                    m=re.search(r'(?:value|consideration|transaction value)[^\d]{0,30}([₹Rs. ]*[\d,.]+)',txt,re.I)
                    if m:val=self._num(m.group(1))
                    if side:out.append({'date':date,'side':side,'value':val,'source_url':final,'evidence_title':h.title})
                elif kind=='deal':
                    side='buy' if re.search(r'\bbuy\b|\bbought\b',txt,re.I) else 'sell' if re.search(r'\bsell\b|\bsold\b',txt,re.I) else None
                    qty=None;price=None
                    m=re.search(r'(?:qty|quantity|shares)[^\d]{0,20}([\d,]+)',txt,re.I);qty=self._num(m.group(1)) if m else None
                    m=re.search(r'(?:price|rate)[^\d]{0,20}([\d,.]+)',txt,re.I);price=self._num(m.group(1)) if m else None
                    if side or qty or price:out.append({'date':date,'side':side,'quantity':qty,'price':price,'value':qty*price if qty and price else None,'source_url':final})
                else:
                    typ=None
                    for name in ('dividend','bonus','split','rights','buyback'):
                        if re.search(r'\b'+name+r'\b',txt,re.I):typ=name;break
                    if typ:out.append({'date':date,'type':typ,'source_url':final,'evidence_title':h.title})
            except Exception as e:errs.append(f'{kind} fetch {h.url}: {type(e).__name__}: {e}')
        # de-duplicate events by basic signature
        seen=set();ded=[]
        for r in out:
            sig=tuple((k,r.get(k)) for k in sorted(r) if k!='source_url')
            if sig in seen:continue
            seen.add(sig);ded.append(r)
        return ded,evidence,errs

def collect_search_first_360cr(symbol:str):
    base=collect_market_and_company(symbol);c=SearchFirst360Collector();errors={};ev={}
    fq,src,err=c.financials(symbol);errors['financial']=err;ev['financial_sources']=src
    bs,src,err=c.balance_sheets(symbol);errors['balance']=err;ev['balance_sources']=src
    cf,src,err=c.cashflows(symbol);errors['cashflow']=err;ev['cashflow_sources']=src
    sh,src,err=c.shareholding(symbol);errors['shareholding']=err;ev['shareholding_sources']=src
    ins,ins_ev,err=c._structured_events(symbol,'insider');errors['insider']=err;ev['insider_search']=ins_ev
    deals,deal_ev,err=c._structured_events(symbol,'deal');errors['deal']=err;ev['deal_search']=deal_ev
    actions,act_ev,err=c._structured_events(symbol,'action');errors['action']=err;ev['action_search']=act_ev
    if len(fq)>len(base.quarterly_financials):base.quarterly_financials=fq[-20:]
    if len(bs)>len(base.balance_sheet_quarters):base.balance_sheet_quarters=bs[-20:]
    if len(cf)>len(base.cashflow_quarters):base.cashflow_quarters=cf[-20:]
    if sh:base.shareholding_quarters=sh[-20:]
    if ins:base.insider_transactions=ins
    if deals:base.bulk_block_deals=deals
    if actions:base.corporate_actions=actions
    base.metadata.update({'search_first_candidate':True,'search_first_errors':errors,'search_first_evidence':ev,'search_first_counts':{'financial_quarters':len(base.quarterly_financials),'balance_sheet_quarters':len(base.balance_sheet_quarters),'cashflow_quarters':len(base.cashflow_quarters),'shareholding_quarters':len(base.shareholding_quarters),'insider_transactions':len(base.insider_transactions),'bulk_block_deals':len(base.bulk_block_deals),'corporate_actions':len(base.corporate_actions),'price_records':len(base.price_history)}})
    return base
