from __future__ import annotations
import re
from collections import defaultdict
from copy import deepcopy
from urllib.parse import urlparse
from .identity_safe_collector import IdentitySafe360Collector, ALIASES
from .collector import collect_market_and_company

OFFICIAL_OR_HIGH_TRUST=(
    'nsearchives.nseindia.com','archives.nseindia.com','nseindia.com','bseindia.com',
    'screener.in','moneycontrol.com','trendlyne.com','tickertape.in','economictimes.indiatimes.com'
)
BAD_EVENT_HOSTS=('wikipedia.org','facebook.com','linkedin.com','youtube.com')


def _period_key(p):
    m=re.search(r'\b(Mar|Jun|Sep|Dec)\s+(20\d{2})\b',str(p),re.I)
    if not m:return (0,0)
    return (int(m.group(2)),{'mar':3,'jun':6,'sep':9,'dec':12}[m.group(1).lower()])


def _merge_period_rows(a,b,fields,limit=20):
    best={}
    for r in list(a or [])+list(b or []):
        p=r.get('period')
        if not p:continue
        score=sum(r.get(k) is not None for k in fields)
        trust=2 if any(h in str(r.get('source_url','')).lower() for h in OFFICIAL_OR_HIGH_TRUST) else 0
        key=(score+trust,len(str(r.get('source_url',''))))
        if p not in best or key>best[p][0]:best[p]=(key,r)
    rows=[deepcopy(x[1]) for x in best.values()]
    rows.sort(key=lambda r:_period_key(r.get('period')))
    return rows[-limit:]


def _is_specific_event_url(url,kind):
    u=(url or '').lower(); host=urlparse(u).netloc
    if any(x in host for x in BAD_EVENT_HOSTS):return False
    if any(x in host for x in ('nsearchives.nseindia.com','archives.nseindia.com','nseindia.com','bseindia.com')):return True
    keys={
      'insider':('insider','regulation-7','regulation_7','disclosure','shareholding-change'),
      'deal':('bulk','block','deal'),
      'action':('corporate-action','corporate_action','dividend','bonus','split','rights','buyback')
    }[kind]
    return any(k in u for k in keys)


