# 360CR data schema

`ghost_pro/cr360_engine.py` is data-provider agnostic. It expects normalized structured data and can use up to 20 quarters where available.

## Quarterly financial row

```python
{
  "period": "2026Q1",
  "revenue": 0,
  "ebitda": 0,
  "ebit": 0,
  "pat": 0,
  "eps": 0,
  "opm": 0,
  "npm": 0,
  "tax_rate": 0,
  "cfo": 0,
  "capex": 0,
  "fcf": 0,
  "debt": 0,
  "cash": 0,
  "net_debt": 0,
  "equity": 0,
  "assets": 0,
  "receivables": 0,
  "inventory": 0,
  "payables": 0,
  "depreciation": 0,
  "interest": 0,
  "shares": 0,
  "roce": 0,
  "roe": 0,
  "working_capital_days": 0,
  "debtor_days": 0
}
```

## Shareholding row

```python
{
  "period": "2026Q1",
  "promoter": 0,
  "fii": 0,
  "dii": 0,
  "mutual_fund": 0,
  "public": 0,
  "pledge": 0,
  "insider": 0
}
```

The engine evaluates promoter/FII/DII/MF changes across historical quarters, with up to 20 quarters retained.

## Valuation snapshot

```python
{
  "price": 0,
  "pe": 0,
  "pb": 0,
  "ev_ebitda": 0,
  "sector_pe": 0,
  "historical_median_pe": 0,
  "expected_eps_growth": 0,
  "eps_ttm": 0
}
```

## Events / corporate-action context

```python
[
  {"type":"promoter_buy", "direction":"buy", "materiality":1.0},
  {"type":"bulk_buy", "direction":"buy", "materiality":0.7},
  {"type":"rating_upgrade", "materiality":0.5},
  {"type":"large_order", "materiality":0.8}
]
```

Recognized event categories include insider/promoter buy/sell, bulk/block trades, buyback, pledge increase/release, auditor resignation, regulatory adverse events, rating upgrades, large orders and capacity commissioning.

## 360CR output layers

- financial quality
- growth and acceleration
- profitability and margin trend
- balance-sheet leverage and interest coverage
- cash-flow conversion and FCF
- ownership accumulation/distribution
- valuation and fair-value band
- governance/corporate actions
- earnings stability
- red/green flags
- final 0–100 360CR score, grade and conviction

## Fusion with Ghost Trade Pro

`ghost_pro/cr360_fusion.py` keeps technical structure dominant for timing while 360CR tightens conviction. A technically strong setup can be downgraded or vetoed when there is severe cash-flow, leverage, ownership or earnings deterioration. Strong 360CR context can modestly boost a clean technical setup, but cannot override extreme false-breakout risk.

This is deliberately not a guarantee engine. Fair value is a model estimate, and institutional ownership changes must be interpreted with verified source data and corporate-event context.
