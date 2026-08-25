from __future__ import annotations
import re
from datetime import date
from bs4 import BeautifulSoup
from .collector import collect_market_and_company
from .web_discovery_collector import SearchFirst360Collector

QMONTHS=(3,6,9,12)

def _quarter_labels(n=24):
    today=date.today(); y=today.year; m=max(q for q in QMONTHS if q<=today.month) if any(q<=today.month for q in QMONTHS) else 12
    out=[]
    for _ in range(n):
        mon={3:'Mar',6:'Jun',9:'Sep',12:'Dec'}[m]
        out.append(f'{mon} {y}')
        m-=3
        if m<=0:m=12;y-=1
    return out

def _num(s):
    try:
        t=str(s).replace(',','').replace('%','').replace('₹','').strip()
        m=re.search(r'-?\d+(?:\.\d+)?',t)
        return float(m.group()) if m else None
    except:return None

def _rows(soup):
    out=[]
    for tr in soup.find_all('tr'):
        cells=[c.get_text(' ',strip=True) for c in tr.find_all(['th','td'])]
        if cells:out.append(cells)
    return out

def _norm_period(s):
    m=re.search(r'\b(Mar|Jun|Sep|Sept|Dec)\s+(20\d{2})\b',str(s),re.I)
    if not m:return str(s).strip() or None
    mon='Sep' if m.group(1).lower().startswith('sept') else m.group(1).title()
    return f'{mon} {m.group(2)}'

class DeepHistory360Collector(SearchFirst360Collector):
    """Standalone candidate only; not wired into main scanner.
    Adds: trusted-table parsing + quarter-specific discovery for the older 20Q window.
    """
    def _html(self,url):
        text,final=self._get(url)
        return BeautifulSoup(text,'lxml'),final

    def screener_quarters(self,symbol):
        urls=[f'https://www.screener.in/company/{symbol}/consolidated/',f'https://www.screener.in/company/{symbol}/']
        best=[];errors=[]
        for url in urls:
            try:
                s,final=self._html(url)
                heading=s.find(lambda t:getattr(t,'name',None) in ('h2','h3') and 'Quarterly Results' in t.get_text(' ',strip=True))
                table=heading.find_next('table') if heading else None
                if not table:continue
                rr=_rows(table)
                if len(rr)<3:continue
                periods=[_norm_period(x) for x in rr[0][1:]]
                by={r[0].lower().replace('+','').strip():r[1:] for r in rr[1:] if r}
                def pick(names):
                    for k,v in by.items():
                        if any(n in k for n in names):return v
                    return []
                sales=pick(['sales','revenue','total income']); op=pick(['operating profit','ebitda']); profit=pick(['net profit','profit after tax']); eps=pick(['eps in rs','eps'])
                rows=[]
                for i,p in enumerate(periods):
                    q={'period':p,'revenue':_num(sales[i]) if i<len(sales) else None,'ebitda':_num(op[i]) if i<len(op) else None,'net_profit':_num(profit[i]) if i<len(profit) else None,'eps':_num(eps[i]) if i<len(eps) else None,'source_url':final}
                    if any(q[k] is not None for k in ('revenue','ebitda','net_profit','eps')):rows.append(q)
                if len(rows)>len(best):best=rows
            except Exception as e:errors.append(f'screener {url}: {type(e).__name__}: {e}')
        return best,errors

    def quarter_sweep_financials(self,symbol,existing=None,target=20):
        existing=list(existing or []); have={_norm_period(x.get('period')) for x in existing if x.get('period')};errors=[];sources=[]
        for label in _quarter_labels(28):
            if len(existing)>=target:break
            if label in have:continue
            q=f'site:nsearchives.nseindia.com {symbol} "{label}" quarterly financial results iXBRL'
            hits,errs=self.searcher.search(q,8);errors+=errs
            for h in hits:
                if 'nsearchives.nseindia.com' not in h.url.lower() and 'archives.nseindia.com' not in h.url.lower():continue
                try:
                    row=self.parse_financial_page(h.url)
                    if not row:continue
                    row['period']=row.get('period') or label
                    p=_norm_period(row['period'])
                    if p in have:continue
                    row['period']=p;existing.append(row);have.add(p);sources.append(h.url);break
                except Exception as e:errors.append(f'{label} {h.url}: {type(e).__name__}: {e}')
        existing.sort(key=lambda x:(_period_key(x.get('period'))));return existing[-target:],sources,errors

    def shareholding_history_tables(self,symbol,target=20):
        queries=[f'{symbol} stockezee shareholding',f'{symbol} tickjournal shareholding pattern',f'{symbol} trendlyne shareholding history']
        hits=[];errors=[];seen=set()
        for q in queries:
            h,e=self.searcher.search(q,8);errors+=e
            for x in h:
                if x.url not in seen:seen.add(x.url);hits.append(x)
        out=[];sources=[]
        for h in hits:
            try:
                s,final=self._html(h.url)
                for table in s.find_all('table'):
                    rr=_rows(table)
                    if len(rr)<3:continue
                    head=[x.lower() for x in rr[0]]
                    # Orientation A: period rows, category columns.
                    if any('promoter' in x for x in head) and any(('fii' in x or 'fpi' in x) for x in head):
                        for r in rr[1:]:
                            if len(r)<2:continue
                            p=_norm_period(r[0]);q={'period':p,'source_url':final}
                            for i,hdr in enumerate(head[1:],1):
                                if i>=len(r):continue
                                if 'promoter' in hdr:q['promoter']=_num(r[i])
                                elif 'fii' in hdr or 'fpi' in hdr:q['fii_fpi']=_num(r[i])
                                elif 'dii' in hdr:q['dii']=_num(r[i])
                                elif 'mutual' in hdr:q['mutual_fund']=_num(r[i])
                            if any(q.get(k) is not None for k in ('promoter','fii_fpi','dii','mutual_fund')):out.append(q)
                    # Orientation B: category rows, period columns.
                    periods=[_norm_period(x) for x in rr[0][1:]]
                    labels={r[0].lower():r[1:] for r in rr[1:] if r}
                    if periods and any('promoter' in k for k in labels):
                        for i,p in enumerate(periods):
                            q={'period':p,'source_url':final}
                            for k,v in labels.items():
                                if i>=len(v):continue
                                if 'promoter' in k:q['promoter']=_num(v[i])
                                elif 'fii' in k or 'fpi' in k or 'foreign' in k:q['fii_fpi']=_num(v[i])
                                elif 'dii' in k or 'domestic institutional' in k:q['dii']=_num(v[i])
                                elif 'mutual' in k:q['mutual_fund']=_num(v[i])
                            if any(q.get(k) is not None for k in ('promoter','fii_fpi','dii','mutual_fund')):out.append(q)
                if out:sources.append(final)
            except Exception as e:errors.append(f'shareholding {h.url}: {type(e).__name__}: {e}')
        best={}
        for q in out:
            p=_norm_period(q.get('period'))
            if not p:continue
            score=sum(q.get(k) is not None for k in ('promoter','fii_fpi','dii','mutual_fund'))
            if p not in best or score>best[p][0]:best[p]=(score,q)
        rows=[v[1] for v in best.values()];rows.sort(key=lambda x:_period_key(x.get('period')))
        return rows[-target:],sources,errors

