"""Transparent descriptive footprint metrics; not trade recommendations."""
from .footprint import FootprintBar
def analyze_footprint(bar: FootprintBar, imbalance_ratio: float = 3.0, min_side_volume: int = 10) -> dict:
    if imbalance_ratio <= 1: raise ValueError("imbalance_ratio must be greater than 1")
    prices = sorted(bar.levels); buys, sells = [], []
    for i, price in enumerate(prices):
        level = bar.levels[price]; below = bar.levels.get(prices[i-1]) if i else None
        above = bar.levels.get(prices[i+1]) if i+1 < len(prices) else None
        if below and level.ask_volume >= min_side_volume and level.ask_volume >= imbalance_ratio * max(1, below.bid_volume): buys.append(str(price))
        if above and level.bid_volume >= min_side_volume and level.bid_volume >= imbalance_ratio * max(1, above.ask_volume): sells.append(str(price))
    total, delta_pct = bar.total_volume, bar.delta_pct
    bias = "NO_DATA" if total == 0 else "BUY_AGGRESSION" if delta_pct >= 15 else "SELL_AGGRESSION" if delta_pct <= -15 else "BALANCED"
    return {"symbol":bar.symbol,"start":bar.start.isoformat(),"end":bar.end.isoformat(),"total_volume":total,"ask_volume":bar.ask_volume,"bid_volume":bar.bid_volume,"delta":bar.delta,"delta_pct":round(delta_pct,2),"poc":str(bar.poc) if bar.poc is not None else None,"buy_imbalances":buys,"sell_imbalances":sells,"bias":bias,"signal_status":"DESCRIPTIVE_ONLY","limitations":["Requires correctly classified aggressor-side executed trades.","Absorption cannot be confirmed from one footprint bar alone.","No institutional identity or hidden orders are inferred."]}
