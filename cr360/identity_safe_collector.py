from __future__ import annotations
import base64,re
from urllib.parse import urlparse,parse_qs,unquote
from bs4 import BeautifulSoup
from .deep_history_collector import DeepHistory360Collector,_rows,_norm_period,_num,_period_key
from .web_discovery_collector import PublicWebSearch,SearchHit,OFFICIAL_HOSTS,TRUSTED_HOST_HINTS
from .collector import collect_market_and_company

ALIASES={
 'RELIANCE':['reliance industries','ril'], 'HDFCBANK':['hdfc bank'], 'ICICIBANK':['icici bank'],
 'SBIN':['state bank of india','sbi'], 'TCS':['tata consultancy services'], 'INFY':['infosys'],
 'LT':['larsen & toubro','larsen and toubro'], 'BHARTIARTL':['bharti airtel'],
 'HINDALCO':['hindalco industries'], 'TATASTEEL':['tata steel'], 'CANBK':['canara bank'],
 'AXISBANK':['axis bank'], 'BEL':['bharat electronics'], 'HAL':['hindustan aeronautics'],
 'PFC':['power finance corporation'], 'RECLTD':['rec limited','rural electrification corporation'],
 'IRFC':['indian railway finance corporation'], 'IREDA':['indian renewable energy development agency'],
 'RVNL':['rail vikas nigam'],
}

BAD_HOST_HINTS=('wikipedia.org','netbanking','login','logout','merriam-webster','reliance.com','relianceinc.com')
EVENT_WORDS={
 'insider':('insider','regulation 7(2)','reg 7(2)','acquisition','disposal','sast','pit'),
 'deal':('bulk deal','block deal','bulk deals','block deals'),
 'action':('corporate action','dividend','bonus','split','rights','buyback','record date','ex-date'),
}
TRUSTED_EVENT_HOSTS=('nsearchives.nseindia.com','archives.nseindia.com','nseindia.com','bseindia.com','moneycontrol.com','trendlyne.com','economictimes.indiatimes.com')


