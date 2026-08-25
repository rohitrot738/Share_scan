from cr360.nse_public_adapter import NSEPublicAdapter

def test_rows_shapes():
    a=NSEPublicAdapter(session=None); assert a._rows({"data":[{"x":1}]})==[{"x":1}]; assert a._rows({"records":{"data":[{"x":2}]}})==[{"x":2}]
def test_pick_alias_and_num():
    a=NSEPublicAdapter(); r={"Promoter Holding":"55.2%","FII_FPI":"12.5"}; assert a._num(a._pick(r,"promoterHolding"))==55.2; assert a._num(a._pick(r,"fiiFpi"))==12.5
def test_call_degrades_to_missing_not_fake(monkeypatch):
    a=NSEPublicAdapter();
    monkeypatch.setattr(a,"shareholding",lambda s: (_ for _ in ()).throw(RuntimeError("blocked")))
    monkeypatch.setattr(a,"insiders",lambda s:[]); monkeypatch.setattr(a,"bulk_block",lambda s:[]); monkeypatch.setattr(a,"corporate_actions",lambda s:[])
    p=a("TEST"); assert p["shareholding_quarters"]==[]; assert "shareholding" in p["adapter_errors"]; assert p["coverage"]["shareholding_quarters"]==0
