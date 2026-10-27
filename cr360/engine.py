from __future__ import annotations
import math, statistics
from .models import ResearchInput, ResearchResult
from .valuation import HIGH_RISK, MANIPULATION, REJECT

WEIGHTS={"financial_quality":18,"growth":12,"cashflow":10,"balance_sheet":10,"ownership":12,"insider_deals":8,"valuation":12,"price_156w":8,"stability":5,"technical_risk":5}
# Lenders and the rest of financial services borrow to lend and pay loans out as operating cash: their debt and cash
# flow are the business itself, so those parts do not apply to them (metadata "financial").
NOT_FOR_FINANCIALS={"cashflow","balance_sheet"}
LEVEL_GAP=.01  # a past close within 1% of today's price is that price, not a support or resistance below or above it
def num(x):
    try:v=float(x);return v if math.isfinite(v) else None
    except:return None
def vals(rows,key,n=20):return [v for r in rows[-n:] if (v:=num(r.get(key))) is not None]
def clamp(x,a=0,b=100):return max(a,min(b,x))
def change(xs):
    """The last four periods against the first four (half the periods each when there are under eight): the percent
    change and its 0-100 score. From a loss or next to nothing a percent says nothing (None); the direction still counts."""
    k=min(4,len(xs)//2)
    if k<1:return None,None
    a,b=sum(xs[:k]),sum(xs[-k:])
    if a>0 and a>=abs(b)*.05:pct=(b-a)/a*100;return pct,clamp(50+pct/2)
    return None,((100 if b>0 else 60) if b>a else 0 if b<a else 50)

def score_financials(x):
    q=x.quarterly_financials[-20:];missing=[];ev={};rev=vals(q,"revenue");pat=vals(q,"net_profit");eb=vals(q,"ebitda");eps=vals(q,"eps");marg=[]
    for r in q:
        a,b=num(r.get("net_profit")),num(r.get("revenue"))
        if a is not None and b not in (None,0):marg.append(a/b*100)
    quality=None
    if pat and marg:
        quality=clamp(sum(v>0 for v in pat)/len(pat)*65+(100-min(statistics.pstdev(marg)*5,60) if len(marg)>1 else 70)*.35)
    else:missing.append("financial_quality")
    gp=[]
    for name,s in (("revenue",rev),("profit",pat),("ebitda",eb),("eps",eps)):
        t,g=change(s);ev[name+"_20q_change_pct"]=t
        if g is not None:gp.append(g)
    growth=sum(gp)/len(gp) if gp else None
    if growth is None:missing.append("growth")
    ev.update(quarters=len(q),profitable_quarters=sum(v>0 for v in pat),net_margin_latest=marg[-1] if marg else None)
    return quality,growth,ev,missing

def score_cash_balance(x):
    if x.metadata.get("financial"):return None,None,{},[]
    cf=x.cashflow_quarters[-20:];bs=x.balance_sheet_quarters[-20:];miss=[];ocf=vals(cf,"operating_cash_flow");fcf=vals(cf,"free_cash_flow");pat=vals(x.quarterly_financials,"net_profit");cash=None
    if ocf:
        # Yearly cash flows that carry their year's profit (Screener) are compared with it, not with quarters.
        base=vals(cf,"net_profit") or pat
        conv=50; common=min(len(base),len(ocf))
        if common and sum(base[-common:])!=0:conv=clamp(sum(ocf[-common:])/abs(sum(base[-common:]))*50)
        cash=clamp(sum(v>0 for v in ocf)/len(ocf)*60+conv*.4)
    else:miss.append("cashflow")
    debt=vals(bs,"total_debt");equity=vals(bs,"equity");cb=vals(bs,"cash");bscore=None
    if equity:
        de=debt[-1]/equity[-1] if debt and equity[-1]>0 else 0;bscore=clamp(85-de*25)
        if debt and cb and cb[-1]>=debt[-1]:bscore=clamp(bscore+10)
    else:miss.append("balance_sheet")
    return cash,bscore,{"ocf_positive_ratio":sum(v>0 for v in ocf)/len(ocf) if ocf else None,"fcf_latest":fcf[-1] if fcf else None,"debt_latest":debt[-1] if debt else None,"equity_latest":equity[-1] if equity else None},miss

def score_ownership(x):
    rows=x.shareholding_quarters[-20:];ev={"quarters":len(rows)}
    if not rows:return None,ev,["ownership_20q"]
    parts=[]
    for k in ("promoter","fii_fpi","dii","mutual_fund"):
        a=vals(rows,k);ch=a[-1]-a[0] if len(a)>1 else None;ev[k+"_change_pp"]=ch;ev[k+"_latest"]=a[-1] if a else None
        if ch is not None:parts.append(clamp(50+ch*5))
    pled=vals(rows,"promoter_pledge")
    if pled:parts.append(clamp(100-pled[-1]*3));ev["promoter_pledge_latest"]=pled[-1]
    return (sum(parts)/len(parts) if parts else None),ev,[]

def score_events(x):
    ins=x.insider_transactions;deals=x.bulk_block_deals;actions=x.corporate_actions
    buys=sum(num(r.get("value")) or 0 for r in ins if str(r.get("side","")).lower() in {"buy","purchase","acquire"});sells=sum(num(r.get("value")) or 0 for r in ins if str(r.get("side","")).lower() in {"sell","sale","dispose"});bb=sum(num(r.get("value")) or 0 for r in deals if str(r.get("side","")).lower()=="buy");bs=sum(num(r.get("value")) or 0 for r in deals if str(r.get("side","")).lower()=="sell")
    ev={"insider_buy":buys,"insider_sell":sells,"bulk_block_buy":bb,"bulk_block_sell":bs,"corporate_actions":actions}
    if not ins and not deals:return None,ev,["insider_bulk_block"]
    net=buys+bb-sells-bs;scale=abs(buys)+abs(sells)+abs(bb)+abs(bs) or 1
    return clamp(50+50*net/scale),ev,[]

def score_valuation(x):
    p=num(x.price);methods=[];detail={};own=num(x.valuation.get("history_fair_value"))
    for k in ("dcf_fair_value","earnings_fair_value","book_fair_value","peer_fair_value","analyst_fair_value"):
        z=num(x.valuation.get(k))
        if z and z>0:methods.append(z);detail[k]=z
    own=own if own and own>0 else None
    if not methods and not own:return None,{"low":None,"base":None,"high":None,"upside_pct":None},["fair_value"]
    fair={"low":None,"base":None,"high":None,"upside_pct":None};scores=[]
    if methods:
        methods.sort();base=statistics.median(methods);up=((base/p)-1)*100 if p else None
        fair={"low":round(min(methods),2),"base":round(base,2),"high":round(max(methods),2),"upside_pct":round(up,2) if up is not None else None,"methods":detail}
        scores.append(clamp(50+(up or 0)*1.5) if p else 50)
    # The company's own past multiple (history_fair_value) weighs as much as the market's, so a business that has
    # always traded at a premium is judged against that too and not only against its sector.
    if own:
        hup=((own/p)-1)*100 if p else None
        fair.update(history=round(own,2),history_upside_pct=round(hup,2) if hup is not None else None)
        scores.append(clamp(50+(hup or 0)*1.5) if p else 50)
    # The sector-wise framework's intrinsic value (valuation.assess), its penalties applied, is the one that counts
    # when there is one; the blend's figures stay alongside for the app.
    fw=num(x.valuation.get("framework_fair_value"))
    if fw and fw>0:
        up=((fw/p)-1)*100 if p else None
        fair.update(base=round(fw,2),upside_pct=round(up,2) if up is not None else None)
        return (clamp(50+up*1.5) if up is not None else 50),fair,[]
    return sum(scores)/len(scores),fair,[]

def score_price(x):
    rows=x.price_history;p=num(x.price) or (num(rows[-1].get("close")) if rows else None);cl=[num(r.get("close")) for r in rows if num(r.get("close")) is not None];hi=[num(r.get("high")) for r in rows if num(r.get("high")) is not None];lo=[num(r.get("low")) for r in rows if num(r.get("low")) is not None]
    if not p or len(cl)<30:return None,None,None,{},["156_week_price_history"]
    window=min(len(cl),780);h=max((hi[-window:] if hi else cl[-window:]));l=min((lo[-window:] if lo else cl[-window:]));pos=(p-l)/(h-l)*100 if h>l else 50;rets=[cl[i]/cl[i-1]-1 for i in range(1,len(cl)) if cl[i-1]>0];vol=statistics.pstdev(rets[-156:])*math.sqrt(252)*100 if len(rets)>5 else None
    ma20=sum(cl[-20:])/min(20,len(cl));ma50=sum(cl[-50:])/min(50,len(cl));ma200=sum(cl[-200:])/min(200,len(cl));price_structure=clamp(35+(20 if p>ma20 else 0)+(20 if p>ma50 else 0)+(25 if p>ma200 else 0));stability=clamp(100-(vol or 50))
    # The nearest closes of the 60 sessions before today below and above the price (the 3-year low or high when none):
    # today's own close counted as both left every stock without support or room above.
    before=cl[-61:-1];gap=p*LEVEL_GAP;support=max([z for z in before if z<p-gap],default=l if l<p-gap else None);resistance=min([z for z in before if z>p+gap],default=h if h>p+gap else None)
    down=(p-support)/p*100 if support is not None else None;up=(resistance-p)/p*100 if resistance is not None else None
    rr=(up/down) if down and up is not None else None
    # Separate risk-quality score: rewards nearby defined support, usable R:R and lower volatility; it is NOT the trend score.
    support_quality=clamp(100-(down if down is not None else 20)*8);rr_quality=clamp((rr or 0)*35);vol_quality=clamp(100-(vol or 50));technical_risk=clamp(support_quality*.4+rr_quality*.35+vol_quality*.25)
    risk={"price":p,"156w_high":h,"156w_low":l,"position_156w_pct":round(pos,2),"annualized_volatility_pct":round(vol,2) if vol is not None else None,"ma20":ma20,"ma50":ma50,"ma200":ma200,"support_proxy":support,"resistance_proxy":resistance,"downside_to_support_pct":round(-down,2) if down is not None else None,"upside_to_resistance_pct":round(up,2) if up is not None else None,"risk_reward_proxy":round(rr,2) if rr is not None else None}
    return price_structure,stability,technical_risk,risk,[]

def analyse_360cr(data:ResearchInput|dict)->ResearchResult:
    x=data if isinstance(data,ResearchInput) else ResearchInput(**data);missing=[];warnings=[];sections={};evidence={}
    fq,gr,ev,m=score_financials(x);sections.update(financial_quality=fq,growth=gr);evidence["financials"]=ev;missing+=m
    cf,bs,ev,m=score_cash_balance(x);sections.update(cashflow=cf,balance_sheet=bs);evidence["cash_balance"]=ev;missing+=m
    own,ev,m=score_ownership(x);sections["ownership"]=own;evidence["ownership"]=ev;missing+=m
    evt,ev,m=score_events(x);sections["insider_deals"]=evt;evidence["events"]=ev;missing+=m
    val,fair,m=score_valuation(x);sections["valuation"]=val;missing+=m
    priceq,stab,riskq,risk,m=score_price(x);sections.update(price_156w=priceq,stability=stab,technical_risk=riskq);missing+=m
    # The framework's universal screen overrides a cheap valuation: a rejected or flagged company's price counts as
    # no bargain, and its verdict is held down below.
    flags=(x.metadata.get("framework") or {}).get("flags") or 0
    if flags&(REJECT|HIGH_RISK|MANIPULATION) and sections.get("valuation") is not None:sections["valuation"]=min(sections["valuation"],40)
    exempt=NOT_FOR_FINANCIALS if x.metadata.get("financial") else set()
    available={k:v for k,v in sections.items() if v is not None};covered=sum(WEIGHTS[k] for k in available);total=sum(w for k,w in WEIGHTS.items() if k not in exempt);score=sum(available[k]*WEIGHTS[k] for k in available)/covered if covered else None;confidence=covered/total*100
    if confidence<60:warnings.append("LOW_COVERAGE: conviction must not be treated as complete")
    state="INSUFFICIENT_DATA" if score is None or confidence<40 else("HIGH_CONVICTION" if score>=75 else "POSITIVE" if score>=62 else "NEUTRAL" if score>=48 else "CAUTION" if score>=35 else "AVOID")
    if state!="INSUFFICIENT_DATA":
        if flags&REJECT:state="AVOID";warnings.append("REJECT: promoter pledge over 15%")
        elif flags&(HIGH_RISK|MANIPULATION) and state in ("HIGH_CONVICTION","POSITIVE","NEUTRAL"):
            state="CAUTION";warnings.append("FLAGGED: interest cover under 2.5 or operating cash under 60% of profit")
    coverage={"score_pct":round(confidence,2),"available_sections":sorted(available),"missing_sections":sorted(set(missing)),"financial_quarters":min(len(x.quarterly_financials),20),"shareholding_quarters":min(len(x.shareholding_quarters),20),"cashflow_quarters":min(len(x.cashflow_quarters),20),"balance_sheet_quarters":min(len(x.balance_sheet_quarters),20),"price_records":len(x.price_history)}
    return ResearchResult(x.symbol,round(score,2) if score is not None else None,round(confidence,2),state,coverage,sections,fair,risk,evidence,warnings,sorted(set(missing)))
def analyse_many_360cr(items):return [analyse_360cr(x).to_dict() for x in items]
