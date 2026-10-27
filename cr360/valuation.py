"""The sector-wise valuation framework ("Algorithmic Engine: Valuation Blueprint", the app's backend spec): each
industry valued the way it earns, a margin of safety on top, a universal screen that rejects or flags flawed
companies, and penalties for governance and risk, applied to the intrinsic value before the app shows a price.

- Banks, NBFCs and other financial services: book value less net bad loans per share, times the company's own
  median price-to-book; a buy only with a return on assets over 1%.
- Capital goods, autos and other asset-heavy makers: the company's own median EV/EBITDA on today's EBITDA, less debt,
  per share; 20% off when ROCE is under 12%.
- Metals, mining, oil and gas: the same on mid-cycle EBITDA (the years' average), with a warning that a low P/E there
  is often peak earnings.
- IT, FMCG and pharma: PEG, the P/E over the three-year EPS growth; the intrinsic value is the earnings at the
  company's own usual P/E, but never above a PEG of 2, where the spec calls a stock overvalued.
- The rest: 360CR's own blend (sector P/E and P/B, the company's own P/E).

Pure functions over the store's rows (Screener's figures in ₹ crore; EPS and book value per share in ₹) and the
daily prices, so they are tested without the network.
"""
from __future__ import annotations

from datetime import date, timedelta
from statistics import median

BANK, ASSET, CYCLICAL, CASH, GENERAL = "bank", "asset", "cyclical", "cash", "general"
MODEL_CODE = {GENERAL: 0, BANK: 1, ASSET: 2, CYCLICAL: 3, CASH: 4}

# NSE's industries (data/sectors.json) and the model that values each; the rest are GENERAL.
SECTOR_MODEL = {
    "Financial Services": BANK,
    "Capital Goods": ASSET, "Automobile and Auto Components": ASSET, "Chemicals": ASSET, "Construction": ASSET,
    "Construction Materials": ASSET, "Consumer Durables": ASSET, "Textiles": ASSET, "Forest Materials": ASSET,
    "Power": ASSET, "Telecommunication": ASSET, "Utilities": ASSET,
    "Metals & Mining": CYCLICAL, "Oil Gas & Consumable Fuels": CYCLICAL,
    "Information Technology": CASH, "Fast Moving Consumer Goods": CASH, "Healthcare": CASH,
}

# Margin of safety by size, SEBI's classes by market-cap rank: 1-100 large, 101-250 mid, the rest small. The spec
# gives 20% for large and 35% for small caps; mid caps take the middle.
LARGE_RANK, MID_RANK = 100, 250
MOS = {"large": .20, "mid": .275, "small": .35}

PLEDGE_REJECT = 15.0        # % of the promoters' holding pledged
ICR_RISK = 2.5              # twelve months' (profit before tax + interest) / interest
CFO_PAT_FLAG = .6           # three years' operating cash flow over net profit
ROA_MIN = 1.0               # a lender's twelve months' profit over its assets, %
ROCE_MIN, ROCE_PENALTY = 12.0, .20
DEBT_TO_EQUITY_MAX, DEBT_PENALTY = 1.5, .20
AUDITOR_DAYS, AUDITOR_PENALTY = 365, .40
INSTITUTION_QUARTERS, INSTITUTION_PENALTY = 3, .10
PEG_CHEAP, PEG_DEAR = 1.0, 2.0
MIN_GROWTH, MAX_GROWTH = 5.0, 40.0   # PEG on growth under 5% says nothing; over 40% a year is not kept up
GROWTH_YEARS = 3
HISTORY_MONTHS = 12         # month-ends with a known multiple before the company's own median counts
RESULTS_LAG, BALANCE_LAG = 45, 60    # days after a period ends that its figures are public
PB_RANGE, EV_RANGE = (.3, 10.0), (2.0, 60.0)
EMA_SESSIONS = 200
FVG_SESSIONS, FVG_NEAR = 60, .05     # a bullish gap of the last 60 sessions, price at most 5% over it
SWEEP_WEEKS, PIVOT_WEEKS, SWEEP_RECENT = 52, 2, 2

