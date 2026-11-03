"""Swing (2 days to 4 weeks) and long-term (1 to 5+ years) tools for NSE/BSE stocks, used by the /short-* and
/long-* Claude skills (.claude/skills/short-*, long-*/SKILL.md). Built on tools/trader.py's data and calculations.

    python3 tools/invest.py short-scanner [SYMBOL ...]
    python3 tools/invest.py short-setup SYMBOL
    python3 tools/invest.py short-risk --entry E (--stop S | --symbol SYM) [--capital C] [--risk-pct 1.5] [--open N]
    python3 tools/invest.py short-catalysts SYMBOL [--bse-code CODE] [--days 90]
    python3 tools/invest.py short-broker SYMBOL --qty N --entry E --stop S --target T [--side BUY]
    python3 tools/invest.py long-screener [--top 30] [--strict]
    python3 tools/invest.py long-valuation SYMBOL
    python3 tools/invest.py long-moat SYMBOL
    python3 tools/invest.py long-portfolio --capital C [--core A,B] [--growth C,D] [--split 60,30,10]
    python3 tools/invest.py long-rebalance --holdings FILE.csv [--band 5]

Prices come from Yahoo's chart; fundamentals, shareholding, insider trades and the valuation framework from the app's
nightly R/S files (cr360-data release); filings from BSE's announcements, as the app's News reads them. A figure the
data does not have is reported as unknown, never guessed. Nothing here places an order or is investment or tax advice.
"""
import argparse
import csv
import gzip
import json
import math
import os
import re
import sys
import time
import urllib.parse
from datetime import date, datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trader  # noqa: E402
from trader import DataError, IST, show  # noqa: E402

SWING = {"capital": 100000.0, "risk_pct": 1.5, "max_risk_pct": 2.0, "max_position_pct": 25.0, "max_open": 5,
         "target_r": 2.5, "atr_stop": 2.0, "trail_atr": 3.0}
WATCHLIST = ["RELIANCE", "TATASTEEL", "BEL", "HAL", "TRENT", "POLYCAB", "DIXON", "BHARTIARTL", "SBIN", "TITAN",
             "PERSISTENT", "COFORGE", "BSE", "MCX", "ETERNAL"]
LONG = {"min_roce": 15.0, "min_roe": 15.0, "max_de": 0.5, "min_profit_cagr": 12.0, "min_promoter": 50.0, "max_pledge": 0.0}
TAX = {"stcg": 20.0, "ltcg": 12.5, "ltcg_exempt": 125000.0, "long_after_days": 365}  # listed equity, Budget 2024 rates
BSE_API = "https://api.bseindia.com/BseIndiaAPI/api/"


# ---------------------------------------------------------------- indicators

def sma(values: list[float], n: int) -> float | None:
    return sum(values[-n:]) / n if len(values) >= n else None


def ema_series(values: list[float], n: int) -> list[float]:
    """Exponential moving average, seeded with the simple average of the first [n] values; empty when too short."""
    if len(values) < n:
        return []
    k = 2 / (n + 1)
    out = [sum(values[:n]) / n]
    for v in values[n:]:
        out.append(v * k + out[-1] * (1 - k))
    return out


def atr(bars: list[dict], n: int = 14) -> float | None:
    """Wilder's average true range of the last [n] candles."""
    if len(bars) < n + 1:
        return None
    trs = [max(b["high"] - b["low"], abs(b["high"] - a["close"]), abs(b["low"] - a["close"])) for a, b in zip(bars, bars[1:])]
    value = sum(trs[:n]) / n
    for tr in trs[n:]:
        value = (value * (n - 1) + tr) / n
    return value


def swing_points(bars: list[dict], side: int = 5) -> tuple[list[float], list[float]]:
    """Swing highs and lows: a candle higher (lower) than the [side] candles on each side of it."""
    highs, lows = [], []
    for i in range(side, len(bars) - side):
        window = bars[i - side:i + side + 1]
        if bars[i]["high"] == max(b["high"] for b in window):
            highs.append(bars[i]["high"])
        if bars[i]["low"] == min(b["low"] for b in window):
            lows.append(bars[i]["low"])
    return highs, lows


# ---------------------------------------------------------------- swing: scanner and setup

