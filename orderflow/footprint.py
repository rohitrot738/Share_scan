"""Price-level footprint aggregation from explicitly classified executed trades."""
from __future__ import annotations
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from typing import Iterable, Literal
Side = Literal["buy", "sell"]
@dataclass(frozen=True)
class Trade:
    symbol: str
    timestamp: datetime
    price: Decimal
    quantity: int
    aggressor: Side
    trade_id: str | None = None
    def __post_init__(self):
        if not self.symbol.strip(): raise ValueError("symbol is required")
        if self.price <= 0: raise ValueError("price must be positive")
        if self.quantity <= 0: raise ValueError("quantity must be positive")
        if self.aggressor not in ("buy", "sell"): raise ValueError("aggressor must be buy or sell")
@dataclass
class PriceLevel:
    price: Decimal
    ask_volume: int = 0
    bid_volume: int = 0
    trades: int = 0
    @property
    def total_volume(self): return self.ask_volume + self.bid_volume
    @property
    def delta(self): return self.ask_volume - self.bid_volume
@dataclass
class FootprintBar:
    symbol: str
    start: datetime
    end: datetime
    levels: dict[Decimal, PriceLevel] = field(default_factory=dict)
    trade_count: int = 0
    @property
    def ask_volume(self): return sum(x.ask_volume for x in self.levels.values())
    @property
    def bid_volume(self): return sum(x.bid_volume for x in self.levels.values())
    @property
    def total_volume(self): return self.ask_volume + self.bid_volume
    @property
    def delta(self): return self.ask_volume - self.bid_volume
    @property
    def delta_pct(self): return 100.0 * self.delta / self.total_volume if self.total_volume else 0.0
    @property
    def poc(self):
        if not self.levels: return None
        return max(self.levels.values(), key=lambda x: (x.total_volume, -x.price)).price
class FootprintBuilder:
    def __init__(self, tick_size: Decimal | str, bar_seconds: int = 60):
        self.tick_size = Decimal(str(tick_size))
        if self.tick_size <= 0: raise ValueError("tick_size must be positive")
        if bar_seconds <= 0: raise ValueError("bar_seconds must be positive")
        self.bar_seconds = bar_seconds
    def normalize_price(self, price: Decimal) -> Decimal:
        ticks = (price / self.tick_size).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
        return (ticks * self.tick_size).quantize(self.tick_size)
    def build(self, trades: Iterable[Trade]) -> list[FootprintBar]:
        bars, seen = {}, set()
        for trade in sorted(trades, key=lambda t: (t.timestamp, t.symbol, t.trade_id or "")):
            if trade.trade_id:
                ident = (trade.symbol, trade.trade_id)
                if ident in seen: continue
                seen.add(ident)
            epoch = int(trade.timestamp.timestamp()); start_epoch = epoch - epoch % self.bar_seconds
            start = datetime.fromtimestamp(start_epoch, tz=trade.timestamp.tzinfo)
            key = (trade.symbol, start)
            bar = bars.setdefault(key, FootprintBar(trade.symbol, start, datetime.fromtimestamp(start_epoch+self.bar_seconds, tz=trade.timestamp.tzinfo)))
            price = self.normalize_price(trade.price)
            level = bar.levels.setdefault(price, PriceLevel(price))
            if trade.aggressor == "buy": level.ask_volume += trade.quantity
            else: level.bid_volume += trade.quantity
            level.trades += 1; bar.trade_count += 1
        return sorted(bars.values(), key=lambda b: (b.start, b.symbol))