# The app's flag bits ("flg").
REJECT = 1             # promoter pledge over 15%
HIGH_RISK = 2          # interest cover under 2.5
MANIPULATION = 4       # operating cash under 60% of profit
BUY_ZONE = 8           # price over its 200-day EMA, at an unfilled bullish fair-value gap
CYCLICAL_WARNING = 16  # metals, mining, oil and gas
WEEKLY_SWEEP = 32      # this or last week dipped under a weekly swing low and closed back above it
BEST = 64              # under the buy price while sweeping a weekly demand zone, and nothing flagged
LOW_ROA = 128          # a lender earning under 1% on its assets: no buy
PEG_LOW = 256          # PEG under 1
PEG_HIGH = 512         # PEG over 2
BLOCKING = REJECT | HIGH_RISK | MANIPULATION | LOW_ROA

# The penalty bits ("pf").
P_AUDITOR, P_DEBT, P_INSTITUTIONS, P_ROCE = 1, 2, 4, 8


def _d(s) -> date:
    return date.fromisoformat(str(s)[:10])


def _clamp(v, lo, hi):
    return max(lo, min(hi, v))


def _per_year(quarters: list[dict]) -> int:
    """Rows to a year: four quarters, or two half-years for a half-yearly reporter (most SMEs)."""
    return 2 if len(quarters) >= 2 and (_d(quarters[-1]["period"]) - _d(quarters[-2]["period"])).days > 150 else 4


def ttm(rows: list[dict], key: str) -> float | None:
    """The last twelve months' sum of `key`; None when any of them is missing."""
    n = _per_year(rows)
    vals = [r.get(key) for r in rows[-n:]]
    if len(vals) < n or any(v is None for v in vals):
        return None
    return sum(vals)


def _known(rows: list[dict], day: date, lag: int) -> list[dict]:
    return [r for r in rows if _d(r["period"]) + timedelta(days=lag) <= day]


def month_ends(price_rows: list[dict]) -> list[tuple[date, float]]:
    ends: dict[str, tuple[date, float]] = {}
    for row in price_rows:
        ends[row["date"][:7]] = (_d(row["date"]), row["close"])
    return [ends[k] for k in sorted(ends)]


def shares_cr(stock: dict) -> float | None:
    """Shares in crore: share capital (₹ crore) over face value (₹); market cap over price when either is missing."""
    r = stock.get("ratios") or {}
    bal = (stock.get("balance") or [{}])[-1]
    capital, face = bal.get("capital"), r.get("face_value")
    if capital and face:
        return capital / face
    cap, price = r.get("market_cap_cr"), r.get("price")
    return cap / price if cap and price else None


def size_mos(rank: int | None) -> float:
    """The margin of safety for a company of market-cap rank `rank` (1 the largest); small when unknown."""
    if rank is not None and rank <= LARGE_RANK:
        return MOS["large"]
    if rank is not None and rank <= MID_RANK:
        return MOS["mid"]
    return MOS["small"]


# --- the sector models ----------------------------------------------------------------------------------------------

def bank_value(stock: dict, price_rows: list[dict], sector_pb: float | None = None) -> dict | None:
    """Adjusted price to book: book value per share less the net bad loans per share (net NPA % of the loans, which
    Screener lists in a lender's "Other Assets"), times the company's own median P/B over its price history (the
    sector's when the history is short). ROA: twelve months' profit over total assets."""
    r = stock.get("ratios") or {}
    bvps = r.get("book_value")
    if not bvps or bvps <= 0:
        return None
    balance = stock.get("balance") or []
    quarters = stock.get("quarters") or []
    bal = balance[-1] if balance else {}
    shares = shares_cr(stock)
    npa = next((q["net_npa"] for q in reversed(quarters) if q.get("net_npa") is not None), None)
    loans = bal.get("other_assets")
    npa_ps = npa / 100 * loans / shares if npa is not None and loans and shares else None
    adjusted = bvps - (npa_ps or 0.0)
    pbs = []
    for day, close in month_ends(price_rows):
        known = [b for b in _known(balance, day, BALANCE_LAG) if b.get("equity")]
        if known and shares:
            # Book value per share then on today's share count, as Yahoo's prices are adjusted for splits and bonuses
            # (a bonus issue doubles the capital at the same face value, so the capital then would halve the P/B).
            book = known[-1]["equity"] / shares
            if book > 0:
                pbs.append(close / book)
    own = len(pbs) >= HISTORY_MONTHS
    pb = median(pbs) if own else sector_pb
    profit = ttm(quarters, "net_profit")
    roa = profit / bal["total_assets"] * 100 if profit is not None and bal.get("total_assets") else None
    out = {"bvps": bvps, "adjusted_bvps": adjusted, "net_npa": npa, "roa": roa, "own_history": own}
    if pb:
        pb = _clamp(pb, *PB_RANGE)
        out.update(pb=pb, iv=adjusted * pb)
    return out