def swing_signals(bars: list[dict]) -> dict:
    """The swing scanner's signals on daily candles (at least about a year of them for the 52-week ones)."""
    if len(bars) < 60:
        raise DataError(f"only {len(bars)} daily candles; the scanner needs about a year")
    closes = [b["close"] for b in bars]
    vols = [b["volume"] for b in bars]
    c, prev = closes[-1], closes[-2]
    e20, e50 = ema_series(closes, 20), ema_series(closes, 50)
    s150, s200 = sma(closes, 150), sma(closes, 200)
    s200_then = sma(closes[:-20], 200)
    year = bars[-252:-1]
    hi52, lo52 = max(b["high"] for b in year), min(b["low"] for b in year)
    avg50 = sma(vols[:-1], 50) or 0
    out = {"cmp": round(c, 2), "ema20": round(e20[-1], 2) if e20 else None, "ema50": round(e50[-1], 2) if e50 else None,
           "sma200": round(s200, 2) if s200 else None, "high_52w": round(hi52, 2), "low_52w": round(lo52, 2), "signals": []}
    if len(bars) >= 200 and c >= hi52:
        out["signals"].append("52W_BREAKOUT")
    if s150 and s200 and s200_then and c > s150 > s200 and s200 > s200_then and c >= 0.75 * hi52 and c >= 1.3 * lo52:
        out["signals"].append("STAGE_2_UPTREND")
    if e20 and e50 and e20[-1] > e50[-1] and c > e50[-1]:
        touched = [m for m, line in (("20", e20[-1]), ("50", e50[-1]))
                   if min(b["low"] for b in bars[-3:]) <= line * 1.015 and c >= line * 0.985]
        if touched:
            out["signals"].append(f"EMA{touched[0]}_PULLBACK")
    if avg50 and (sma(vols, 5) or 0) < 0.6 * avg50:
        out["signals"].append("VOLUME_DRY_UP")
    downs = [b["volume"] for a, b in zip(bars[-12:-1], bars[-11:]) if b["close"] < a["close"]]
    if c > prev and downs and vols[-1] > max(downs):
        out["signals"].append("POCKET_PIVOT")
    hi20 = max(b["high"] for b in bars[-21:-1])
    if c > hi20 and avg50 and vols[-1] > 1.5 * avg50:
        out["signals"].append("HIGH_VOLUME_20D_BREAKOUT")
    out["volume_vs_50d"] = round(vols[-1] / avg50, 2) if avg50 else None
    return out


def trend(closes: list[float], fast: int, slow: int) -> str:
    f, s = ema_series(closes, fast), ema_series(closes, slow)
    if not f or not s or len(f) < 6:
        return "UNKNOWN"
    if closes[-1] > f[-1] > s[-1] and f[-1] > f[-6]:
        return "UP"
    if closes[-1] < f[-1] < s[-1] and f[-1] < f[-6]:
        return "DOWN"
    return "SIDEWAYS"


def swing_setup(daily: list[dict], weekly: list[dict]) -> dict:
    closes = [b["close"] for b in daily]
    c = closes[-1]
    week, day = trend([b["close"] for b in weekly], 10, 30), trend(closes, 20, 50)
    highs, lows = swing_points(daily[-160:])
    a = atr(daily)
    return {
        "cmp": round(c, 2), "weekly_trend": week, "daily_trend": day,
        "timeframes": "ALIGNED_UP" if week == day == "UP" else "ALIGNED_DOWN" if week == day == "DOWN" else "MIXED",
        "atr14": round(a, 2) if a else None,
        "support": sorted({round(x, 2) for x in lows if x < c}, reverse=True)[:2],
        "resistance": sorted({round(x, 2) for x in highs if x > c})[:2],
        "order_blocks": [o for o in trader.order_blocks(daily, 120) if not o["mitigated"]][-4:],
        "fair_value_gaps": [g for g in trader.fair_value_gaps(daily, 120) if not g["filled"]][-4:],
    }


# ---------------------------------------------------------------- swing: risk and tickets

def swing_position(capital: float, entry: float, stop: float, risk_pct: float | None = None, risk_rs: float | None = None,
                   open_positions: int = 0, rules: dict = SWING) -> dict:
    """Delivery (CNC) size: the risk budget (at most [rules] max_risk_pct of capital) over the risk a share, capped
    at max_position_pct of capital in one stock; the target at target_r times the risk."""
    per_share = entry - stop
    if per_share <= 0:
        raise DataError("for a swing buy the stop must be under the entry")
    pct = min(risk_pct if risk_pct is not None else rules["risk_pct"], rules["max_risk_pct"])
    budget = min(risk_rs, capital * rules["max_risk_pct"] / 100) if risk_rs is not None else capital * pct / 100
    by_risk = math.floor(budget / per_share)
    by_cap = math.floor(capital * rules["max_position_pct"] / 100 / entry)
    qty = max(0, min(by_risk, by_cap))
    warnings = []
    if open_positions >= rules["max_open"]:
        warnings.append(f"{open_positions} positions already open (limit {rules['max_open']}): close one first")
    if by_cap < by_risk:
        warnings.append(f"capped at {rules['max_position_pct']:g}% of capital in one stock")
    return {"qty": qty, "entry": round(entry, 2), "stop_loss": round(stop, 2),
            "target": round(entry + rules["target_r"] * per_share, 2), "reward_to_risk": f"1:{rules['target_r']:g}",
            "investment": round(qty * entry, 2), "risk_if_stopped": round(qty * per_share, 2),
            "risk_budget": round(budget, 2), "warnings": warnings}


