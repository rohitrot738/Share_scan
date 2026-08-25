from __future__ import annotations
import base64,re
from urllib.parse import urlparse,parse_qs,unquote
from bs4 import BeautifulSoup
from .deep_history_collector import DeepHistory360Collector
from .web_discovery_collector import PublicWebSearch,SearchHit,OFFICIAL_HOSTS,TRUSTED_HOST_HINTS
from .collector import collect_market_and_company

ALIASES={
 'RELIANCE':['reliance industries','ril'],
 'HDFCBANK':['hdfc bank'],
 'ICICIBANK':['icici bank'],
 'SBIN':['state bank of india','sbi'],
 'TCS':['tata consultancy services'],
 'INFY':['infosys'],
 'LT':['larsen & toubro','larsen and toubro'],
 'BHARTIARTL':['bharti airtel'],
 'HINDALCO':['hindalco industries'],
 'TATASTEEL':['tata steel'],
 'CANBK':['canara bank'],
 'AXISBANK':['axis bank'],
 'BEL':['bharat electronics'],
 'HAL':['hindustan aeronautics'],
 'PFC':['power finance corporation'],
 'RECLTD':['rec limited','rural electrification corporation'],
 'IRFC':['indian railway finance corporation'],
 'IREDA':['indian renewable energy development agency'],
 'RVNL':['rail vikas nigam'],
}

def _bing_decode(u:str)->str:
    if not u:return u
    try:
        p=urlparse(u)
        if 'bing.com' not in p.netloc:return u
        q=parse_qs(p.query)
        raw=(q.get('u') or [None])[0]
        if not raw:return u
        raw=unquote(raw)
        if raw.startswith('a1'):raw=raw[2:]
        raw += '='*((4-len(raw)%4)%4)
        dec=base64.urlsafe_b64decode(raw.encode()).decode('utf-8','ignore')
        return dec if dec.startswith('http') else u
    except:return u

class BetterPublicWebSearch(PublicWebSearch):
    def bing(self,q,limit=12):
        r=self.s.get('https://www.bing.com/search',params={'q':q,'count':limit},timeout=self.timeout);r.raise_for_status();s=BeautifulSoup(r.text,'lxml');out=[]
        for li in s.select('li.b_algo'):
            a=li.select_one('h2 a');p=li.select_one('.b_caption p')
            if not a:continue
            u=_bing_decode(str(a.get('href','')))
            if u.startswith('http'):out.append(SearchHit(u,a.get_text(' ',strip=True),p.get_text(' ',strip=True) if p else '','bing'))
            if len(out)>=limit:break
        return out
    def search(self,q,limit=12):
        # Merge providers instead of accepting the first non-empty provider.
        out=[];errors=[];seen=set()
        for fn in (self.duckduckgo,self.bing):
            try:
                for h in fn(q,limit):
                    h.url=_bing_decode(h.url)
                    if h.url not in seen:seen.add(h.url);out.append(h)
            except Exception as e:errors.append(f'{fn.__name__}: {type(e).__name__}: {e}')
        return out[:limit*2],errors

