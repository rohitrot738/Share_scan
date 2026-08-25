from __future__ import annotations
from dataclasses import dataclass, field, asdict
from typing import Any

@dataclass
class ResearchInput:
    symbol: str
    price: float | None = None
    price_history: list[dict] = field(default_factory=list)
    quarterly_financials: list[dict] = field(default_factory=list)
    balance_sheet_quarters: list[dict] = field(default_factory=list)
    cashflow_quarters: list[dict] = field(default_factory=list)
    shareholding_quarters: list[dict] = field(default_factory=list)
    insider_transactions: list[dict] = field(default_factory=list)
    bulk_block_deals: list[dict] = field(default_factory=list)
    corporate_actions: list[dict] = field(default_factory=list)
    valuation: dict = field(default_factory=dict)
    metadata: dict = field(default_factory=dict)

@dataclass
class ResearchResult:
    symbol: str
    score: float | None
    confidence: float
    state: str
    coverage: dict
    sections: dict
    fair_value: dict
    risk: dict
    evidence: dict
    warnings: list[str]
    missing: list[str]
    def to_dict(self) -> dict[str, Any]: return asdict(self)