def _bing_decode(u:str)->str:
    if not u:return u
    try:
        p=urlparse(u)
        if 'bing.com' not in p.netloc:return u
        raw=(parse_qs(p.query).get('u') or [None])[0]
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
    def _aliases(symbol):return ALIASES.get(symbol.upper(),[symbol.lower()])
    def _identity_ok(self,symbol,h):
        u=h.url.lower();blob=(h.title+' '+h.snippet+' '+u).lower()
        if any(x in u for x in BAD_HOST_HINTS):return False
        if any(host in u for host in OFFICIAL_HOSTS):
            return symbol.lower() in blob or any(a in blob for a in self._aliases(symbol)) or 'nsearchives.nseindia.com' in u
        return any(a in blob for a in self._aliases(symbol))
    def _event_source_ok(self,h,kind):
        u=h.url.lower();blob=(h.title+' '+h.snippet+' '+u).lower()
        if any(x in u for x in BAD_HOST_HINTS):return False
        if not any(host in u for host in TRUSTED_EVENT_HOSTS):return False
        return any(w in blob for w in EVENT_WORDS[kind])
    def discover(self,symbol,kind):
        alias=self._aliases(symbol)[0]
        qs={
          'financial':[f'"{alias}" NSE quarterly results revenue net profit EPS',f'site:nsearchives.nseindia.com "{symbol}" financial results iXBRL',f'"{alias}" investor quarterly results'],
          'balance':[f'"{alias}" quarterly balance sheet debt equity cash',f'"{alias}" moneycontrol balance sheet quarterly',f'"{alias}" investor balance sheet'],
          'cashflow':[f'"{alias}" quarterly cash flow operating cash flow',f'"{alias}" moneycontrol cash flow quarterly',f'"{alias}" investor cash flow'],
          'shareholding':[f'"{alias}" shareholding pattern promoter FII FPI quarter',f'site:nsearchives.nseindia.com "{symbol}" shareholding',f'"{alias}" screener shareholding pattern',f'"{alias}" shareholding history'],
          'insider':[f'"{alias}" insider trading regulation 7(2) NSE',f'site:nsearchives.nseindia.com "{symbol}" regulation 7(2)',f'"{alias}" insider trading disclosure'],
          'deal':[f'"{alias}" bulk deal block deal NSE',f'"{symbol}" bulk block deals NSE',f'"{alias}" moneycontrol bulk deals'],
          'action':[f'"{alias}" corporate actions dividend bonus split NSE',f'"{alias}" investor corporate actions',f'"{alias}" moneycontrol corporate action'],
        }[kind]
        seen=set();allhits=[];errors=[]
        for q in qs:
            hits,errs=self.searcher.search(q,self.max_hits);errors+=errs
            for h in hits:
                h.url=_bing_decode(h.url)
                if h.url in seen or not self._identity_ok(symbol,h):continue
                if kind in EVENT_WORDS and not self._event_source_ok(h,kind):continue
                seen.add(h.url);h.score=self._rank(h,kind)
                text=(h.title+' '+h.snippet+' '+h.url).lower()
                if any(k in text for k in ('shareholding','quarter','financial','insider','bulk','block','corporate','xbrl','filing','record date')):h.score+=25
                if any(host in h.url.lower() for host in OFFICIAL_HOSTS):h.score+=50
                allhits.append(h)
        allhits.sort(key=lambda x:x.score,reverse=True)
        return allhits[:self.max_hits],errors

    def screener_shareholding(self,symbol,target=20):
        urls=[f'https://www.screener.in/company/{symbol}/consolidated/',f'https://www.screener.in/company/{symbol}/']
        best=[];sources=[];errors=[]
        for url in urls:
            try:
                s,final=self._html(url)
                heading=s.find(lambda t:getattr(t,'name',None) in ('h2','h3') and 'Shareholding Pattern' in t.get_text(' ',strip=True))
                table=heading.find_next('table') if heading else None
                if not table:continue
                rr=_rows(table)
                if len(rr)<3:continue
                periods=[_norm_period(x) for x in rr[0][1:]]
                labels={r[0].lower().strip():r[1:] for r in rr[1:] if r}
                rows=[]
                for i,p in enumerate(periods):
                    q={'period':p,'source_url':final}
                    for k,v in labels.items():
                        if i>=len(v):continue
                        if 'promoter' in k:q['promoter']=_num(v[i])
                        elif 'fii' in k or 'fpi' in k or 'foreign' in k:q['fii_fpi']=_num(v[i])
                        elif 'dii' in k or 'domestic institutional' in k:q['dii']=_num(v[i])
                        elif 'mutual' in k:q['mutual_fund']=_num(v[i])
                    if any(q.get(k) is not None for k in ('promoter','fii_fpi','dii','mutual_fund')):rows.append(q)
                if len(rows)>len(best):best=rows;sources=[final]
            except Exception as e:errors.append(f'screener shareholding {url}: {type(e).__name__}: {e}')
        best.sort(key=lambda x:_period_key(x.get('period')))
        return best[-target:],sources,errors

    def trusted_table_history(self,symbol,kind,target=20):
        fields={
          'balance':{'total_debt':['total debt','borrowings','total borrowings'],'equity':['total equity','shareholders funds','shareholder equity'],'cash':['cash and cash equivalents','cash equivalents','cash balance']},
          'cashflow':{'operating_cash_flow':['cash flow from operating activities','net cash from operating activities','operating cash flow'],'free_cash_flow':['free cash flow']},
        }[kind]
        hits,errors=self.discover(symbol,kind);out=[];sources=[]
        for h in hits:
            u=h.url.lower()
            if not any(x in u for x in ('moneycontrol.com','trendlyne.com','screener.in','economictimes.indiatimes.com','nsearchives.nseindia.com','archives.nseindia.com')):continue
            try:
                s,final=self._html(h.url)
                for table in s.find_all('table'):
                    rr=_rows(table)
                    if len(rr)<3:continue
                    head=rr[0]
                    periods=[_norm_period(x) for x in head[1:]]
                    if not any(re.search(r'\b(Mar|Jun|Sep|Dec)\s+20\d{2}\b',str(p),re.I) for p in periods):continue
                    labels={r[0].lower().strip():r[1:] for r in rr[1:] if r}
                    for i,p in enumerate(periods):
                        q={'period':p,'source_url':final}
                        for dest,aliases in fields.items():
                            val=None
                            for label,vals in labels.items():
                                if any(a in label for a in aliases) and i<len(vals):val=_num(vals[i]);break
                            q[dest]=val
                        if any(q.get(k) is not None for k in fields):out.append(q)
                if out:sources.append(final)
            except Exception as e:errors.append(f'{kind} table {h.url}: {type(e).__name__}: {e}')
        best={}
        for q in out:
            p=_norm_period(q.get('period'));score=sum(q.get(k) is not None for k in fields)
            if p and (p not in best or score>best[p][0]):best[p]=(score,q)
        rows=[v[1] for v in best.values()];rows.sort(key=lambda x:_period_key(x.get('period')))
        return rows[-target:],sources,errors

    def strict_events(self,symbol,kind):
        hits,errors=self.discover(symbol,kind);out=[];evidence=[]
        for h in hits[:12]:
            if not self._event_source_ok(h,kind):continue
            evidence.append({'url':h.url,'title':h.title,'snippet':h.snippet,'provider':h.provider,'score':h.score})
            try:
                s,final=self._soup(h.url);txt=s.get_text(' ',strip=True)[:50000];d=self._date(txt)
                if kind=='insider':
                    side='buy' if re.search(r'\b(acquisition|purchase|bought|buy)\b',txt,re.I) else 'sell' if re.search(r'\b(disposal|sale|sold|sell)\b',txt,re.I) else None
                    if side:out.append({'date':d,'side':side,'source_url':final,'evidence_title':h.title})
                elif kind=='deal':
                    side='buy' if re.search(r'\bbuy\b|\bbought\b',txt,re.I) else 'sell' if re.search(r'\bsell\b|\bsold\b',txt,re.I) else None
                    mq=re.search(r'(?:qty|quantity|shares)[^\d]{0,20}([\d,]+)',txt,re.I);mp=re.search(r'(?:price|rate)[^\d]{0,20}([\d,.]+)',txt,re.I)
                    qty=self._num(mq.group(1)) if mq else None;price=self._num(mp.group(1)) if mp else None
                    if side or qty or price:out.append({'date':d,'side':side,'quantity':qty,'price':price,'value':qty*price if qty and price else None,'source_url':final})
                else:
                    typ=next((name for name in ('dividend','bonus','split','rights','buyback') if re.search(r'\b'+name+r'\b',txt,re.I)),None)
                    if typ:out.append({'date':d,'type':typ,'source_url':final,'evidence_title':h.title})
            except Exception as e:errors.append(f'{kind} fetch {h.url}: {type(e).__name__}: {e}')
        seen=set();ded=[]
        for r in out:
            sig=tuple((k,r.get(k)) for k in sorted(r) if k!='source_url')
            if sig not in seen:seen.add(sig);ded.append(r)
        return ded,evidence,errors