class IdentitySafe360Collector(DeepHistory360Collector):
    def __init__(self,timeout=15,max_hits=16):
        super().__init__(timeout=timeout,max_hits=max_hits)
        self.searcher=BetterPublicWebSearch(timeout=timeout,session=self.s)
    @staticmethod
    def _aliases(symbol):
        return ALIASES.get(symbol.upper(),[symbol.lower()])
    def _identity_ok(self,symbol,h):
        u=h.url.lower(); blob=(h.title+' '+h.snippet+' '+u).lower()
        # Official exchange/archive hits may use only the NSE symbol in document metadata/URL.
        if any(host in u for host in OFFICIAL_HOSTS):
            return symbol.lower() in blob or any(a in blob for a in self._aliases(symbol)) or 'nsearchives.nseindia.com' in u
        return any(a in blob for a in self._aliases(symbol))
    def discover(self,symbol,kind):
        alias=self._aliases(symbol)[0]
        qs={
          'financial':[f'"{alias}" NSE quarterly results revenue net profit EPS',f'site:nsearchives.nseindia.com "{symbol}" financial results iXBRL',f'"{alias}" investor quarterly results'],
          'balance':[f'"{alias}" quarterly balance sheet debt equity cash',f'"{alias}" investor balance sheet'],
          'cashflow':[f'"{alias}" quarterly cash flow operating cash flow',f'"{alias}" investor cash flow'],
          'shareholding':[f'"{alias}" shareholding pattern promoter FII FPI quarter',f'site:nsearchives.nseindia.com "{symbol}" shareholding',f'"{alias}" shareholding history'],
          'insider':[f'"{alias}" insider trading regulation 7(2) NSE',f'site:nsearchives.nseindia.com "{symbol}" regulation 7(2)'],
          'deal':[f'"{alias}" bulk deal block deal NSE',f'"{symbol}" bulk block deals NSE'],
          'action':[f'"{alias}" corporate actions dividend bonus split NSE',f'"{alias}" investor corporate actions'],
        }[kind]
        seen=set();allhits=[];errors=[]
        for q in qs:
            hits,errs=self.searcher.search(q,self.max_hits);errors+=errs
            for h in hits:
                h.url=_bing_decode(h.url)
                if h.url in seen or not self._identity_ok(symbol,h):continue
                seen.add(h.url);h.score=self._rank(h,kind)
                # Penalize generic homepages; reward filing/history words.
                text=(h.title+' '+h.snippet+' '+h.url).lower()
                if any(k in text for k in ('shareholding','quarter','financial','insider','bulk','block','corporate','xbrl','filing')):h.score+=25
                allhits.append(h)
        allhits.sort(key=lambda x:x.score,reverse=True)
        return allhits[:self.max_hits],errors

def collect_identity_safe_360cr(symbol:str):
    base=collect_market_and_company(symbol);c=IdentitySafe360Collector(timeout=15,max_hits=16);errors={};ev={}
    sq,e=c.screener_quarters(symbol);errors['screener_financial']=e;ev['screener_count']=len(sq)
    seed=sq if len(sq)>len(base.quarterly_financials) else base.quarterly_financials
    fq,src,e=c.quarter_sweep_financials(symbol,seed,target=20);errors['quarter_sweep']=e;ev['quarter_sweep_sources']=src
    sh,src,e=c.shareholding_history_tables(symbol,target=20);errors['shareholding_tables']=e;ev['shareholding_sources']=src
    if len(fq)>len(base.quarterly_financials):base.quarterly_financials=fq
    if sh:base.shareholding_quarters=sh
    # Also use identity-safe generic parsers for balance/cashflow and events.
    bs,src,e=c.balance_sheets(symbol,20);errors['balance']=e;ev['balance_sources']=src
    cf,src,e=c.cashflows(symbol,20);errors['cashflow']=e;ev['cashflow_sources']=src
    if len(bs)>len(base.balance_sheet_quarters):base.balance_sheet_quarters=bs
    if len(cf)>len(base.cashflow_quarters):base.cashflow_quarters=cf
    ins,ins_ev,e=c._structured_events(symbol,'insider');errors['insider']=e;ev['insider_sources']=ins_ev
    deals,deal_ev,e=c._structured_events(symbol,'deal');errors['deal']=e;ev['deal_sources']=deal_ev
    acts,act_ev,e=c._structured_events(symbol,'action');errors['action']=e;ev['action_sources']=act_ev
    if ins:base.insider_transactions=ins
    if deals:base.bulk_block_deals=deals
    if acts:base.corporate_actions=acts
    base.metadata.update({'identity_safe_candidate':True,'identity_safe_errors':errors,'identity_safe_evidence':ev,'identity_safe_counts':{
      'financial_quarters':len(base.quarterly_financials),'balance_sheet_quarters':len(base.balance_sheet_quarters),'cashflow_quarters':len(base.cashflow_quarters),'shareholding_quarters':len(base.shareholding_quarters),'insider_transactions':len(base.insider_transactions),'bulk_block_deals':len(base.bulk_block_deals),'corporate_actions':len(base.corporate_actions),'price_records':len(base.price_history)}})
    return base
