from datetime import date,timedelta
from cr360 import ResearchInput, analyse_360cr
from cr360.validator import validate_360cr_input

def complete_input(weekly=False):
    q=[{"revenue":100+i*3,"net_profit":10+i*.5,"ebitda":20+i,"eps":2+i*.1} for i in range(20)]
    bs=[{"total_debt":40-i,"equity":100+i*2,"cash":20+i} for i in range(20)]
    cf=[{"operating_cash_flow":12+i*.4,"free_cash_flow":8+i*.3} for i in range(20)]
    sh=[{"promoter":55,"fii_fpi":10+i*.1,"dii":8+i*.05,"mutual_fund":5+i*.05,"promoter_pledge":0} for i in range(20)]
    n=156 if weekly else 780;step=7 if weekly else 1;start=date(2023,1,1)
    ph=[{"date":str(start+timedelta(days=i*step)),"interval":"1wk" if weekly else "1d","open":100+i*.1,"high":102+i*.1,"low":98+i*.1,"close":100+i*.1,"volume":100000+i} for i in range(n)]
    return ResearchInput("TEST",price=ph[-1]["close"],price_history=ph,quarterly_financials=q,balance_sheet_quarters=bs,cashflow_quarters=cf,shareholding_quarters=sh,insider_transactions=[{"side":"buy","value":100}],bulk_block_deals=[{"side":"buy","value":50}],corporate_actions=[{"type":"dividend"}],valuation={"dcf_fair_value":200,"peer_fair_value":190})

def test_complete_daily():
    x=complete_input();v=validate_360cr_input(x);r=analyse_360cr(x)
    assert v["complete"] is True;assert r.score is not None;assert r.confidence==100;assert r.sections["technical_risk"]!=r.sections["price_156w"]

def test_complete_weekly(): assert validate_360cr_input(complete_input(True))["complete"] is True

def test_missing_not_zero_scored():
    r=analyse_360cr(ResearchInput("EMPTY"));assert r.score is None;assert r.state=="INSUFFICIENT_DATA";assert r.confidence==0

def test_negative_and_zero_safe():
    x=complete_input();x.quarterly_financials[0]["revenue"]=0;x.balance_sheet_quarters[-1]["equity"]=-1
    r=analyse_360cr(x);assert r.score is not None

def test_support_not_above_price_and_resistance_not_below():
    r=analyse_360cr(complete_input());assert r.risk["support_proxy"]<=r.risk["price"]<=r.risk["resistance_proxy"]