def ev_value(stock: dict, price_rows: list[dict], price: float | None, mid_cycle: bool = False) -> dict | None:
    """Enterprise multiple: the company's own median EV/EBITDA over its price history (market value plus debt over
    the twelve months' EBITDA public at each month-end), times today's EBITDA (or, `mid_cycle`, the years' average),
    less today's debt, per share; 20% off when ROCE is under 12%. Cash is not in Screener's summary, so EV here is
    market value plus debt on both sides of the comparison."""
    shares = shares_cr(stock)
    quarters = stock.get("quarters") or []
    balance = stock.get("balance") or []
    ebitda_now = ttm(quarters, "ebitda")
    if mid_cycle:
        years = [a["ebitda"] for a in stock.get("annual") or [] if a.get("ebitda") is not None]
        base = sum(years) / len(years) if years else ebitda_now
    else:
        base = ebitda_now
    if not shares or not base or base <= 0 or not balance:
        return None
    debt = balance[-1].get("total_debt") or 0.0
    multiples = []
    for day, close in month_ends(price_rows):
        e = ttm(_known(quarters, day, RESULTS_LAG), "ebitda")
        known = _known(balance, day, BALANCE_LAG)
        if e and e > 0 and known:
            multiples.append((close * shares + (known[-1].get("total_debt") or 0.0)) / e)
    if len(multiples) < HISTORY_MONTHS:
        return None
    own = _clamp(median(multiples), *EV_RANGE)
    raw = (own * base - debt) / shares
    roce = (stock.get("ratios") or {}).get("roce")
    cut = roce is not None and roce < ROCE_MIN
    out = {"ev_ebitda_own": own, "ebitda": base, "debt": debt, "shares": shares, "roce": roce, "roce_cut": cut,
           "ev_ebitda_now": (price * shares + debt) / ebitda_now if price and ebitda_now and ebitda_now > 0 else None}
    if raw > 0:
        out["iv"] = raw * (1 - ROCE_PENALTY) if cut else raw
    return out


def peg_value(stock: dict, price: float | None, eps: float | None, own_pe: float | None = None,
              sector_pe: float | None = None) -> dict | None:
    """PEG: today's P/E over the yearly EPS growth of the last three years (the expected growth the spec asks for is
    not published freely; the past three years stand in for it). The intrinsic value is the earnings at a fair P/E:
    the company's own usual P/E (its sector's without a history), capped at a PEG of 2, where the spec calls a stock
    overvalued; with neither, the middle of the spec's fair band, a PEG of 1.5. (The price at a PEG of 1 put
    steady growers like TCS and HUL at a third of their price.)"""
    years = [a.get("eps") for a in stock.get("annual") or []]
    if not price or not eps or eps <= 0 or len(years) <= GROWTH_YEARS:
        return None
    first, last = years[-1 - GROWTH_YEARS], years[-1]
    if not first or not last or first <= 0 or last <= 0:
        return None
    growth = ((last / first) ** (1 / GROWTH_YEARS) - 1) * 100
    pe = price / eps
    out = {"pe": pe, "growth": growth}
    if growth >= MIN_GROWTH:
        g = min(growth, MAX_GROWTH)
        base = own_pe or sector_pe
        fair_pe = min(base, PEG_DEAR * g) if base else (PEG_CHEAP + PEG_DEAR) / 2 * g
        out.update(peg=pe / g, base_pe=base, base_sector=not own_pe and bool(sector_pe), pe_cap=PEG_DEAR * g,
                   fair_pe=fair_pe, iv=eps * fair_pe)
    return out


# --- the universal screen, the chart and the penalties ----------------------------------------------------------------

