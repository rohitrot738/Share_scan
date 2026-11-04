"""The four signals of बड़े पैसे की चाल and their 0–100 score. Pure functions over plain rows.

- Accumulation (चुपचाप खरीद): today's delivery % above 1.8× its average of the 20 sessions before, the price within
  ±2% and over 1,00,000 shares traded.
- Churn (operator churn / जाल): volume over 4× its 20-session average with delivery under 20%.
- Deals: every client's bulk and block deals of the last 30 sessions netted; a run of net buying (or selling) on the
  latest two or more deal days, and the clients who bought and sold within 5 sessions.
- Promoter: the promoters' market buys less their market sells over 90 days; ESOP, off-market, gifts and pledges
  are not trades.
"""
from __future__ import annotations

from collections import namedtuple
from datetime import date, timedelta

SPIKE_RATIO = 1.8
CALM_PCT = 2.0
MIN_VOLUME = 100_000
AVG_SESSIONS = 20
MIN_SESSIONS = 15
RECENT_SESSIONS = 5
CHURN_VOLUME = 4.0
CHURN_DELIVERY = 20.0
DEAL_SESSIONS = 30
STREAK = 2
CIRCULAR_SESSIONS = 5
PROMOTER_DAYS = 90
PROMOTER_FULL_RUPEES = 5e7  # ₹5 crore
PROMOTERS = ("Promoters", "Promoter Group")
MARKET_MODES = ("Market Purchase", "Market Sale")

# Points of each part of the score.
SPIKE_POINTS, BUILD_POINTS, DEAL_POINTS, PROMOTER_POINTS, CHURN_POINTS = 30, 20, 25, 25, 30

Day = namedtuple("Day", "date close prev_close volume deliv_pct")
Ratios = namedtuple("Ratios", "spike build volume change")
DealNet = namedtuple("DealNet", "shares rupees days streak circular")
PromoterNet = namedtuple("PromoterNet", "rupees buys sells")


def _mean(values) -> float | None:
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def _usable(days: list[Day], field: str) -> list[float]:
    """`field` of the 20 sessions before today, those that have it."""
    return [getattr(d, field) for d in days[-AVG_SESSIONS - 1:-1] if getattr(d, field) is not None]


def change(day: Day) -> float | None:
    if day.close is None or not day.prev_close:
        return None
    return (day.close - day.prev_close) / day.prev_close * 100


def ratios(days: list[Day]) -> Ratios:
    """Today's delivery and volume against their 20-session averages, the last 5 sessions' delivery against the
    same average, and today's price change in %; each None without the figures."""
    today = days[-1]
    deliveries, volumes = _usable(days, "deliv_pct"), _usable(days, "volume")
    avg_d = _mean(deliveries) if len(deliveries) >= MIN_SESSIONS else None
    avg_v = _mean(volumes) if len(volumes) >= MIN_SESSIONS else None
    spike = today.deliv_pct / avg_d if avg_d and today.deliv_pct is not None else None
    recent = _mean(d.deliv_pct for d in days[-RECENT_SESSIONS:])
    build = recent / avg_d if avg_d and recent is not None else None
    volume = today.volume / avg_v if avg_v and today.volume is not None else None
    return Ratios(spike, build, volume, change(today))


def accumulation(days: list[Day]) -> bool:
    r, today = ratios(days), days[-1]
    return (r.spike is not None and r.spike > SPIKE_RATIO and r.change is not None and abs(r.change) < CALM_PCT
            and (today.volume or 0) > MIN_VOLUME)


def churn(days: list[Day]) -> bool:
    r, today = ratios(days), days[-1]
    return (r.volume is not None and r.volume > CHURN_VOLUME and today.deliv_pct is not None
            and today.deliv_pct < CHURN_DELIVERY)


