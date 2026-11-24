"""Synthetic-only demonstration: python -m orderflow.demo"""
from datetime import datetime, timezone
from decimal import Decimal
from .footprint import Trade, FootprintBuilder
from .signals import analyze_footprint
trades=[Trade("DEMO",datetime(2026,1,1,9,15,1,tzinfo=timezone.utc),Decimal("100.00"),120,"buy","1"),Trade("DEMO",datetime(2026,1,1,9,15,3,tzinfo=timezone.utc),Decimal("100.05"),80,"buy","2"),Trade("DEMO",datetime(2026,1,1,9,15,8,tzinfo=timezone.utc),Decimal("100.00"),35,"sell","3")]
bar=FootprintBuilder("0.05",60).build(trades)[0]
print("SYNTHETIC TEST DATA — NOT LIVE NSE DATA")
print(analyze_footprint(bar))