class ResearchAgent360Collector(IdentitySafe360Collector):
    """Standalone research-agent style candidate.

    Mimics: identify company -> plan searches by missing section -> discover -> open ->
    extract -> verify source quality -> merge -> re-search unresolved sections.
    No LLM/API secret required; decisions are deterministic and auditable.
    """
    def __init__(self,timeout=15,max_hits=18,max_rounds=3):
        super().__init__(timeout=timeout,max_hits=max_hits)
        self.max_rounds=max_rounds

    def _company_name(self,symbol):
        return ALIASES.get(symbol.upper(),[symbol.lower()])[0]

    def _queries_for(self,symbol,section,round_no):
        name=self._company_name(symbol)
        base={
          'financial':[
            f'"{name}" quarterly results 2022 revenue net profit',
            f'"{name}" quarterly results 2021 revenue net profit',
            f'site:screener.in/company/{symbol} quarterly results',
            f'site:moneycontrol.com "{name}" quarterly results',
            f'site:nsearchives.nseindia.com "{symbol}" financial results'
          ],
          'balance':[
            f'"{name}" balance sheet quarterly debt equity cash',
            f'site:moneycontrol.com "{name}" balance sheet',
            f'"{name}" investor annual report balance sheet'
          ],
          'cashflow':[
            f'"{name}" cash flow statement operating cash flow',
            f'site:moneycontrol.com "{name}" cash flow',
            f'"{name}" annual report cash flow'
          ],
          'shareholding':[
            f'"{name}" shareholding pattern Sep 2022 promoter FII',
            f'"{name}" shareholding pattern 2021 promoter FII',
            f'site:screener.in/company/{symbol} shareholding pattern',
            f'site:trendlyne.com "{name}" shareholding'
          ],
          'insider':[
            f'site:nsearchives.nseindia.com "{symbol}" "Regulation 7(2)"',
            f'site:bseindia.com "{name}" insider trading disclosure',
            f'"{name}" insider trading disclosure NSE'
          ],
          'deal':[
            f'"{name}" bulk deal block deal NSE',
            f'site:bseindia.com "{name}" bulk deal',
            f'site:moneycontrol.com "{name}" bulk deals'
          ],
          'action':[
            f'"{name}" corporate actions dividend bonus split',
            f'site:nsearchives.nseindia.com "{symbol}" dividend',
            f'site:bseindia.com "{name}" corporate action'
          ]
        }[section]
        # Later rounds deliberately broaden to older-year/archive language.
        if round_no>=2:
            if section in ('financial','shareholding'):base += [f'"{name}" 2020 2021 2022 historical {section}',f'"{symbol}" archive {section}']
            if section in ('insider','deal','action'):base += [f'"{symbol}" filing {section} 2024 2025 2026']
        return base

    def _search_hits(self,symbol,section,round_no):
        seen=set();hits=[];errors=[]
        for q in self._queries_for(symbol,section,round_no):
            h,e=self.searcher.search(q,self.max_hits);errors+=e
            for x in h:
                if x.url in seen or not self._identity_ok(symbol,x):continue
                seen.add(x.url);x.score=self._rank(x,section if section in ('financial','balance','cashflow','shareholding','insider','deal','action') else 'financial')
                if any(host in x.url.lower() for host in OFFICIAL_OR_HIGH_TRUST):x.score+=35
                hits.append(x)
        hits.sort(key=lambda x:x.score,reverse=True)
        return hits[:self.max_hits],errors

    def _financial_from_hits(self,symbol,round_no):
        hits,errs=self._search_hits(symbol,'financial',round_no);rows=[];src=[]
        for h in hits:
            try:
                q=self.parse_financial_page(h.url)
                if q:rows.append(q);src.append(q.get('source_url') or h.url)
            except Exception as e:errs.append(f'financial {h.url}: {type(e).__name__}: {e}')
        return rows,src,errs

    def _generic_from_hits(self,symbol,section,fields,round_no):
        hits,errs=self._search_hits(symbol,section,round_no);rows=[];src=[]
        for h in hits:
            try:
                s,final=self._soup(h.url);raw=s.get_text(' ',strip=True)[:50000];q={'period':self._date(raw),'source_url':final}
                for k,aliases in fields.items():q[k]=self._label_value(s,aliases)
                if any(q[k] is not None for k in fields):rows.append(q);src.append(final)
            except Exception as e:errs.append(f'{section} {h.url}: {type(e).__name__}: {e}')
        return rows,src,errs

    def _events_from_hits(self,symbol,kind,round_no):
        hits,errs=self._search_hits(symbol,kind,round_no);out=[];src=[]
        for h in hits:
            if not _is_specific_event_url(h.url,kind):continue
            try:
                # Reuse the existing parser but only on evidence-specific URLs.
                old_discover=self.discover
                try:
                    self.discover=lambda s,k: ([h],[])
                    rows,ev,e=self._structured_events(symbol,kind)
                finally:
                    self.discover=old_discover
                errs+=e
                for r in rows:
                    if _is_specific_event_url(r.get('source_url'),kind):out.append(r)
                if rows:src.append(h.url)
            except Exception as e:errs.append(f'{kind} {h.url}: {type(e).__name__}: {e}')
        # de-dupe
        seen=set();ded=[]
        for r in out:
            sig=(r.get('date'),r.get('side'),r.get('type'),r.get('quantity'),r.get('price'),r.get('value'))
            if sig in seen:continue
            seen.add(sig);ded.append(r)
        return ded,src,errs

    def run(self,symbol):
        base=collect_market_and_company(symbol)
        evidence=defaultdict(list);errors=defaultdict(list);rounds=[]

        # Strong table seeds first.
        sq,e=self.screener_quarters(symbol);errors['financial']+=e
        if sq:base.quarterly_financials=_merge_period_rows(base.quarterly_financials,sq,('revenue','ebitda','net_profit','eps'))
        sh,src,e=self.shareholding_history_tables(symbol,20);errors['shareholding']+=e;evidence['shareholding']+=src
        if sh:base.shareholding_quarters=_merge_period_rows(base.shareholding_quarters,sh,('promoter','fii_fpi','dii','mutual_fund','promoter_pledge'))

        for rnd in range(1,self.max_rounds+1):
            missing={
              'financial':len(base.quarterly_financials)<20,
              'balance':len(base.balance_sheet_quarters)<20,
              'cashflow':len(base.cashflow_quarters)<20,
              'shareholding':len(base.shareholding_quarters)<20,
              'insider':len(base.insider_transactions)==0,
              'deal':len(base.bulk_block_deals)==0,
              'action':len(base.corporate_actions)==0,
            }
            rounds.append({'round':rnd,'missing_before':[k for k,v in missing.items() if v]})
            if not any(missing.values()):break
            if missing['financial']:
                rows,src,e=self._financial_from_hits(symbol,rnd);errors['financial']+=e;evidence['financial']+=src
                base.quarterly_financials=_merge_period_rows(base.quarterly_financials,rows,('revenue','ebitda','net_profit','eps'))
            if missing['balance']:
                rows,src,e=self._generic_from_hits(symbol,'balance',{'total_debt':['total debt','borrowings','total borrowings'],'equity':['total equity','shareholders funds','shareholder equity'],'cash':['cash and cash equivalents','cash equivalents','cash balance']},rnd);errors['balance']+=e;evidence['balance']+=src
                base.balance_sheet_quarters=_merge_period_rows(base.balance_sheet_quarters,rows,('total_debt','equity','cash'))
            if missing['cashflow']:
                rows,src,e=self._generic_from_hits(symbol,'cashflow',{'operating_cash_flow':['cash flow from operating activities','net cash from operating activities','operating cash flow'],'free_cash_flow':['free cash flow']},rnd);errors['cashflow']+=e;evidence['cashflow']+=src
                base.cashflow_quarters=_merge_period_rows(base.cashflow_quarters,rows,('operating_cash_flow','free_cash_flow'))
            if missing['shareholding']:
                rows,src,e=self.shareholding_history_tables(symbol,20);errors['shareholding']+=e;evidence['shareholding']+=src
                base.shareholding_quarters=_merge_period_rows(base.shareholding_quarters,rows,('promoter','fii_fpi','dii','mutual_fund','promoter_pledge'))
            if missing['insider']:
                rows,src,e=self._events_from_hits(symbol,'insider',rnd);errors['insider']+=e;evidence['insider']+=src
                if rows:base.insider_transactions=rows
            if missing['deal']:
                rows,src,e=self._events_from_hits(symbol,'deal',rnd);errors['deal']+=e;evidence['deal']+=src
                if rows:base.bulk_block_deals=rows
            if missing['action']:
                rows,src,e=self._events_from_hits(symbol,'action',rnd);errors['action']+=e;evidence['action']+=src
                if rows:base.corporate_actions=rows

        base.metadata['research_agent_candidate']=True
        base.metadata['research_agent_rounds']=rounds
        base.metadata['research_agent_errors']={k:v for k,v in errors.items() if v}
        base.metadata['research_agent_evidence']={k:list(dict.fromkeys(v)) for k,v in evidence.items()}
        base.metadata['research_agent_counts']={
          'financial_quarters':len(base.quarterly_financials),'balance_sheet_quarters':len(base.balance_sheet_quarters),
          'cashflow_quarters':len(base.cashflow_quarters),'shareholding_quarters':len(base.shareholding_quarters),
          'insider_transactions':len(base.insider_transactions),'bulk_block_deals':len(base.bulk_block_deals),
          'corporate_actions':len(base.corporate_actions),'price_records':len(base.price_history)
        }
        return base


def collect_research_agent_360cr(symbol:str):
    return ResearchAgent360Collector().run(symbol)