def deal_net(rows: list[list], sessions: list[str]) -> DealNet | None:
    """The bulk and block deals ([date, side, rupees, shares, client, kind]) dated within `sessions` (the last 30
    session dates, oldest first) netted: buys add, sells take away. None when there were none."""
    if not sessions:
        return None
    index = {d: i for i, d in enumerate(sessions)}
    rows = [r for r in rows if r and r[0] in index and r[1] in ("buy", "sell") and r[3]]
    if not rows:
        return None
    by_day: dict[str, int] = {}
    shares, rupees = 0, 0.0
    sides: dict[str, dict[str, list[int]]] = {}
    for d, side, value, qty, client, *_ in rows:
        sign = 1 if side == "buy" else -1
        shares += sign * int(qty)
        rupees += sign * float(value or 0)
        by_day[d] = by_day.get(d, 0) + sign * int(qty)
        if client:
            sides.setdefault(client, {"buy": [], "sell": []})[side].append(index[d])
    streak = 0
    for d in sorted(by_day, reverse=True):
        net = by_day[d]
        if net == 0 or (streak and (net > 0) != (streak > 0)):
            break
        streak += 1 if net > 0 else -1
    circular = sorted(c for c, s in sides.items()
                      if any(abs(b - x) < CIRCULAR_SESSIONS for b in s["buy"] for x in s["sell"]))
    return DealNet(shares, rupees, len(by_day), streak, circular)


def promoter_net(rows: list[list], today: date) -> PromoterNet | None:
    """The promoters' market buys less market sells over the 90 days to `today`, from insider rows ([date, side,
    rupees, shares, name, category, mode, ...]); None when there were none. Dates after today (mistyped filings)
    and rows without a category and mode are left out."""
    start = (today - timedelta(days=PROMOTER_DAYS)).isoformat()
    end = today.isoformat()
    rupees, buys, sells = 0.0, 0, 0
    for r in rows:
        if len(r) < 7 or not r[0] or not (start <= r[0] <= end):
            continue
        if r[5] not in PROMOTERS or r[6] not in MARKET_MODES or r[1] not in ("buy", "sell"):
            continue
        if r[1] == "buy":
            rupees += float(r[2] or 0)
            buys += 1
        else:
            rupees -= float(r[2] or 0)
            sells += 1
    return PromoterNet(rupees, buys, sells) if buys or sells else None


def _scale(value: float | None, low: float, high: float) -> float:
    if value is None:
        return 0.0
    return min(1.0, max(0.0, (value - low) / (high - low)))


def score(r: Ratios, deal: DealNet | None, promoter: PromoterNet | None, churned: bool,
          avg_volume: float | None) -> tuple[int, list[float]]:
    """The score, 0–100, and its parts: delivery spike, delivery building up, deals net buying, promoters net
    buying, churn (negative) and net selling by deals or promoters (negative)."""
    spike = SPIKE_POINTS * _scale(r.spike, 1.0, 3.0)
    build = BUILD_POINTS * _scale(r.build, 1.0, 1.5)
    deals = selling = 0.0
    if deal is not None and deal.shares:
        if abs(deal.streak) >= STREAK:
            part = 1.0
        else:
            part = min(1.0, abs(deal.shares) / avg_volume) if avg_volume else 0.0
        if deal.shares > 0:
            deals = DEAL_POINTS * part
        else:
            selling -= DEAL_POINTS * part
    held = 0.0
    if promoter is not None and promoter.rupees:
        part = PROMOTER_POINTS * min(1.0, abs(promoter.rupees) / PROMOTER_FULL_RUPEES)
        if promoter.rupees > 0:
            held = part
        else:
            selling -= part
    penalty = -CHURN_POINTS if churned else 0.0
    parts = [round(p, 2) + 0.0 for p in (spike, build, deals, held, penalty, selling)]
    total = round(sum(parts))
    return int(min(100, max(0, total))), parts


def flags(accumulated: bool, churned: bool, deal: DealNet | None, promoter: PromoterNet | None) -> list[str]:
    out = []
    if accumulated:
        out.append("acc")
    if churned:
        out.append("churn")
    if deal is not None and deal.streak >= STREAK:
        out.append("deal_buy")
    if deal is not None and deal.streak <= -STREAK:
        out.append("deal_sell")
    if promoter is not None and promoter.rupees > 0:
        out.append("promoter_buy")
    if promoter is not None and promoter.rupees < 0:
        out.append("promoter_sell")
    return out
