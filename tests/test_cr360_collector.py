import pandas as pd
from cr360.models import ResearchInput
from cr360.collector import _records, merge_regulatory

def test_statement_mapping():
    df=pd.DataFrame({"2026Q1":[100,10]},index=["Total Revenue","Net Income"])
    rows=_records(df,{"revenue":["Total Revenue"],"net_profit":["Net Income"]})
    assert rows[-1]["revenue"]==100 and rows[-1]["net_profit"]==10

def test_regulatory_merge_keeps_real_missing_semantics():
    x=ResearchInput("TEST")
    merge_regulatory(x,shareholding=[{"promoter":50}],insiders=[],deals=[],actions=[{"type":"dividend"}],source="NSE")
    assert x.shareholding_quarters[0]["promoter"]==50
    assert x.insider_transactions==[] and x.bulk_block_deals==[]
    assert x.metadata["regulatory_source"]=="NSE"