def _period_key(p):
    m=re.search(r'\b(Mar|Jun|Sep|Dec)\s+(20\d{2})\b',str(p),re.I)
    if not m:return (0,0)
    return (int(m.group(2)),{'mar':3,'jun':6,'sep':9,'dec':12}[m.group(1).lower()])

def collect_deep_history_360cr(symbol:str):
    base=collect_market_and_company(symbol);c=DeepHistory360Collector(timeout=15,max_hits=12);errors={};evidence={}
    sq,e=c.screener_quarters(symbol);errors['screener_financial']=e;evidence['screener_count']=len(sq)
    seed=sq if len(sq)>len(base.quarterly_financials) else base.quarterly_financials
    fq,src,e=c.quarter_sweep_financials(symbol,seed,target=20);errors['quarter_sweep']=e;evidence['quarter_sweep_sources']=src
    sh,src,e=c.shareholding_history_tables(symbol,target=20);errors['shareholding_tables']=e;evidence['shareholding_sources']=src
    if len(fq)>len(base.quarterly_financials):base.quarterly_financials=fq
    if sh:base.shareholding_quarters=sh
    # Keep other event parsing from the existing search-first candidate, but don't fabricate.
    ins,ins_ev,e=c._structured_events(symbol,'insider');errors['insider']=e;evidence['insider_sources']=ins_ev
    deals,deal_ev,e=c._structured_events(symbol,'deal');errors['deal']=e;evidence['deal_sources']=deal_ev
    acts,act_ev,e=c._structured_events(symbol,'action');errors['action']=e;evidence['action_sources']=act_ev
    if ins:base.insider_transactions=ins
    if deals:base.bulk_block_deals=deals
    if acts:base.corporate_actions=acts
    base.metadata['deep_history_candidate']=True
    base.metadata['deep_history_errors']=errors
    base.metadata['deep_history_evidence']=evidence
    base.metadata['deep_history_counts']={'financial_quarters':len(base.quarterly_financials),'balance_sheet_quarters':len(base.balance_sheet_quarters),'cashflow_quarters':len(base.cashflow_quarters),'shareholding_quarters':len(base.shareholding_quarters),'insider_transactions':len(base.insider_transactions),'bulk_block_deals':len(base.bulk_block_deals),'corporate_actions':len(base.corporate_actions),'price_records':len(base.price_history)}
    return base
