"""360CR (360° Conviction Research) engine.

This module scores long-horizon business quality, financial trend, valuation,
ownership behaviour, capital allocation, corporate actions and risk signals.
It is deliberately data-provider agnostic: feed it normalized quarterly,
shareholding, valuation and event data from any reliable source/API.

The engine is designed to be fused with Ghost Trade Pro technical signals so
that a chart setup only receives maximum conviction when business/ownership
context agrees.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any, Dict, Iterable, List, Mapping, Optional
import math
import statistics


def _f(x: Any, default: float = 0.0) -> float:
    try:
        if x is None:
            return default
        v = float(x)
        return default if math.isnan(v) or math.isinf(v) else v
    except Exception:
        return default


def _clip(x: float, lo: float = 0.0, hi: float = 100.0) -> float:
    return max(lo, min(hi, float(x)))


def _pct_change(new: float, old: float) -> float:
    if old == 0:
        return 0.0
    return (new - old) / abs(old) * 100.0


def _cagr(new: float, old: float, years: float) -> float:
    if old <= 0 or new <= 0 or years <= 0:
        return 0.0
    return ((new / old) ** (1.0 / years) - 1.0) * 100.0


def _mean(xs: Iterable[float], default: float = 0.0) -> float:
    a = [float(x) for x in xs if x is not None and math.isfinite(float(x))]
    return statistics.mean(a) if a else default


def _stdev(xs: Iterable[float], default: float = 0.0) -> float:
    a = [float(x) for x in xs if x is not None and math.isfinite(float(x))]
    return statistics.pstdev(a) if len(a) >= 2 else default


def _slope(xs: List[float]) -> float:
    if len(xs) < 2:
        return 0.0
    n = len(xs)
    xbar = (n - 1) / 2.0
    ybar = sum(xs) / n
    num = sum((i - xbar) * (v - ybar) for i, v in enumerate(xs))
    den = sum((i - xbar) ** 2 for i in range(n))
    return num / den if den else 0.0


def _latest(rows: List[Mapping[str, Any]], key: str, default: float = 0.0) -> float:
    if not rows:
        return default
    return _f(rows[-1].get(key), default)


def _series(rows: List[Mapping[str, Any]], key: str) -> List[float]:
    return [_f(r.get(key)) for r in rows if r.get(key) is not None]


@dataclass
class CR360Weights:
    financial_quality: float = 0.21
    growth: float = 0.15
    profitability: float = 0.12
    balance_sheet: float = 0.12
    cash_flow: float = 0.10
    ownership: float = 0.11
    valuation: float = 0.10
    governance_actions: float = 0.05
    stability: float = 0.04


@dataclass
class CR360Decision:
    symbol: str
    score: float
    grade: str
    conviction: str
    fundamental_bias: str
    fair_value_low: float
    fair_value_mid: float
    fair_value_high: float
    margin_of_safety_pct: float
    ownership_bias: str
    earnings_trend: str
    cashflow_flag: str
    balance_sheet_flag: str
    red_flag_count: int
    green_flag_count: int


def normalize_quarters(rows: List[Mapping[str, Any]], max_quarters: int = 20) -> List[Dict[str, Any]]:
    """Return oldest->newest normalized quarter rows, capped at 20 by default."""
    rows = list(rows or [])[-max_quarters:]
    keys = [
        'period','revenue','ebitda','ebit','pat','eps','opm','npm','tax_rate',
        'cfo','capex','fcf','debt','cash','net_debt','equity','assets',
        'receivables','inventory','payables','depreciation','interest',
        'shares','roce','roe','working_capital_days','debtor_days'
    ]
    out=[]
    for r in rows:
        d={k:r.get(k) for k in keys}
        out.append(d)
    return out


def financial_quality_score(q: List[Mapping[str, Any]]) -> Dict[str, Any]:
    rev=_series(q,'revenue'); pat=_series(q,'pat'); eps=_series(q,'eps')
    opm=_series(q,'opm'); npm=_series(q,'npm')
    if len(q)<4:
        return {'score':35.0,'reasons':['insufficient quarterly history']}

    rev_growth = _pct_change(_mean(rev[-4:]), _mean(rev[-8:-4])) if len(rev)>=8 else _pct_change(rev[-1],rev[0])
    pat_growth = _pct_change(_mean(pat[-4:]), _mean(pat[-8:-4])) if len(pat)>=8 else _pct_change(pat[-1],pat[0])
    eps_growth = _pct_change(_mean(eps[-4:]), _mean(eps[-8:-4])) if len(eps)>=8 else 0.0

    rev_consistency = max(0.0, 100.0 - min(100.0, _stdev([_pct_change(rev[i],rev[i-1]) for i in range(1,len(rev))]) * 1.6)) if len(rev)>2 else 50
    pat_consistency = max(0.0, 100.0 - min(100.0, _stdev([_pct_change(pat[i],pat[i-1]) for i in range(1,len(pat))]) * 0.9)) if len(pat)>2 else 45
    margin_consistency = max(0.0, 100.0 - min(100.0, _stdev(opm)*7.0)) if opm else 45

    growth_component = _clip(50 + 0.8*rev_growth + 0.5*pat_growth + 0.25*eps_growth)
    score = 0.38*growth_component + 0.22*rev_consistency + 0.22*pat_consistency + 0.18*margin_consistency
    reasons=[]
    if rev_growth>12: reasons.append('revenue growth healthy')
    if pat_growth>15: reasons.append('profit growth healthy')
    if pat_growth<-10: reasons.append('profit trend deteriorating')
    if margin_consistency<45: reasons.append('margins unstable')
    return {'score':round(_clip(score),2),'rev_growth_yoy_like':round(rev_growth,2),'pat_growth_yoy_like':round(pat_growth,2),'eps_growth_yoy_like':round(eps_growth,2),'reasons':reasons}


def growth_score(q: List[Mapping[str, Any]]) -> Dict[str, Any]:
    rev=_series(q,'revenue'); pat=_series(q,'pat')
    if len(rev)<5:
        return {'score':40.0,'reasons':['short history']}
    rev_slope=_slope(rev)/max(abs(_mean(rev)),1e-9)*100
    pat_slope=_slope(pat)/max(abs(_mean(pat)),1e-9)*100 if pat else 0
    recent_accel=0.0
    if len(rev)>=8:
        recent=_pct_change(_mean(rev[-4:]),_mean(rev[-8:-4]))
        older=_pct_change(_mean(rev[-8:-4]),_mean(rev[-12:-8])) if len(rev)>=12 else 0
        recent_accel=recent-older
    score=_clip(50 + rev_slope*7 + pat_slope*4 + recent_accel*0.7)
    return {'score':round(score,2),'revenue_slope':round(rev_slope,3),'profit_slope':round(pat_slope,3),'growth_acceleration':round(recent_accel,2),'reasons':[]}


def profitability_score(q: List[Mapping[str, Any]]) -> Dict[str, Any]:
    opm=_series(q,'opm'); npm=_series(q,'npm'); roce=_series(q,'roce'); roe=_series(q,'roe')
    opm_now=_mean(opm[-4:]) if opm else 0; npm_now=_mean(npm[-4:]) if npm else 0
    roce_now=_mean(roce[-4:]) if roce else 0; roe_now=_mean(roe[-4:]) if roe else 0
    margin_trend=_slope(opm[-8:]) if len(opm)>=4 else 0
    score=_clip(30 + min(opm_now,30)*1.0 + min(npm_now,25)*0.8 + min(roce_now,30)*0.7 + min(roe_now,30)*0.5 + margin_trend*3)
    reasons=[]
    if margin_trend>0.2: reasons.append('operating margin improving')
    if margin_trend<-0.2: reasons.append('operating margin compressing')
    if roce_now>=18: reasons.append('strong capital efficiency')
    return {'score':round(score,2),'opm_recent':round(opm_now,2),'npm_recent':round(npm_now,2),'roce_recent':round(roce_now,2),'roe_recent':round(roe_now,2),'margin_trend':round(margin_trend,3),'reasons':reasons}


def balance_sheet_score(q: List[Mapping[str, Any]]) -> Dict[str, Any]:
    debt=_latest(q,'debt'); cash=_latest(q,'cash'); equity=_latest(q,'equity'); interest=_latest(q,'interest'); ebit=_latest(q,'ebit')
    net_debt=_latest(q,'net_debt',debt-cash)
    de=max(0.0,debt/max(equity,1e-9)) if equity>0 else 5.0
    ic=ebit/max(interest,1e-9) if interest>0 else 99.0
    debt_series=_series(q,'debt'); debt_trend=_slope(debt_series[-8:]) if debt_series else 0
    base=92 - min(de,3)*24
    if ic<2: base-=28
    elif ic<4: base-=14
    if net_debt<0: base+=8
    if debt_trend>0 and debt>cash: base-=6
    reasons=[]
    if de<0.5: reasons.append('low leverage')
    if net_debt<0: reasons.append('net cash balance sheet')
    if ic<2: reasons.append('weak interest coverage')
    if debt_trend>0: reasons.append('debt rising')
    return {'score':round(_clip(base),2),'debt_to_equity':round(de,3),'interest_cover':round(ic,2),'net_debt':round(net_debt,2),'debt_trend':round(debt_trend,2),'reasons':reasons}


def cashflow_score(q: List[Mapping[str, Any]]) -> Dict[str, Any]:
    cfo=_series(q,'cfo'); pat=_series(q,'pat'); fcf=_series(q,'fcf'); capex=_series(q,'capex')
    if not cfo:
        return {'score':35.0,'reasons':['cash-flow data unavailable']}
    cfo4=sum(cfo[-4:]); pat4=sum(pat[-4:]) if pat else 0; fcf4=sum(fcf[-4:]) if fcf else cfo4-sum(capex[-4:])
    conversion=cfo4/max(abs(pat4),1e-9) if pat4!=0 else 0
    positive_ratio=sum(1 for x in cfo if x>0)/len(cfo)
    fcf_positive_ratio=sum(1 for x in fcf if x>0)/len(fcf) if fcf else (1.0 if fcf4>0 else 0.0)
    score=_clip(25 + min(max(conversion,0),1.5)*35 + positive_ratio*25 + fcf_positive_ratio*15)
    reasons=[]
    if conversion>=0.8: reasons.append('profit backed by operating cash')
    if conversion<0.5: reasons.append('weak cash conversion')
    if fcf4<0: reasons.append('recent free cash flow negative')
    return {'score':round(score,2),'cfo_pat_conversion':round(conversion,3),'recent_cfo':round(cfo4,2),'recent_fcf':round(fcf4,2),'positive_cfo_ratio':round(positive_ratio,3),'reasons':reasons}


def ownership_score(rows: List[Mapping[str, Any]], max_quarters: int = 20) -> Dict[str, Any]:
    rows=list(rows or [])[-max_quarters:]
    if not rows:
        return {'score':45.0,'bias':'UNKNOWN','reasons':['shareholding history unavailable']}
    fields=['promoter','fii','dii','mutual_fund','public','pledge','insider']
    ser={k:[_f(r.get(k)) for r in rows if r.get(k) is not None] for k in fields}
    def delta(k,n=4):
        a=ser[k]
        if len(a)<2:return 0.0
        i=max(0,len(a)-1-n)
        return a[-1]-a[i]
    promoter_delta=delta('promoter'); fii_delta=delta('fii'); dii_delta=delta('dii'); mf_delta=delta('mutual_fund'); pledge=_latest(rows,'pledge')
    institutional=fii_delta+dii_delta+mf_delta
    score=55 + promoter_delta*4 + institutional*2.0 - max(0,pledge)*2.2
    # Reward stable high promoter ownership without pledge.
    promoter_now=_latest(rows,'promoter')
    if promoter_now>=50 and pledge<=0.01: score+=8
    # Penalize broad institutional exit.
    if fii_delta<-2 and dii_delta<0: score-=10
    bias='ACCUMULATION' if score>=65 else ('DISTRIBUTION' if score<45 else 'NEUTRAL')
    reasons=[]
    if promoter_delta>0.25: reasons.append('promoter stake rising')
    if fii_delta>0.5: reasons.append('FII accumulation')
    if fii_delta<-0.5: reasons.append('FII reduction')
    if dii_delta>0.5 or mf_delta>0.5: reasons.append('domestic institutional accumulation')
    if pledge>0: reasons.append('promoter pledge present')
    return {'score':round(_clip(score),2),'bias':bias,'promoter_delta':round(promoter_delta,3),'fii_delta':round(fii_delta,3),'dii_delta':round(dii_delta,3),'mf_delta':round(mf_delta,3),'pledge':round(pledge,3),'reasons':reasons}


def governance_action_score(events: List[Mapping[str, Any]]) -> Dict[str, Any]:
    score=60.0; reasons=[]
    for e in events or []:
        typ=str(e.get('type','')).lower(); direction=str(e.get('direction','')).lower(); material=_f(e.get('materiality'),1.0)
        if typ in {'insider_buy','promoter_buy','buyback'}: score += 5*material; reasons.append(typ)
        elif typ in {'insider_sell','promoter_sell'}: score -= 4*material; reasons.append(typ)
        elif typ in {'pledge_increase','auditor_resignation','regulatory_adverse','fraud'}: score -= 12*material; reasons.append(typ)
        elif typ in {'pledge_release','rating_upgrade','large_order','capacity_commissioned'}: score += 4*material; reasons.append(typ)
        elif typ in {'bulk_buy','block_buy'} and direction!='sell': score += 2.5*material; reasons.append(typ)
        elif typ in {'bulk_sell','block_sell'} or direction=='sell': score -= 2.5*material; reasons.append(typ)
    return {'score':round(_clip(score),2),'reasons':reasons}


def valuation_score(v: Mapping[str, Any], q: List[Mapping[str, Any]]) -> Dict[str, Any]:
    price=_f(v.get('price')); pe=_f(v.get('pe')); pb=_f(v.get('pb')); ev_ebitda=_f(v.get('ev_ebitda'))
    sector_pe=_f(v.get('sector_pe')); hist_pe=_f(v.get('historical_median_pe')); growth=_f(v.get('expected_eps_growth'))
    anchors=[x for x in [sector_pe,hist_pe] if x>0]
    anchor=_mean(anchors, pe if pe>0 else 20)
    relative=anchor/max(pe,1e-9) if pe>0 else 1.0
    peg=pe/max(growth,1e-9) if growth>0 and pe>0 else 2.0
    score=50 + (relative-1)*35
    if peg<1: score+=15
    elif peg<1.5: score+=8
    elif peg>2.5: score-=12
    if pb>8: score-=8
    if ev_ebitda>35: score-=8
    score=_clip(score)
    eps_ttm=_f(v.get('eps_ttm'))
    if eps_ttm<=0:
        eps=_series(q,'eps'); eps_ttm=sum(eps[-4:]) if eps else 0
    quality_multiple=max(8.0,min(45.0,anchor if anchor>0 else 20.0))
    # Three-point fair value band. Not a guarantee; valuation prior only.
    fair_mid=max(0.0,eps_ttm*quality_multiple)
    fair_low=fair_mid*0.82
    fair_high=fair_mid*1.18
    mos=_pct_change(fair_mid,price) if price>0 else 0
    reasons=[]
    if relative>1.15: reasons.append('discount to valuation anchor')
    if relative<0.8: reasons.append('premium to valuation anchor')
    if peg<1.5: reasons.append('growth-adjusted valuation supportive')
    return {'score':round(score,2),'fair_value_low':round(fair_low,2),'fair_value_mid':round(fair_mid,2),'fair_value_high':round(fair_high,2),'margin_of_safety_pct':round(mos,2),'pe':round(pe,2),'pb':round(pb,2),'anchor_pe':round(anchor,2),'peg':round(peg,2),'reasons':reasons}


def stability_score(q: List[Mapping[str, Any]]) -> Dict[str, Any]:
    rev=_series(q,'revenue'); pat=_series(q,'pat'); opm=_series(q,'opm')
    def variability(a):
        if len(a)<3:return 50.0
        m=abs(_mean(a)); return _clip(100-(100*_stdev(a)/max(m,1e-9))*1.8)
    score=0.34*variability(rev)+0.36*variability(pat)+0.30*variability(opm)
    return {'score':round(_clip(score),2),'revenue_stability':round(variability(rev),2),'profit_stability':round(variability(pat),2),'margin_stability':round(variability(opm),2),'reasons':[]}


def collect_flags(parts: Mapping[str, Mapping[str, Any]]) -> Dict[str, List[str]]:
    green=[]; red=[]
    for name,p in parts.items():
        score=_f(p.get('score'),50)
        for reason in p.get('reasons',[]) or []:
            text=f'{name}: {reason}'
            if any(w in reason for w in ['weak','deteriorating','compressing','negative','rising','reduction','pledge present','premium','distribution','unavailable','insufficient']): red.append(text)
            else: green.append(text)
        if score>=75: green.append(f'{name}: high score {score:.1f}')
        if score<40: red.append(f'{name}: low score {score:.1f}')
    return {'green':green,'red':red}


def analyse_360cr(symbol: str,
                  quarters: List[Mapping[str, Any]],
                  shareholding: Optional[List[Mapping[str, Any]]] = None,
                  valuation: Optional[Mapping[str, Any]] = None,
                  events: Optional[List[Mapping[str, Any]]] = None,
                  weights: Optional[CR360Weights] = None) -> Dict[str, Any]:
    q=normalize_quarters(quarters)
    w=weights or CR360Weights()
    parts={
        'financial_quality':financial_quality_score(q),
        'growth':growth_score(q),
        'profitability':profitability_score(q),
        'balance_sheet':balance_sheet_score(q),
        'cash_flow':cashflow_score(q),
        'ownership':ownership_score(shareholding or []),
        'valuation':valuation_score(valuation or {},q),
        'governance_actions':governance_action_score(events or []),
        'stability':stability_score(q),
    }
    wm=asdict(w)
    score=sum(_f(parts[k]['score'])*wm[k] for k in wm)/max(sum(wm.values()),1e-9)
    flags=collect_flags(parts)
    # Hard risk penalties: cash conversion, leverage, pledge, severe profit decay.
    penalty=0.0
    if _f(parts['cash_flow'].get('cfo_pat_conversion'))<0.35: penalty+=7
    if _f(parts['balance_sheet'].get('debt_to_equity'))>2: penalty+=8
    if _f(parts['ownership'].get('pledge'))>20: penalty+=8
    if _f(parts['financial_quality'].get('pat_growth_yoy_like'))<-25: penalty+=8
    score=_clip(score-penalty)
    grade='A+' if score>=85 else 'A' if score>=78 else 'B+' if score>=70 else 'B' if score>=62 else 'C' if score>=52 else 'D'
    conviction='HIGH' if score>=78 and len(flags['red'])<=2 else 'MEDIUM' if score>=62 else 'LOW'
    bias='BULLISH' if score>=72 else 'NEUTRAL' if score>=55 else 'BEARISH'
    earnings='IMPROVING' if _f(parts['growth'].get('growth_acceleration'))>2 and _f(parts['financial_quality'].get('pat_growth_yoy_like'))>0 else ('DETERIORATING' if _f(parts['financial_quality'].get('pat_growth_yoy_like'))<-10 else 'MIXED')
    cf='STRONG' if parts['cash_flow']['score']>=70 else 'WEAK' if parts['cash_flow']['score']<45 else 'NORMAL'
    bs='STRONG' if parts['balance_sheet']['score']>=75 else 'RISKY' if parts['balance_sheet']['score']<45 else 'NORMAL'
    vv=parts['valuation']; own=parts['ownership']
    d=CR360Decision(symbol=symbol,score=round(score,2),grade=grade,conviction=conviction,fundamental_bias=bias,fair_value_low=_f(vv.get('fair_value_low')),fair_value_mid=_f(vv.get('fair_value_mid')),fair_value_high=_f(vv.get('fair_value_high')),margin_of_safety_pct=_f(vv.get('margin_of_safety_pct')),ownership_bias=str(own.get('bias','UNKNOWN')),earnings_trend=earnings,cashflow_flag=cf,balance_sheet_flag=bs,red_flag_count=len(flags['red']),green_flag_count=len(flags['green']))
    return {'decision':asdict(d),'components':parts,'green_flags':flags['green'],'red_flags':flags['red'],'quarters_used':len(q),'shareholding_quarters_used':min(len(shareholding or []),20),'penalty':round(penalty,2)}
