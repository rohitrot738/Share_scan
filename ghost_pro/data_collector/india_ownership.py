from __future__ import annotations

"""Indian ownership/shareholding collection layer.

Design goals:
- collect promoter/FII/DII/MF/pledge history when a provider can supply it;
- never fabricate missing values;
- normalize all providers to one quarter-history schema;
- support future NSE/BSE/paid-provider adapters without changing 360CR.

This module intentionally separates provider-specific retrieval from the scoring
engine. Public sites can change HTML/API behaviour, so failures are returned as
warnings and missing fields stay None.
"""

from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional
import math
import re


class OwnershipProviderError(RuntimeError):
    pass


def _num(v: Any) -> Optional[float]:
    try:
        if v is None:
            return None
        if isinstance(v, str):
            s = v.strip().replace(',', '').replace('%', '')
            if not s or s in {'-', '--', 'NA', 'N/A', 'null', 'None'}:
                return None
            v = s
        x = float(v)
        if math.isnan(x) or math.isinf(x):
            return None
        return x
    except Exception:
        return None


def _quarter_key(value: Any) -> str:
    """Best-effort canonical period label, preserving source text if unknown."""
    s = str(value or '').strip()
    if not s:
        return ''
    # Accept labels such as Jun 2026, June-26, 2026-06, 30-06-2026.
    months = {
        'jan':'Mar','feb':'Mar','mar':'Mar',
        'apr':'Jun','may':'Jun','jun':'Jun',
        'jul':'Sep','aug':'Sep','sep':'Sep',
        'oct':'Dec','nov':'Dec','dec':'Dec',
    }
    low = s.lower()
    m = re.search(r'(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[^0-9]*(20\d{2}|\d{2})', low)
    if m:
        y = int(m.group(2))
        if y < 100:
            y += 2000
        return f"{months[m.group(1)[:3]].title()} {y}"
    m = re.search(r'(20\d{2})[^0-9]+(0?[1-9]|1[0-2])', low)
    if m:
        y = int(m.group(1)); mo = int(m.group(2))
        q = 'Mar' if mo <= 3 else 'Jun' if mo <= 6 else 'Sep' if mo <= 9 else 'Dec'
        return f'{q} {y}'
    return s


def normalize_history(rows: Iterable[Dict[str, Any]], max_quarters: int = 20) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for raw in rows or []:
        low = {str(k).lower().strip(): v for k, v in dict(raw).items()}
        period = _quarter_key(low.get('period') or low.get('quarter') or low.get('date') or low.get('as_on'))

        def pick(*names):
            for name in names:
                if name in low:
                    x = _num(low[name])
                    if x is not None:
                        return x
            for k, v in low.items():
                if any(name in k for name in names):
                    x = _num(v)
                    if x is not None:
                        return x
            return None

        row = {
            'period': period,
            'promoter': pick('promoter', 'promoters'),
            'fii': pick('fii', 'fpi', 'foreign institutional', 'foreign portfolio'),
            'dii': pick('dii', 'domestic institutional'),
            'mutual_fund': pick('mutual fund', 'mutual_fund', 'mf'),
            'public': pick('public', 'retail'),
            'pledge': pick('pledge', 'promoter pledge', 'encumbered'),
            'insider': pick('insider'),
            'source': raw.get('source') if isinstance(raw, dict) else None,
        }
        if any(row[k] is not None for k in ['promoter','fii','dii','mutual_fund','public','pledge','insider']):
            out.append(row)

    # Preserve provider order if period parsing is not reliable; cap newest 20.
    return out[-max_quarters:]


@dataclass
class OwnershipPacket:
    symbol: str
    exchange: str
    history: List[Dict[str, Any]]
    source: str
    warnings: List[str]


class CompositeIndiaOwnershipProvider:
    """Composite ownership provider with graceful fallbacks.

    Provider objects may implement:
        history(symbol, exchange='NSE', max_quarters=20) -> list[dict]

    The first provider returning useful normalized rows wins. This lets us plug in
    an exchange adapter, broker API, licensed data vendor, or user-supplied CSV
    without changing downstream code.
    """

    def __init__(self, providers: Optional[List[Any]] = None):
        self.providers = list(providers or [])

    def add_provider(self, provider: Any) -> None:
        self.providers.append(provider)

    def collect(self, symbol: str, exchange: str = 'NSE', max_quarters: int = 20) -> Dict[str, Any]:
        warnings: List[str] = []
        for provider in self.providers:
            name = provider.__class__.__name__
            try:
                rows = provider.history(symbol, exchange=exchange, max_quarters=max_quarters)
                norm = normalize_history(rows, max_quarters=max_quarters)
                if norm:
                    return {
                        'symbol': symbol.upper(),
                        'exchange': exchange.upper(),
                        'history': norm,
                        'source': name,
                        'warnings': warnings,
                    }
                warnings.append(f'{name}: no usable ownership rows')
            except Exception as e:
                warnings.append(f'{name}: {e}')

        return {
            'symbol': symbol.upper(),
            'exchange': exchange.upper(),
            'history': [],
            'source': 'NONE',
            'warnings': warnings + ['ownership history unavailable; no values fabricated'],
        }


class StaticOwnershipProvider:
    """Small adapter for tests/manual injection and future CSV imports."""

    def __init__(self, mapping: Dict[str, List[Dict[str, Any]]]):
        self.mapping = mapping

    def history(self, symbol: str, exchange: str = 'NSE', max_quarters: int = 20):
        key1 = f'{exchange.upper()}:{symbol.upper()}'
        key2 = symbol.upper()
        rows = self.mapping.get(key1, self.mapping.get(key2, []))
        return list(rows)[-max_quarters:]