def collect_identity_safe_360cr(symbol:str):
    base=collect_market_and_company(symbol);c=IdentitySafe360Collector(timeout=15,max_hits=16);errors={};ev={}
    sq,e=c.screener_quarters(symbol);errors['screener_financial']=e;ev['screener_count']=len(sq)
    seed=sq if len(sq)>len(base.quarterly_financials) else base.quarterly_financials
    fq,src,e=c.quarter_sweep_financials(symbol,seed,target=20);errors['quarter_sweep']=e;ev['quarter_sweep_sources']=src
    sh1,src1,e1=c.screener_shareholding(symbol,20);sh2,src2,e2=c.shareholding_history_tables(symbol,target=20)
    errors['shareholding_tables']=e1+e2;ev['shareholding_sources']=src1+src2
    sh=sh1 if len(sh1)>=len(sh2) else sh2
    if len(fq)>len(base.quarterly_financials):base.quarterly_financials=fq
    if sh:base.shareholding_quarters=sh
    bs1,src,e=c.trusted_table_history(symbol,'balance',20);errors['balance']=e;ev['balance_sources']=src
    cf1,src,e=c.trusted_table_history(symbol,'cashflow',20);errors['cashflow']=e;ev['cashflow_sources']=src
    if len(bs1)>len(base.balance_sheet_quarters):base.balance_sheet_quarters=bs1
    if len(cf1)>len(base.cashflow_quarters):base.cashflow_quarters=cf1
    ins,ins_ev,e=c.strict_events(symbol,'insider');errors['insider']=e;ev['insider_sources']=ins_ev
    deals,deal_ev,e=c.strict_events(symbol,'deal');errors['deal']=e;ev['deal_sources']=deal_ev
    acts,act_ev,e=c.strict_events(symbol,'action');errors['action']=e;ev['action_sources']=act_ev
    if ins:base.insider_transactions=ins
    if deals:base.bulk_block_deals=deals
    if acts:base.corporate_actions=acts
    base.metadata.update({'identity_safe_candidate':True,'identity_safe_errors':errors,'identity_safe_evidence':ev,'identity_safe_counts':{
      'financial_quarters':len(base.quarterly_financials),'balance_sheet_quarters':len(base.balance_sheet_quarters),'cashflow_quarters':len(base.cashflow_quarters),'shareholding_quarters':len(base.shareholding_quarters),'insider_transactions':len(base.insider_transactions),'bulk_block_deals':len(base.bulk_block_deals),'corporate_actions':len(base.corporate_actions),'price_records':len(base.price_history)}})
    return base
