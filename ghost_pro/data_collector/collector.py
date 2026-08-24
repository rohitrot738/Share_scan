from __future__ import annotations

from typing import Any, Dict
from .providers import YahooIndiaProvider, ProviderError
from .cache import JsonCache
from .normalizer import normalize_quarters, derive_financial_metrics, normalize_ownership
from .india_ownership import CompositeIndiaOwnershipProvider
from .screener_ownership import ScreenerOwnershipProvider
from .india_events import CompositeIndiaEventProvider


class Auto360Collector:
    """One-call collector that builds a normalized 360CR data packet.

    Market/fundamental data, Indian shareholding history and explicit event data
    are separate provider layers. Missing values are never fabricated.
    """

    def __init__(self, provider=None, cache=None, ownership_provider=None, event_provider=None):
        self.provider = provider or YahooIndiaProvider()
        self.cache = cache or JsonCache()
        self.ownership_provider = ownership_provider or CompositeIndiaOwnershipProvider([
            ScreenerOwnershipProvider()
        ])
        self.event_provider = event_provider or CompositeIndiaEventProvider()

    def collect(self, symbol: str, exchange: str = "NSE", force_refresh: bool = False) -> Dict[str, Any]:
        symbol = symbol.strip().upper()
        exchange = exchange.strip().upper()
        key = f"360cr_{exchange}_{symbol}"
        if not force_refresh:
            cached = self.cache.get(key)
            if cached is not None:
                return cached

        warnings=[]
        try:
            market=self.provider.price_snapshot(symbol,exchange)
        except Exception as e:
            market={"symbol":symbol,"exchange":exchange}
            warnings.append(f"market snapshot unavailable: {e}")
        try:
            financials=self.provider.financials(symbol,exchange,max_periods=20)
        except Exception as e:
            financials={}
            warnings.append(f"financial statements unavailable: {e}")
        try:
            holders_raw=self.provider.holders(symbol,exchange)
        except Exception as e:
            holders_raw={}
            warnings.append(f"holder data unavailable: {e}")

        try:
            indian_ownership=self.ownership_provider.collect(symbol,exchange,max_quarters=20)
            warnings.extend(indian_ownership.get("warnings",[]) or [])
        except Exception as e:
            indian_ownership={"history":[],"source":"NONE","warnings":[str(e)]}
            warnings.append(f"Indian ownership history unavailable: {e}")

        try:
            event_packet=self.event_provider.collect(symbol)
            warnings.extend(event_packet.get("warnings",[]) or [])
        except Exception as e:
            event_packet={"events":[],"pledge_history":[],"sources":[],"warnings":[str(e)]}
            warnings.append(f"Indian event data unavailable: {e}")

        quarters=normalize_quarters(financials,20)
        metrics=derive_financial_metrics(quarters)
        ownership=normalize_ownership(holders_raw)

        ownership_history=indian_ownership.get("history",[]) or []
        pledge_history=event_packet.get("pledge_history",[]) or []

        # Merge pledge observations into matching shareholding periods when possible.
        if ownership_history and pledge_history:
            pledge_map={str(x.get("period")):x.get("pledge_pct") for x in pledge_history if x.get("period")}
            for row in ownership_history:
                period=str(row.get("period"))
                if period in pledge_map and pledge_map[period] is not None:
                    row["pledge_pct"]=pledge_map[period]

        ownership["history"] = ownership_history
        ownership["history_source"] = indian_ownership.get("source","NONE")
        ownership["pledge_history"] = pledge_history

        events=list(event_packet.get("events",[]) or [])

        packet={
            "symbol":symbol,
            "exchange":exchange,
            "market":market,
            "quarters":quarters,
            "financial_metrics":metrics,
            "ownership":ownership,
            "shareholding_history":ownership_history,
            "pledge_history":pledge_history,
            "events":events,
            "event_sources":event_packet.get("sources",[]) or [],
            "raw_financials":financials,
            "raw_holder_snapshot":holders_raw,
            "raw_event_packet":event_packet,
            "data_quality":{
                "quarter_count":len(quarters),
                "shareholding_quarter_count":len(ownership_history),
                "pledge_observation_count":len(pledge_history),
                "event_count":len(events),
                "has_market_price":market.get("price") is not None,
                "has_ownership_snapshot":bool(holders_raw),
                "has_shareholding_history":bool(ownership_history),
                "has_pledge_history":bool(pledge_history),
                "has_event_data":bool(events),
                "ownership_source":indian_ownership.get("source","NONE"),
                "event_sources":event_packet.get("sources",[]) or [],
                "warnings":warnings,
            },
        }
        self.cache.set(key,packet)
        return packet

    def collect_price_history(self, symbol: str, exchange: str = "NSE", period: str = "5y", interval: str = "1d"):
        return self.provider.price_history(symbol,exchange,period,interval)