def ema(closes: list[float], n: int = EMA_SESSIONS) -> float | None:
    if len(closes) < n:
        return None
    value = sum(closes[:n]) / n
    k = 2 / (n + 1)
    for c in closes[n:]:
        value += (c - value) * k
    return value


def bullish_gap(price_rows: list[dict], price: float) -> tuple[float, float] | None:
    """The latest unfilled bullish fair-value gap of the last 60 sessions that price sits in or at most 5% over: a
    session whose low is above the high two sessions before it, no low since reaching back into it. (bottom, top)."""
    rows = price_rows[-(FVG_SESSIONS + 2):]
    for i in range(len(rows) - 1, 1, -1):
        bottom, top = rows[i - 2]["high"], rows[i]["low"]
        if top <= bottom:
            continue
        if any(r["low"] <= bottom for r in rows[i + 1:]):
            continue
        if bottom <= price <= top * (1 + FVG_NEAR):
            return bottom, top
    return None


def weekly_bars(price_rows: list[dict]) -> list[dict]:
    weeks: dict[tuple[int, int], dict] = {}
    for row in price_rows:
        key = _d(row["date"]).isocalendar()[:2]
        w = weeks.get(key)
        if w is None:
            weeks[key] = {"high": row["high"], "low": row["low"], "close": row["close"]}
        else:
            w.update(high=max(w["high"], row["high"]), low=min(w["low"], row["low"]), close=row["close"])
    return [weeks[k] for k in sorted(weeks)]


def weekly_sweep(price_rows: list[dict]) -> float | None:
    """A weekly liquidity sweep of a demand zone: this week or last dipped under a weekly swing low of the past year
    (a low under the two weeks either side) and closed back above it. The swing low swept, the highest such."""
    weeks = weekly_bars(price_rows)[-(SWEEP_WEEKS + SWEEP_RECENT):]
    if len(weeks) < 2 * PIVOT_WEEKS + 1 + SWEEP_RECENT:
        return None
    past, recent = weeks[:-SWEEP_RECENT], weeks[-SWEEP_RECENT:]
    lows = [past[i]["low"] for i in range(PIVOT_WEEKS, len(past) - PIVOT_WEEKS)
            if all(past[i]["low"] < past[j]["low"] for j in range(i - PIVOT_WEEKS, i + PIVOT_WEEKS + 1) if j != i)]
    swept = [low for low in lows for w in recent if w["low"] < low < w["close"]]
    return max(swept) if swept else None


def screen(stock: dict, price_rows: list[dict], price: float | None, financial: bool) -> tuple[int, dict]:
    """The universal screen: promoter pledge (reject over 15%), interest cover (high risk under 2.5), operating cash
    against profit (a flag under 60%) and the chart's buy zone (over the 200-day EMA, at a bullish fair-value gap).
    A lender's debt and cash flow are its business, so the two middle checks are not made on it."""
    flags, out = 0, {}
    pledge = stock.get("pledge")
    if pledge is not None:
        out["pledge"] = pledge
        if pledge > PLEDGE_REJECT:
            flags |= REJECT
    if not financial:
        quarters = stock.get("quarters") or []
        interest, pbt = ttm(quarters, "interest"), ttm(quarters, "pbt")
        if interest and interest > 0 and pbt is not None:
            out["icr"] = (pbt + interest) / interest
            if out["icr"] < ICR_RISK:
                flags |= HIGH_RISK
        years = [c for c in stock.get("cashflow") or []
                 if c.get("operating_cash_flow") is not None and c.get("net_profit") is not None][-GROWTH_YEARS:]
        profit = sum(c["net_profit"] for c in years)
        if years and profit > 0:
            out["cfo_pat"] = sum(c["operating_cash_flow"] for c in years) / profit
            if out["cfo_pat"] < CFO_PAT_FLAG:
                flags |= MANIPULATION
    closes = [r["close"] for r in price_rows]
    line = ema(closes)
    if line is not None:
        out["ema200"] = line
        gap = bullish_gap(price_rows, price) if price else None
        if gap:
            out["fvg"] = gap
            if price > line:
                flags |= BUY_ZONE
    return flags, out


