from __future__ import annotations

from typing import Any, Dict
from .providers import YahooIndiaProvider, ProviderError
from .cache import JsonCache
from .normalizer import normalize_quarters, derive_financial_metrics, normalize_ownership
from .india_ownership import CompositeIndiaOwnershipProvider


class Auto360Collector:
    """One-call collector that builds a normalized 360CR data packet.

    Market/fundamental data and Indian shareholding history are separate provider
    layers. Missing values are never fabricated.
    """

    def __init__(self, provider=None, cache=None, ownership_provider=None):
        self.provider = provider or YahooIndiaProvider()
        self.cache = cache or JsonCache()
        self.ownership_provider = ownership_provider or CompositeIndiaOwnershipProvider()

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

        # Dedicated Indian shareholding-history layer. It is allowed to fail
        # independently from Yahoo/company holder snapshots.
        try:
            indian_ownership=self.ownership_provider.collect(symbol,exchange,max_quarters=20)
            warnings.extend(indian_ownership.get("warnings",[]) or [])
        except Exception as e:
            indian_ownership={"history":[],"source":"NONE","warnings":[str(e)]}
            warnings.append(f"Indian ownership history unavailable: {e}")

        quarters=normalize_quarters(financials,20)
        metrics=derive_financial_metrics(quarters)
        ownership=normalize_ownership(holders_raw)

        # Dedicated quarter history has priority for 360CR ownership scoring.
        # Yahoo snapshot remains available as auxiliary context.
        ownership_history=indian_ownership.get("history",[]) or []
        ownership["history"] = ownership_history
        ownership["history_source"] = indian_ownership.get("source","NONE")

        packet={
            "symbol":symbol,
            "exchange":exchange,
            "market":market,
            "quarters":quarters,
            "financial_metrics":metrics,
            "ownership":ownership,
            "shareholding_history":ownership_history,
            "raw_financials":financials,
            "raw_holder_snapshot":holders_raw,
            "data_quality":{
                "quarter_count":len(quarters),
                "shareholding_quarter_count":len(ownership_history),
                "has_market_price":market.get("price") is not None,
                "has_ownership_snapshot":bool(holders_raw),
                "has_shareholding_history":bool(ownership_history),
                "ownership_source":indian_ownership.get("source","NONE"),
                "warnings":warnings,
            },
        }
        self.cache.set(key,packet)
        return packet

    def collect_price_history(self, symbol: str, exchange: str = "NSE", period: str = "5y", interval: str = "1d"):
        return self.provider.price_history(symbol,exchange,period,interval)
