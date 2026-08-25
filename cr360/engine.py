from __future__ import annotations
import math, statistics
from typing import Any
from .models import ResearchInput, ResearchResult

WEIGHTS={"financial_quality":18,"growth":12,"cashflow":10,"balance_sheet":10,"ownership":12,"insider_deals":8,"valuation":12,"price_156w":8,"stability":5,"technical_risk":5}

def num(x):
    try:
        v=float(x); return v if math.isfinite(v) else None
    except: return None

def vals(rows,key,n=20): return [v for r in rows[-n:] if (v:=num(r.get(key))) is not None]
def clamp(x,a=0,b=100): return max(a,min(b,x))
def trend(xs):
    if len(xs)<2:return None
    base=abs(xs[0]) or 1e-9; return (xs[-1]-xs[0])/base*100

def score_financials(x):
    q=x.quarterly_financials[-20:]; missing=[]; evidence={}
    rev=vals(q,"revenue"); pat=vals(q,"net_profit"); ebitda=vals(q,"ebitda"); eps=vals(q,"eps")
    margins=[]
    for r in q:
        a,b=num(r.get("net_profit")),num(r.get("revenue"));
        if a is not None and b not in (None,0): margins.append(a/b*100)
    quality=None
    if pat and margins:
        pos=sum(v>0 for v in pat)/len(pat); stable=100-min(statistics.pstdev(margins)*5,60) if len(margins)>1 else 70
        quality=clamp(pos*65+stable*.35)
    else: missing.append("financial_quality")
    growth_parts=[]
    for name,series in [("revenue",rev),("profit",pat),("ebitda",ebitda),("eps",eps)]:
        t=trend(series); evidence[name+"_20q_change_pct"]=t
        if t is not None:growth_parts.append(clamp(50+t/2))
    growth=sum(growth_parts)/len(growth_parts) if growth_parts else None
    if growth is None:missing.append("growth")
    evidence.update({"quarters":len(q),"profitable_quarters":sum(v>0 for v in pat),"net_margin_latest":margins[-1] if margins else None})
    return quality,growth,evidence,missing

def score_cash_balance(x):
    cf=x.cashflow_quarters[-20:]; bs=x.balance_sheet_quarters[-20:]; miss=[]
    ocf=vals(cf,"operating_cash_flow"); fcf=vals(cf,"free_cash_flow"); pat=vals(x.quarterly_financials,"net_profit")
    cash=None
    if ocf:
        positive=sum(v>0 for v in ocf)/len(ocf)*60
        conv=50
        if pat and sum(pat[-min(len(pat),len(ocf)):])!=0: conv=clamp(sum(ocf[-len(pat):])/abs(sum(pat))*50)
        cash=clamp(positive+conv*.4)
    else:miss.append("cashflow")
    debt=vals(bs,"total_debt"); equity=vals(bs,"equity"); cashbal=vals(bs,"cash"); bs_score=None
    if debt or equity:
        de=(debt[-1]/equity[-1]) if debt and equity and equity[-1]>0 else None
        bs_score=clamp(85-(de or 0)*25)
        if debt and cashbal: bs_score=clamp(bs_score+(10 if cashbal[-1]>=debt[-1] else 0))
    else:miss.append("balance_sheet")
    return cash,bs_score,{"ocf_positive_ratio":sum(v>0 for v in ocf)/len(ocf) if ocf else None,"fcf_latest":fcf[-1] if fcf else None,"debt_latest":debt[-1] if debt else None,"equity_latest":equity[-1] if equity else None},miss

def score_ownership(x):
    rows=x.shareholding_quarters[-20:]; miss=[]; ev={"quarters":len(rows)}
    if not rows:return None,ev,["ownership_20q"]
    keys=["promoter","fii_fpi","dii","mutual_fund"] ; parts=[]
    for k in keys:
        a=vals(rows,k); ch=(a[-1]-a[0]) if len(a)>1 else None; ev[k+"_change_pp"]=ch; ev[k+"_latest"]=a[-1] if a else None
        if ch is not None:parts.append(clamp(50+ch*5))
    pled=vals(rows,"promoter_pledge");
    if pled: parts.append(clamp(100-pled[-1]*3)); ev["promoter_pledge_latest"]=pled[-1]
    return (sum(parts)/len(parts) if parts else None),ev,miss

def score_events(x):
    ins=x.insider_transactions; deals=x.bulk_block_deals; actions=x.corporate_actions
    buys=sum(num(r.get("value")) or 0 for r in ins if str(r.get("side","")).lower() in {"buy","purchase","acquire"})
    sells=sum(num(r.get("value")) or 0 for r in ins if str(r.get("side","")).lower() in {"sell","sale","dispose"})
    bbuy=sum(num(r.get("value")) or 0 for r in deals if str(r.get("side","")).lower()=="buy")
    bsell=sum(num(r.get("value")) or 0 for r in deals if str(r.get("side","")).lower()=="sell")
    if not ins and not deals:return None,{"insider_buy":buys,"insider_sell":sells,"bulk_block_buy":bbuy,"bulk_block_sell":bsell,"corporate_actions":actions},["insider_bulk_block"]
    net=(buys+bbuy)-(sells+bsell); scale=abs(buys)+abs(sells)+abs(bbuy)+abs(bsell) or 1
    return clamp(50+50*net/scale),{"insider_buy":buys,"insider_sell":sells,"bulk_block_buy":bbuy,"bulk_block_sell":bsell,"corporate_actions":actions},[]