def institutions_falling(stock: dict) -> bool:
    """FII and DII holdings together fell in each of the last three quarters."""
    rows = [h for h in stock.get("shareholding") or [] if h.get("fii_fpi") is not None and h.get("dii") is not None]
    totals = [h["fii_fpi"] + h["dii"] for h in rows[-(INSTITUTION_QUARTERS + 1):]]
    return len(totals) == INSTITUTION_QUARTERS + 1 and all(b < a for a, b in zip(totals, totals[1:]))


def penalties(stock: dict, financial: bool, today: date) -> tuple[float, int]:
    """The red-flag multiplier on the intrinsic value: an auditor who resigned in the last year (-40%), debt over
    1.5 times equity (-20%; not for lenders) and institutions selling three quarters running (-10%)."""
    factor, bits = 1.0, 0
    since = today - timedelta(days=AUDITOR_DAYS)
    if any(_d(d) >= since for d in stock.get("auditor_resigned") or []):
        factor *= 1 - AUDITOR_PENALTY
        bits |= P_AUDITOR
    bal = (stock.get("balance") or [{}])[-1]
    if not financial and bal.get("equity") and bal["equity"] > 0 and bal.get("total_debt") is not None:
        if bal["total_debt"] / bal["equity"] > DEBT_TO_EQUITY_MAX:
            factor *= 1 - DEBT_PENALTY
            bits |= P_DEBT
    if institutions_falling(stock):
        factor *= 1 - INSTITUTION_PENALTY
        bits |= P_INSTITUTIONS
    return factor, bits


# --- all of it ------------------------------------------------------------------------------------------------------

def assess(stock: dict, price_rows: list[dict], price: float | None, sector: str, eps: float | None,
           general_iv: float | None, mos: float, today: date, sector_pb: float | None = None,
           own_pe: float | None = None, sector_pe: float | None = None) -> dict:
    """The framework's verdict on one stock: the model that valued it, its intrinsic value before and after the
    penalties, the buy price under the margin of safety, the flags and the figures behind them."""
    model = SECTOR_MODEL.get(sector, GENERAL)
    financial = model == BANK
    detail: dict = {}
    if model == BANK:
        detail = bank_value(stock, price_rows, sector_pb) or {}
    elif model in (ASSET, CYCLICAL):
        detail = ev_value(stock, price_rows, price, mid_cycle=model == CYCLICAL) or {}
    elif model == CASH:
        detail = peg_value(stock, price, eps, own_pe, sector_pe) or {}
    raw, used = detail.get("iv"), model
    if not raw or raw <= 0:
        # The sector's model had too little to go on (a short price history, a loss, growth too slow for PEG): the
        # blend values the company instead, and the penalties and margin of safety still apply.
        raw, used = (general_iv if general_iv and general_iv > 0 else None), GENERAL
    factor, pbits = penalties(stock, financial, today)
    since = (today - timedelta(days=AUDITOR_DAYS)).isoformat()
    resigned = max((d for d in stock.get("auditor_resigned") or [] if d >= since), default=None)
    if used in (ASSET, CYCLICAL) and detail.get("roce_cut"):
        pbits |= P_ROCE
    iv = raw * factor if raw else None
    flags, screened = screen(stock, price_rows, price, financial)
    if model == CYCLICAL:
        flags |= CYCLICAL_WARNING
    if model == BANK and detail.get("roa") is not None and detail["roa"] < ROA_MIN:
        flags |= LOW_ROA
    if detail.get("peg") is not None:
        flags |= PEG_LOW if detail["peg"] < PEG_CHEAP else PEG_HIGH if detail["peg"] > PEG_DEAR else 0
    sweep = weekly_sweep(price_rows)
    if sweep is not None:
        flags |= WEEKLY_SWEEP
    buy = iv * (1 - mos) if iv else None
    if buy and price and price < buy and sweep is not None and not flags & BLOCKING:
        flags |= BEST
    figures = {k: v for k, v in detail.items() if k != "iv"}  # the model's own value is raw_iv here
    return {**figures, **screened, "model": used, "sector_model": model, "raw_iv": raw, "factor": factor,
            "penalties": pbits, "iv": iv, "mos": mos, "buy": buy, "flags": flags, "sweep": sweep, "auditor": resigned}
