from __future__ import annotations

from typing import Any, Dict
from .providers import YahooIndiaProvider, ProviderError
from .cache import JsonCache
from .normalizer import normalize_quarters, derive_financial_metrics, normalize_ownership


class Auto360Collector:
    """One-call collector that builds a normalized 360CR data packet.

    The provider is replaceable. No missing ownership/fundamental value is fabricated.
    """

    def __init__(self, provider=None, cache=None):
        self.provider = provider or YahooIndiaProvider()
        self.cache = cache or JsonCache()

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

        quarters=normalize_quarters(financials,20)
        metrics=derive_financial_metrics(quarters)
        ownership=normalize_ownership(holders_raw)

        packet={
            "symbol":symbol,
            "exchange":exchange,
            "market":market,
            "quarters":quarters,
            "financial_metrics":metrics,
            "ownership":ownership,
            "raw_financials":financials,
            "data_quality":{
                "quarter_count":len(quarters),
                "has_market_price":market.get("price") is not None,
                "has_ownership":bool(holders_raw),
                "warnings":warnings,
            },
        }
        self.cache.set(key,packet)
        return packet

    def collect_price_history(self, symbol: str, exchange: str = "NSE", period: str = "5y", interval: str = "1d"):
        return self.provider.price_history(symbol,exchange,period,interval)