def chandelier(bars_since_entry: list[dict], atr_value: float, initial_stop: float, mult: float = SWING["trail_atr"]) -> float:
    """Trailing stop: the highest close since the entry less [mult] ATRs, never under the first stop."""
    return round(max(initial_stop, max(b["close"] for b in bars_since_entry) - mult * atr_value), 2)


def swing_tickets(symbol: str, side: str, qty: int, entry: float, stop: float, target: float) -> dict:
    """Delivery (CNC) tickets: a LIMIT entry, then the stop and target as a GTT (good till triggered) pair, in Kite's,
    Angel One's and Shoonya's own fields. Nothing is sent."""
    sym = symbol.upper().replace(".NS", "").replace(".BO", "")
    side, exit_side = side.upper(), ("SELL" if side.upper() == "BUY" else "BUY")
    stop_limit = round(stop * (0.995 if exit_side == "SELL" else 1.005), 2)  # a limit just past the trigger so it fills
    return {
        "paper_only": True,
        "note": "Not sent to any broker. Place the GTT after the entry fills; Angel One's GTT rules are single, so "
                "cancel the other one when one triggers.",
        "zerodha_kite": {
            "entry": {"tradingsymbol": sym, "exchange": "NSE", "transaction_type": side, "quantity": qty, "product": "CNC",
                      "order_type": "LIMIT", "price": entry, "validity": "DAY", "variety": "regular"},
            "gtt_oco": {"type": "two-leg", "condition": {"exchange": "NSE", "tradingsymbol": sym, "trigger_values": [stop, target],
                                                          "last_price": entry},
                        "orders": [{"transaction_type": exit_side, "quantity": qty, "product": "CNC", "order_type": "LIMIT", "price": stop_limit},
                                   {"transaction_type": exit_side, "quantity": qty, "product": "CNC", "order_type": "LIMIT", "price": target}]},
        },
        "angel_one": {
            "entry": {"variety": "NORMAL", "tradingsymbol": f"{sym}-EQ", "symboltoken": "<from Angel's instrument list>",
                      "exchange": "NSE", "transactiontype": side, "ordertype": "LIMIT", "producttype": "DELIVERY",
                      "duration": "DAY", "price": str(entry), "quantity": str(qty)},
            "gtt_rules": [{"tradingsymbol": f"{sym}-EQ", "symboltoken": "<from Angel's instrument list>", "exchange": "NSE",
                           "transactiontype": exit_side, "producttype": "DELIVERY", "price": str(p), "qty": str(qty),
                           "triggerprice": str(t), "disclosedqty": "0"} for t, p in ((stop, stop_limit), (target, target))],
        },
        "shoonya": {
            "entry": {"exch": "NSE", "tsym": f"{sym}-EQ", "qty": str(qty), "prc": str(entry), "prd": "C",
                      "trantype": side[0], "prctyp": "LMT", "ret": "DAY"},
            "gtt_oco": {"exch": "NSE", "tsym": f"{sym}-EQ", "trantype": exit_side[0], "prd": "C", "qty": str(qty),
                        "stoploss": {"trigger": str(stop), "price": str(stop_limit)}, "target": {"trigger": str(target), "price": str(target)}},
        },
    }


# ---------------------------------------------------------------- swing: catalysts

_KINDS = [("results", r"financial result|quarterly result|audited result|unaudited result|results?\b"),
          ("board_meeting", r"board meeting"), ("dividend", r"dividend"), ("record_date", r"record date"),
          ("agm_egm", r"\bagm\b|\begm\b|general meeting"), ("bonus_split", r"\bbonus\b|split|sub-division"),
          ("buyback", r"buy ?back"), ("rights", r"rights issue")]