def score_valuation(x):
    v=x.valuation; p=num(x.price); methods=[]; detail={}
    for k in ["dcf_fair_value","earnings_fair_value","book_fair_value","peer_fair_value","analyst_fair_value"]:
        z=num(v.get(k));
        if z and z>0: methods.append(z); detail[k]=z
    if not methods:return None,{"low":None,"base":None,"high":None,"upside_pct":None},["fair_value"]
    methods.sort(); base=statistics.median(methods); low=min(methods); high=max(methods); upside=((base/p)-1)*100 if p else None
    sc=clamp(50+(upside or 0)*1.5) if p else 50
    return sc,{"low":round(low,2),"base":round(base,2),"high":round(high,2),"upside_pct":round(upside,2) if upside is not None else None,"methods":detail},[]

def score_price(x):
    rows=x.price_history; p=num(x.price) or (num(rows[-1].get("close")) if rows else None)
    closes=[num(r.get("close")) for r in rows if num(r.get("close")) is not None]
    highs=[num(r.get("high")) for r in rows if num(r.get("high")) is not None]; lows=[num(r.get("low")) for r in rows if num(r.get("low")) is not None]
    if not p or len(closes)<30:return None,None,{},["156_week_price_history"]
    h=max(highs[-780:] or closes[-780:]); l=min(lows[-780:] or closes[-780:]); pos=(p-l)/(h-l)*100 if h>l else 50
    rets=[closes[i]/closes[i-1]-1 for i in range(1,len(closes)) if closes[i-1]>0]; vol=statistics.pstdev(rets[-156:])*math.sqrt(252)*100 if len(rets)>5 else None
    ma20=sum(closes[-20:])/min(20,len(closes)); ma50=sum(closes[-50:])/min(50,len(closes)); ma200=sum(closes[-200:])/min(200,len(closes))
    tech=clamp(35+(20 if p>ma20 else 0)+(20 if p>ma50 else 0)+(25 if p>ma200 else 0))
    stability=clamp(100-(vol or 50))
    support=min(closes[-60:],key=lambda z:abs(z-p*.95)) if closes else None; resistance=min(closes[-60:],key=lambda z:abs(z-p*1.05)) if closes else None
    risk={"price":p,"156w_high":h,"156w_low":l,"position_156w_pct":round(pos,2),"annualized_volatility_pct":round(vol,2) if vol else None,"ma20":ma20,"ma50":ma50,"ma200":ma200,"support_proxy":support,"resistance_proxy":resistance,"downside_to_support_pct":round((support/p-1)*100,2) if support else None,"upside_to_resistance_pct":round((resistance/p-1)*100,2) if resistance else None}
    return tech,stability,risk,[]

def analyse_360cr(data:ResearchInput|dict)->ResearchResult:
    x=data if isinstance(data,ResearchInput) else ResearchInput(**data); missing=[]; warnings=[]; sections={}; evidence={}
    fq,gr,ev,m=score_financials(x); sections.update(financial_quality=fq,growth=gr); evidence["financials"]=ev; missing+=m
    cf,bs,ev,m=score_cash_balance(x); sections.update(cashflow=cf,balance_sheet=bs); evidence["cash_balance"]=ev; missing+=m
    own,ev,m=score_ownership(x); sections["ownership"]=own; evidence["ownership"]=ev; missing+=m
    evt,ev,m=score_events(x); sections["insider_deals"]=evt; evidence["events"]=ev; missing+=m
    val,fair,m=score_valuation(x); sections["valuation"]=val; missing+=m
    tech,stab,risk,m=score_price(x); sections.update(price_156w=tech,stability=stab,technical_risk=tech); missing+=m
    available={k:v for k,v in sections.items() if v is not None}; covered_weight=sum(WEIGHTS[k] for k in available); total=sum(WEIGHTS.values())
    score=sum(available[k]*WEIGHTS[k] for k in available)/covered_weight if covered_weight else None
    confidence=covered_weight/total*100
    if confidence<60:warnings.append("LOW_COVERAGE: conviction must not be treated as complete")
    state="INSUFFICIENT_DATA" if score is None or confidence<40 else ("HIGH_CONVICTION" if score>=75 else "POSITIVE" if score>=62 else "NEUTRAL" if score>=48 else "CAUTION" if score>=35 else "AVOID")
    coverage={"score_pct":round(confidence,2),"available_sections":sorted(available),"missing_sections":sorted(set(missing)),"financial_quarters":min(len(x.quarterly_financials),20),"shareholding_quarters":min(len(x.shareholding_quarters),20),"cashflow_quarters":min(len(x.cashflow_quarters),20),"balance_sheet_quarters":min(len(x.balance_sheet_quarters),20),"price_records":len(x.price_history)}
    return ResearchResult(x.symbol,round(score,2) if score is not None else None,round(confidence,2),state,coverage,sections,fair,risk,evidence,warnings,sorted(set(missing)))

def analyse_many_360cr(items): return [analyse_360cr(x).to_dict() for x in items]
