import time

from cr360.collector import collect_360cr
from cr360.models import ResearchInput
from cr360.persistent_cache import PersistentResearchCache


def sample(symbol="TEST"):
    return ResearchInput(symbol=symbol, price=123.0,
        price_history=[{"date":"2026-09-01","close":123.0}],
        quarterly_financials=[{"period":"2026Q1","revenue":100}],
        balance_sheet_quarters=[{"period":"2026Q1","equity":50}],
        cashflow_quarters=[{"period":"2026Q1","operating_cash_flow":12}],
        metadata={"source":"test"})


def test_prefilled_cache_is_used_without_provider(tmp_path, monkeypatch):
    cache=PersistentResearchCache(str(tmp_path/"research.sqlite3")); cache.store_research(sample())
    monkeypatch.setattr("cr360.collector.collect_market_and_company", lambda _s: (_ for _ in ()).throw(AssertionError("provider called")))
    result=collect_360cr("TEST",cache=cache)
    assert result.price==123.0 and result.metadata["cache_status"]=="HIT"


def test_stale_cache_survives_provider_failure(tmp_path, monkeypatch):
    cache=PersistentResearchCache(str(tmp_path/"research.sqlite3")); cache.store_research(sample())
    old=time.time()-30*24*60*60
    for section in ("market","financials"):
        cache.put("TEST",section,cache.get("TEST",section)["payload"],saved_at=old)
    monkeypatch.setattr("cr360.collector.collect_market_and_company", lambda _s: (_ for _ in ()).throw(RuntimeError("temporary outage")))
    result=collect_360cr("TEST",cache=cache)
    assert result.price==123.0 and result.metadata["cache_status"]=="STALE_FALLBACK"
    assert "temporary outage" in result.metadata["cache_refresh_error"]


def test_empty_cache_fetches_and_persists(tmp_path, monkeypatch):
    cache=PersistentResearchCache(str(tmp_path/"research.sqlite3"))
    monkeypatch.setattr("cr360.collector.collect_market_and_company",lambda _s:sample())
    assert collect_360cr("TEST",cache=cache).metadata["cache_status"]=="MISS_FETCHED"
    assert collect_360cr("TEST",cache=cache).metadata["cache_status"]=="HIT"


def test_stale_regulatory_refreshes_without_market_fetch(tmp_path, monkeypatch):
    cache=PersistentResearchCache(str(tmp_path/"research.sqlite3")); cache.store_research(sample())
    old=time.time()-2*24*60*60
    for section in ("shareholding","regulatory"):
        cache.put("TEST",section,cache.get("TEST",section)["payload"],saved_at=old)
    monkeypatch.setattr("cr360.collector.collect_market_and_company", lambda _s: (_ for _ in ()).throw(AssertionError("market provider called")))
    result=collect_360cr("TEST",cache=cache,regulatory_adapter=lambda _s:{"shareholding_quarters":[{"period":"2026Q2","promoter":55}],"source":"NSE"})
    assert result.metadata["cache_status"]=="REGULATORY_REFRESHED"
    assert result.shareholding_quarters[-1]["promoter"]==55


def test_market_refresh_preserves_cached_regulatory_data(tmp_path, monkeypatch):
    cache=PersistentResearchCache(str(tmp_path/"research.sqlite3")); value=sample()
    value.shareholding_quarters=[{"period":"2026Q1","promoter":54}]; cache.store_research(value)
    old=time.time()-30*24*60*60
    cache.put("TEST","market",cache.get("TEST","market")["payload"],saved_at=old)
    monkeypatch.setattr("cr360.collector.collect_market_and_company",lambda _s:sample())
    result=collect_360cr("TEST",cache=cache)
    assert result.shareholding_quarters[-1]["promoter"]==54


def test_put_many_writes_all_sections_with_one_timestamp(tmp_path):
    cache=PersistentResearchCache(str(tmp_path/"research.sqlite3"))
    saved_at=time.time()-10
    cache.put_many("TEST", {"market":{"price":123}, "financials":{"revenue":100}}, saved_at=saved_at)
    market=cache.get("TEST","market"); financials=cache.get("TEST","financials")
    assert market["payload"]=={"price":123}
    assert financials["payload"]=={"revenue":100}
    assert market["saved_at"]==financials["saved_at"]==saved_at


def test_stale_market_refresh_does_not_refetch_fresh_financials(tmp_path, monkeypatch):
    cache=PersistentResearchCache(str(tmp_path/"research.sqlite3")); value=sample(); cache.store_research(value)
    old=time.time()-2*60*60
    cache.put("TEST","market",cache.get("TEST","market")["payload"],saved_at=old)
    calls=[]
    monkeypatch.setattr("cr360.collector._refresh_market", lambda _s: (calls.append("market") or ResearchInput(symbol="TEST",price=130.0,price_history=[{"close":130}],valuation={})))
    monkeypatch.setattr("cr360.collector._refresh_financials", lambda _s: (_ for _ in ()).throw(AssertionError("fresh financials refetched")))
    result=collect_360cr("TEST",cache=cache)
    assert calls==["market"] and result.price==130.0
    assert result.quarterly_financials==value.quarterly_financials


def test_stale_financials_refresh_does_not_refetch_fresh_market(tmp_path, monkeypatch):
    cache=PersistentResearchCache(str(tmp_path/"research.sqlite3")); value=sample(); cache.store_research(value)
    old=time.time()-8*24*60*60
    cache.put("TEST","financials",cache.get("TEST","financials")["payload"],saved_at=old)
    monkeypatch.setattr("cr360.collector._refresh_market", lambda _s: (_ for _ in ()).throw(AssertionError("fresh market refetched")))
    monkeypatch.setattr("cr360.collector._refresh_financials", lambda _s: ResearchInput(symbol="TEST",quarterly_financials=[{"period":"2026Q2","revenue":200}],balance_sheet_quarters=[],cashflow_quarters=[]))
    result=collect_360cr("TEST",cache=cache)
    assert result.price==value.price
    assert result.quarterly_financials==[{"period":"2026Q2","revenue":200}]
