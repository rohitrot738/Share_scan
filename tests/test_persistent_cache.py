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