_MONTHS = {m: i for i, m in enumerate(["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], 1)}


def dates_in(text: str) -> list[date]:
    """Dates written in a filing's headline: 12/11/2026, 12-11-2026, 12th November 2026, November 12, 2026."""
    out = []
    for d, m, y in re.findall(r"\b(\d{1,2})[/.-](\d{1,2})[/.-](20\d\d)\b", text):
        try:
            out.append(date(int(y), int(m), int(d)))
        except ValueError:
            pass
    for d, mon, y in re.findall(r"\b(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})\.?,?\s+(20\d\d)\b", text):
        if mon[:3].lower() in _MONTHS:
            try:
                out.append(date(int(y), _MONTHS[mon[:3].lower()], int(d)))
            except ValueError:
                pass
    for mon, d, y in re.findall(r"\b([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(20\d\d)\b", text):
        if mon[:3].lower() in _MONTHS:
            try:
                out.append(date(int(y), _MONTHS[mon[:3].lower()], int(d)))
            except ValueError:
                pass
    return sorted(set(out))


def catalysts_of(filings: list[dict], today: date) -> dict:
    """Filings sorted into upcoming events (a date in the headline on or after [today]) and recent ones."""
    upcoming, recent = [], []
    for f in filings:
        text = f["title"]
        kinds = [k for k, pat in _KINDS if re.search(pat, text, re.I)]
        if not kinds:
            continue
        ahead = [d for d in dates_in(text) if d >= today]
        item = {"filed": f["filed"], "kinds": kinds, "title": text[:220]}
        if ahead:
            upcoming.append({**item, "event_date": str(ahead[0])})
        else:
            recent.append(item)
    upcoming.sort(key=lambda x: x["event_date"])
    return {"upcoming": upcoming, "recent": recent[:15]}


def bse_get(path: str) -> object:
    url = BSE_API + path
    req = trader.urllib.request.Request(url, headers={"User-Agent": trader.UA, "Accept": "application/json",
                                                      "Referer": "https://www.bseindia.com/", "Origin": "https://www.bseindia.com"})
    try:
        with trader.urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        raise DataError(f"BSE did not answer ({type(e).__name__}: {str(e)[:80]})") from e
    try:
        return json.loads(body)
    except ValueError:
        return body


def bse_code(symbol: str) -> str:
    """BSE's six-digit scrip code for an NSE symbol, from BSE's own search."""
    if symbol.isdigit():
        return symbol
    body = bse_get("PeerSmartSearch/w?Type=SS&text=" + urllib.parse.quote(symbol))
    text = body if isinstance(body, str) else json.dumps(body)
    m = re.search(r"/(\d{6})/", text) or re.search(r"\b([5-9]\d{5})\b", text)
    if not m:
        raise DataError(f"BSE's search did not find {symbol}; pass --bse-code")
    return m.group(1)


def bse_filings(code: str, days: int) -> list[dict]:
    today = datetime.now(IST).date()
    body = bse_get(f"AnnSubCategoryGetData/w?pageno=1&strCat=-1&strPrevDate={(today - timedelta(days=days)):%Y%m%d}"
                   f"&strScrip={code}&strSearch=P&strToDate={today:%Y%m%d}&strType=C&subcategory=-1")
    rows = body.get("Table", []) if isinstance(body, dict) else []
    return [{"filed": (r.get("NEWS_DT") or r.get("DissemDT") or "")[:16].replace("T", " "),
             "title": " ".join(x for x in (r.get("NEWSSUB") or "", r.get("HEADLINE") or "") if x).strip()} for r in rows]


# ---------------------------------------------------------------- long term

def cagr(first: float | None, last: float | None, years: int) -> float | None:
    if not first or not last or first <= 0 or last <= 0 or years <= 0:
        return None
    return round(((last / first) ** (1 / years) - 1) * 100, 1)


def full_years(hist: dict) -> list[list]:
    """The years with both sales and profit; the file's last row can be a part-year that has only the debt."""
    return [y for y in (hist.get("y") or []) if len(y) > 2 and y[1] is not None and y[2] is not None]


def latest_debt(hist: dict) -> float | None:
    debts = [y[5] for y in (hist.get("y") or []) if len(y) > 5 and y[5] is not None]
    return debts[-1] if debts else None


def cmp_of(row: dict) -> float | None:
    """The price the R/S file valued the stock at: its fair value over one plus its upside."""
    f = row.get("f", {})
    fv, up = f.get("fv"), f.get("up")
    return round(fv / (1 + up / 100), 2) if fv and up is not None and up > -100 else None


def ttm_eps(hist: dict, half_yearly: bool) -> float | None:
    q = [r for r in (hist.get("q") or []) if len(r) > 3 and r[3] is not None]
    n = 2 if half_yearly else 4
    return round(sum(r[3] for r in q[-n:]), 2) if len(q) >= n else None


def roe_of(row: dict, hist: dict) -> float | None:
    """Return on equity: TTM EPS over book value a share when the file has it; else the latest year's profit over the
    equity its debt and debt-to-equity imply (left unknown for a company without debt)."""
    f = row.get("f", {})
    eps = ttm_eps(hist, bool(f.get("hy")))
    if f.get("bv") and eps is not None:
        return round(100 * eps / f["bv"], 1)
    years = full_years(hist)
    if years and len(years[-1]) > 5 and years[-1][5] and f.get("de") and f["de"] > 0.05:
        equity = years[-1][5] / f["de"]
        return round(100 * years[-1][2] / equity, 1) if equity > 0 else None
    return None


def quality_checks(row: dict, hist: dict, rules: dict = LONG) -> dict:
    """The long-term screen's tests, each True, False or None (the data does not have it)."""
    f = row.get("f", {})
    years = full_years(hist)
    profit_cagr = cagr(years[0][2], years[-1][2], len(years) - 1) if len(years) >= 3 else None
    roe = roe_of(row, hist)
    checks = {
        "roce": (f.get("roce"), None if f.get("roce") is None else f["roce"] >= rules["min_roce"]),
        "roe": (roe, None if roe is None else roe >= rules["min_roe"]),
        "debt_to_equity": (f.get("de"), None if f.get("de") is None else f["de"] <= rules["max_de"]),
        "profit_cagr_pct": (profit_cagr, None if profit_cagr is None else profit_cagr >= rules["min_profit_cagr"]),
        "promoter_pct": (f.get("pm"), None if f.get("pm") is None else f["pm"] >= rules["min_promoter"]),
        "pledge_pct": (f.get("pl"), None if f.get("pl") is None else f["pl"] <= rules["max_pledge"]),
    }
    return {k: {"value": v, "pass": p} for k, (v, p) in checks.items()}


def all_rs() -> tuple[dict, dict]:
    """Every stock's R/S row and history (all 32 history files)."""
    data = json.loads(gzip.decompress(trader._get(trader.RELEASE + "cr360.json.gz", 60)))
    hist = {}
    for n in range(trader.HISTORY_SHARDS):
        hist.update(json.loads(gzip.decompress(trader._get(trader.RELEASE + f"cr360-h-{n:02d}.json.gz", 60)))["stocks"])
    return data, hist


def screen(data: dict, hist: dict, top: int = 30, strict: bool = False) -> dict:
    passed = []
    for sym, row in data["stocks"].items():
        checks = quality_checks(row, hist.get(sym) or {})
        known = [c["pass"] for c in checks.values() if c["pass"] is not None]
        if any(p is False for p in known) or len(known) < (len(checks) if strict else 4):
            continue
        passed.append({"symbol": sym, "rs_score": row.get("s"), "rs_state": row.get("st"), "known": len(known),
                       "unknown": [k for k, c in checks.items() if c["pass"] is None],
                       **{k: c["value"] for k, c in checks.items()}})
    passed.sort(key=lambda x: (-x["known"], -(x["rs_score"] or 0)))
    return {"data_as_of": data.get("updated"), "rules": LONG, "stocks_checked": len(data["stocks"]),
            "passed": len(passed), "top": passed[:top]}


def dcf_per_share(fcf: float, growth_pct: float, shares_cr: float, debt_cr: float = 0.0, discount: float = 12.0,
                  terminal: float = 5.0, years: int = 10) -> float | None:
    """Ten years of free cash flow growing at [growth_pct] fading to [terminal] in a straight line, plus a terminal
    value, at [discount]%; less the debt; a share's worth (₹ crore figures, shares in crore)."""
    if fcf <= 0 or shares_cr <= 0 or discount <= terminal:
        return None
    pv, cash = 0.0, fcf
    for t in range(1, years + 1):
        g = growth_pct + (terminal - growth_pct) * (t - 1) / (years - 1)
        cash *= 1 + g / 100
        pv += cash / (1 + discount / 100) ** t
    pv += cash * (1 + terminal / 100) / ((discount - terminal) / 100) / (1 + discount / 100) ** years
    return round((pv - debt_cr) / shares_cr, 2)


def valuation(symbol: str, discount: float = 12.0, growth_pct: float | None = None, terminal: float = 5.0) -> dict:
    row, hist = trader.rs_stock(symbol), trader.rs_history(symbol)
    f = row.get("f", {})
    price = cmp_of(row)
    eps = ttm_eps(hist, bool(f.get("hy")))
    years = full_years(hist)
    graham = round(math.sqrt(22.5 * eps * f["bv"]), 2) if eps and eps > 0 and f.get("bv") and f["bv"] > 0 else None
    fcfs = [y[4] for y in years[-3:] if len(y) > 4 and y[4] is not None]
    growth = cagr(years[0][2], years[-1][2], len(years) - 1) if len(years) >= 3 else None
    used_growth = growth_pct if growth_pct is not None else max(0.0, min(15.0, growth if growth is not None else 8.0))
    dcf = None
    if fcfs and f.get("sh"):
        dcf = dcf_per_share(sum(fcfs) / len(fcfs), used_growth, f["sh"], latest_debt(hist) or 0.0, discount, terminal)

    def mos(value):
        return round((value - price) / value * 100, 1) if value and price else None

    return {"symbol": symbol.upper(), "data_as_of": row.get("_updated"), "cmp_at_rs_update": price,
            "framework": {"intrinsic_value": f.get("fv"), "buy_price": f.get("bp"), "margin_of_safety_pct": mos(f.get("fv")),
                          "model": f.get("mdl")},
            "history_pe": {"usual_pe": f.get("hpe"), "pe_now": f.get("pe"), "fair_value": f.get("fh"), "margin_of_safety_pct": mos(f.get("fh"))},
            "graham_number": {"value": graham, "eps_ttm": eps, "book_value_per_share": f.get("bv"), "margin_of_safety_pct": mos(graham)},
            "dcf": {"value": dcf, "fcf_avg_cr": round(sum(fcfs) / len(fcfs), 1) if fcfs else None, "profit_cagr_pct": growth,
                    "growth_used_pct": used_growth, "discount_pct": discount, "terminal_pct": terminal, "debt_cr": latest_debt(hist),
                    "margin_of_safety_pct": mos(dcf)},
            "note": "A method shows null when the R/S data lacks its inputs (book value, shares or free cash flow)."}


def moat(symbol: str) -> dict:
    row, hist = trader.rs_stock(symbol), trader.rs_history(symbol)
    f = row.get("f", {})
    years = full_years(hist)
    margins = [round(100 * y[2] / y[1], 1) for y in years if y[2] is not None]
    year_ago = (datetime.now(IST) - timedelta(days=365)).strftime("%Y-%m-%d")
    promoter_sold = sum(r[2] or 0 for r in (hist.get("i") or [])
                        if r[0] >= year_ago and r[1] == "sell" and len(r) > 5 and "promoter" in str(r[5]).lower())
    sales_up = sum(1 for a, b in zip(years, years[1:]) if b[1] > a[1])
    q = quality_checks(row, hist)

    def item(name, value, ok, why):
        return {"check": name, "value": value, "pass": ok, "why": why}

    items = [
        item("pricing_power", margins, None if len(margins) < 3 else margins[-1] >= sum(margins) / len(margins) - 1,
             "net margin held or rose over the years"),
        item("returns_on_capital", {"roce": q["roce"]["value"], "roe": q["roe"]["value"]},
             None if q["roce"]["pass"] is None and q["roe"]["pass"] is None else bool(q["roce"]["pass"] or q["roe"]["pass"]),
             "ROCE or ROE at least 15%"),
        item("steady_growth", f"sales up in {sales_up} of {max(0, len(years) - 1)} years",
             None if len(years) < 3 else sales_up >= len(years) - 2, "sales grew in all years but one at most"),
        item("balance_sheet", {"debt_to_equity": f.get("de"), "interest_cover": f.get("icr")},
             None if f.get("de") is None else f["de"] <= 0.5 and (f.get("icr") is None or f["icr"] >= 5),
             "debt-to-equity at most 0.5 and interest covered 5 times"),
        item("cash_conversion", f.get("cfp"), None if f.get("cfp") is None else f["cfp"] >= 0.8,
             "operating cash flow at least 80% of profit"),
        item("promoter_holding", {"pct": f.get("pm"), "change": f.get("pmc")},
             None if f.get("pm") is None else f["pm"] >= 50 and (f.get("pmc") or 0) >= -1, "promoters hold half or more and are not cutting"),
        item("pledge", f.get("pl"), None if f.get("pl") is None else f["pl"] == 0, "no promoter shares pledged"),
        item("promoter_selling_12m", trader._crore(promoter_sold), promoter_sold == 0, "promoters did not sell in the last year"),
        item("auditor", f.get("adr"), f.get("adr") is None, "no statutory auditor resigned in the penalty year"),
        item("market_share", None, None, "not in the data: judge it from the company's own reports"),
    ]
    known = [i for i in items if i["pass"] is not None]
    return {"symbol": symbol.upper(), "data_as_of": row.get("_updated"), "score": f"{sum(1 for i in known if i['pass'])}/{len(known)}",
            "checks": items}


def allocation(capital: float, buckets: dict[str, list[str]], split: list[float], vols: dict[str, float | None],
               prices: dict[str, float | None]) -> dict:
    """Core / growth / cash split, each bucket's money spread by inverse volatility when every stock's is known
    (calmer stocks get more), equally otherwise."""
    names = ["core_compounders", "growth", "cash_reserve"]
    out = {"capital": capital, "split_pct": dict(zip(names, split)), "buckets": {}}
    for name, pct, symbols in zip(names, split, [buckets.get("core", []), buckets.get("growth", []), []]):
        money = capital * pct / 100
        if not symbols:
            out["buckets"][name] = {"amount": round(money, 2)}
            continue
        v = [vols.get(s) for s in symbols]
        by_volatility = all(x and x > 0 for x in v)
        weights = [1 / x for x in v] if by_volatility else [1.0] * len(symbols)
        total = sum(weights)
        out["buckets"][name] = {"amount": round(money, 2), "weighting": "inverse volatility" if by_volatility else "equal",
                                "stocks": [{"symbol": s, "amount": round(money * w / total, 2),
                                            "qty_at_rs_price": math.floor(money * w / total / prices[s]) if prices.get(s) else None,
                                            "volatility_pct": vols.get(s)} for s, w in zip(symbols, weights)]}
    return out


def rebalance(holdings: list[dict], prices: dict[str, float], band: float = 5.0, today: date | None = None, tax: dict = TAX) -> dict:
    """Weights against targets (equal when none are given), the trades back to target for positions outside the
    band, and each lot's tax view: short or long term, gain, and the losses that could be harvested."""
    today = today or datetime.now(IST).date()
    total = sum(h["qty"] * prices[h["symbol"]] for h in holdings)
    by_symbol: dict[str, dict] = {}
    for h in holdings:
        s = by_symbol.setdefault(h["symbol"], {"qty": 0, "value": 0.0, "target": h.get("target_pct")})
        s["qty"] += h["qty"]
        s["value"] += h["qty"] * prices[h["symbol"]]
    n = len(by_symbol)
    rows, lots = [], []
    for sym, s in by_symbol.items():
        target = s["target"] if s["target"] is not None else 100 / n
        weight = 100 * s["value"] / total if total else 0
        drift = weight - target
        trade = math.floor((target / 100 * total - s["value"]) / prices[sym]) if abs(drift) > band else 0
        rows.append({"symbol": sym, "value": round(s["value"], 2), "weight_pct": round(weight, 1), "target_pct": round(target, 1),
                     "drift_pct": round(drift, 1), "trade_qty": trade})
    short_gain = long_gain = 0.0
    for h in holdings:
        held = (today - h["buy_date"]).days if h.get("buy_date") else None
        gain = (prices[h["symbol"]] - h["avg_price"]) * h["qty"]
        term = None if held is None else ("long" if held > tax["long_after_days"] else "short")
        if term == "short":
            short_gain += gain
        elif term == "long":
            long_gain += gain
        lots.append({"symbol": h["symbol"], "qty": h["qty"], "avg_price": h["avg_price"], "held_days": held, "term": term,
                     "unrealised": round(gain, 2), "harvest_candidate": gain < 0})
    taxable_long = max(0.0, long_gain - tax["ltcg_exempt"])
    return {"total_value": round(total, 2), "positions": sorted(rows, key=lambda r: -abs(r["drift_pct"])), "lots": lots,
            "if_all_sold": {"short_term_gain": round(short_gain, 2), "long_term_gain": round(long_gain, 2),
                            "estimated_tax": round(max(0.0, short_gain) * tax["stcg"] / 100 + taxable_long * tax["ltcg"] / 100, 2)},
            "tax_rates_used": tax, "note": "Rates are the listed-equity ones from Budget 2024; check today's before acting. Not tax advice."}


# ---------------------------------------------------------------- command line

def read_holdings(path: str) -> list[dict]:
    out = []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            row = {k.strip().lower(): (v or "").strip() for k, v in row.items()}
            out.append({"symbol": row["symbol"].upper(), "qty": float(row["qty"]), "avg_price": float(row["avg_price"]),
                        "buy_date": date.fromisoformat(row["buy_date"]) if row.get("buy_date") else None,
                        "target_pct": float(row["target_pct"]) if row.get("target_pct") else None})
    if not out:
        raise DataError(f"{path} has no holdings")
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("short-scanner"); s.add_argument("symbols", nargs="*")
    s = sub.add_parser("short-setup"); s.add_argument("symbol")
    s = sub.add_parser("short-risk")
    s.add_argument("--entry", type=float, required=True); s.add_argument("--stop", type=float); s.add_argument("--symbol")
    s.add_argument("--capital", type=float, default=SWING["capital"]); s.add_argument("--risk-pct", type=float)
    s.add_argument("--risk-rs", type=float); s.add_argument("--open", type=int, default=0); s.add_argument("--since", help="entry day, for the trailing stop")
    s = sub.add_parser("short-catalysts"); s.add_argument("symbol"); s.add_argument("--bse-code"); s.add_argument("--days", type=int, default=90)
    s = sub.add_parser("short-broker"); s.add_argument("symbol"); s.add_argument("--side", default="BUY")
    s.add_argument("--qty", type=int, required=True); s.add_argument("--entry", type=float, required=True)
    s.add_argument("--stop", type=float, required=True); s.add_argument("--target", type=float, required=True)
    s = sub.add_parser("long-screener"); s.add_argument("--top", type=int, default=30); s.add_argument("--strict", action="store_true")
    s = sub.add_parser("long-valuation"); s.add_argument("symbol"); s.add_argument("--discount", type=float, default=12.0)
    s.add_argument("--growth", type=float, help="free cash flow growth for the first years (default: profit CAGR, 0-15%%)")
    s.add_argument("--terminal", type=float, default=5.0)
    s = sub.add_parser("long-moat"); s.add_argument("symbol")
    s = sub.add_parser("long-portfolio"); s.add_argument("--capital", type=float, required=True)
    s.add_argument("--core", default=""); s.add_argument("--growth", default=""); s.add_argument("--split", default="60,30,10")
    s = sub.add_parser("long-rebalance"); s.add_argument("--holdings", required=True); s.add_argument("--band", type=float, default=5.0)
    s.add_argument("--prices", help="CSV of symbol,price to use instead of Yahoo")
    a = p.parse_args(argv)

    try:
        if a.cmd == "short-scanner":
            results, failed = [], []
            for sym in a.symbols or WATCHLIST:
                try:
                    results.append({"symbol": sym.upper(), **swing_signals(trader.bars_from_yahoo(sym, "1d", "2y"))})
                except DataError as e:
                    failed.append({"symbol": sym.upper(), "error": str(e)})
                time.sleep(0.3)
            results.sort(key=lambda r: -len(r["signals"]))
            show({"results": results, "not_read": failed})
        elif a.cmd == "short-setup":
            show({"symbol": a.symbol.upper(), **swing_setup(trader.bars_from_yahoo(a.symbol, "1d", "2y"),
                                                             trader.bars_from_yahoo(a.symbol, "1wk", "5y"))})
        elif a.cmd == "short-risk":
            stop, trail = a.stop, None
            if a.symbol:
                bars = trader.bars_from_yahoo(a.symbol, "1d", "1y")
                a_value = atr(bars)
                if stop is None and a_value:
                    stop = round(a.entry - SWING["atr_stop"] * a_value, 2)
                if a.since and a_value:
                    since = date.fromisoformat(a.since)
                    held = [b for b in bars if b["time"].date() >= since]
                    if held and stop is not None:
                        trail = chandelier(held, a_value, stop)
            if stop is None:
                raise DataError("give --stop, or --symbol for a stop two ATRs under the entry")
            show({**swing_position(a.capital, a.entry, stop, a.risk_pct, a.risk_rs, a.open), "trailing_stop": trail})
        elif a.cmd == "short-catalysts":
            code = a.bse_code or bse_code(a.symbol)
            show({"symbol": a.symbol.upper(), "bse_code": code,
                  **catalysts_of(bse_filings(code, a.days), datetime.now(IST).date())})
        elif a.cmd == "short-broker":
            show(swing_tickets(a.symbol, a.side, a.qty, a.entry, a.stop, a.target))
        elif a.cmd == "long-screener":
            data, hist = all_rs()
            show(screen(data, hist, a.top, a.strict))
        elif a.cmd == "long-valuation":
            show(valuation(a.symbol, a.discount, a.growth, a.terminal))
        elif a.cmd == "long-moat":
            show(moat(a.symbol))
        elif a.cmd == "long-portfolio":
            split = [float(x) for x in a.split.split(",")]
            if len(split) != 3 or abs(sum(split) - 100) > 0.01:
                raise DataError("--split takes three percentages that add up to 100")
            buckets = {"core": [s.strip().upper() for s in a.core.split(",") if s.strip()],
                       "growth": [s.strip().upper() for s in a.growth.split(",") if s.strip()]}
            vols, prices = {}, {}
            for s in buckets["core"] + buckets["growth"]:
                row = trader.rs_stock(s)
                vols[s], prices[s] = row.get("f", {}).get("vol"), cmp_of(row)
            show(allocation(a.capital, buckets, split, vols, prices))
        elif a.cmd == "long-rebalance":
            holdings = read_holdings(a.holdings)
            prices = {}
            if a.prices:
                with open(a.prices, newline="", encoding="utf-8") as f:
                    prices = {r["symbol"].strip().upper(): float(r["price"]) for r in csv.DictReader(f)}
            for s in {h["symbol"] for h in holdings} - set(prices):
                prices[s] = trader.bars_from_yahoo(s, "1d", "5d")[-1]["close"]
            show(rebalance(holdings, prices, a.band))
    except DataError as e:
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
