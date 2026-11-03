"""Trading helpers for NSE/BSE stocks, used by the /trader-* Claude skills (.claude/skills/trader-*/SKILL.md).

    python3 tools/trader.py smc SYMBOL [--interval 1d|5m|15m] [--csv FILE]
    python3 tools/trader.py entry SYMBOL [--orb-minutes 15] [--csv FILE]
    python3 tools/trader.py exit --entry E --orb-high H --orb-low L [--side BUY|SELL] [--r 1.5 2]
    python3 tools/trader.py budget --capital C --entry E --stop S [--risk-pct 1 | --risk-rs R] [--product MIS|CNC]
    python3 tools/trader.py risk --loss L [--day-loss D] [--trades N] [--time HH:MM]
    python3 tools/trader.py alerts --cmp P --stop S --target T [--side BUY|SELL] [--time HH:MM]
    python3 tools/trader.py ticket SYMBOL --side BUY|SELL --qty N --price P --stop S --target T [--product MIS|CNC]
    python3 tools/trader.py timemachine SYMBOL --entry E --stop S --target T [--paths 10000] [--steps 30] [--csv FILE]
    python3 tools/trader.py insider SYMBOL
    python3 tools/trader.py analyst SYMBOL

Prices come from Yahoo Finance's chart (SYMBOL.NS, or .BO for a BSE code), as the app's scan reads them; a CSV of
time,open,high,low,close[,volume] can stand in for them. Insider trades, deals, results and shareholding come from the
app's own nightly R/S files (the cr360-data release). Nothing is made up: when data does not come, the command says so.
Times are India's (IST) whatever the machine's clock is set to. Nothing here places an order.
"""
import argparse
import csv
import gzip
import json
import math
import random
import sys
import urllib.request
import zlib
from datetime import datetime, timezone, timedelta

IST = timezone(timedelta(hours=5, minutes=30))
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
RELEASE = "https://github.com/rohitrot738/Share_scan/releases/download/cr360-data/"
HISTORY_SHARDS = 32
SQUARE_OFF = "15:15"  # brokers square intraday (MIS) positions off from about 15:15-15:20 IST
MARKET_OPEN, MARKET_CLOSE = "09:15", "15:30"


class DataError(Exception):
    pass


# ---------------------------------------------------------------- data

def _get(url: str, timeout: int = 20) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def yahoo_symbol(symbol: str) -> str:
    s = symbol.strip().upper()
    if s.endswith((".NS", ".BO")):
        return s
    return s + (".BO" if s.isdigit() else ".NS")


def bars_from_yahoo(symbol: str, interval: str = "1d", range_: str | None = None) -> list[dict]:
    """Candles (time in IST, open, high, low, close, volume) from Yahoo's chart, oldest first."""
    range_ = range_ or {"1d": "1y", "1h": "3mo", "15m": "1mo", "5m": "5d", "1m": "1d"}.get(interval, "1mo")
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{yahoo_symbol(symbol)}?interval={interval}&range={range_}"
    try:
        data = json.loads(_get(url))
        result = data["chart"]["result"][0]
    except Exception as e:  # noqa: BLE001
        raise DataError(f"no {interval} candles for {symbol} from Yahoo ({type(e).__name__}: {str(e)[:80]})") from e
    q = result["indicators"]["quote"][0]
    out = []
    for i, t in enumerate(result.get("timestamp") or []):
        o, h, l, c = q["open"][i], q["high"][i], q["low"][i], q["close"][i]
        if None in (o, h, l, c):
            continue
        out.append({"time": datetime.fromtimestamp(t, IST), "open": o, "high": h, "low": l, "close": c,
                    "volume": (q.get("volume") or [None] * (i + 1))[i] or 0})
    if not out:
        raise DataError(f"Yahoo returned no {interval} candles for {symbol}")
    return out


def bars_from_csv(path: str) -> list[dict]:
    """Candles from a CSV with a header: time,open,high,low,close[,volume]; time as ISO ("2026-11-03 09:15")."""
    out = []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            row = {k.strip().lower(): v for k, v in row.items()}
            t = datetime.fromisoformat(row["time"].strip())
            out.append({"time": t if t.tzinfo else t.replace(tzinfo=IST), "open": float(row["open"]), "high": float(row["high"]),
                        "low": float(row["low"]), "close": float(row["close"]), "volume": float(row.get("volume") or 0)})
    if not out:
        raise DataError(f"{path} has no candles")
    return sorted(out, key=lambda b: b["time"])


def load_bars(args, interval: str) -> list[dict]:
    return bars_from_csv(args.csv) if getattr(args, "csv", None) else bars_from_yahoo(args.symbol, interval)


def history_shard(symbol: str) -> int:
    """Which R/S history file holds a stock, as the app and the nightly cache compute it."""
    return zlib.crc32(symbol.encode("utf-8")) % HISTORY_SHARDS


def rs_stock(symbol: str) -> dict:
    """A stock's row of the nightly R/S file (cr360.json.gz)."""
    try:
        data = json.loads(gzip.decompress(_get(RELEASE + "cr360.json.gz", 60)))
    except Exception as e:  # noqa: BLE001
        raise DataError(f"the R/S file did not come ({type(e).__name__})") from e
    row = data["stocks"].get(symbol.upper())
    if row is None:
        raise DataError(f"{symbol} is not in the R/S file (updated {data.get('updated')})")
    row["_updated"] = data.get("updated")
    return row


def rs_history(symbol: str) -> dict:
    """A stock's R/S history: quarters (q), shareholding (s), years (y), insider trades (i) and deals (d)."""
    name = f"cr360-h-{history_shard(symbol.upper()):02d}.json.gz"
    try:
        data = json.loads(gzip.decompress(_get(RELEASE + name, 60)))
    except Exception as e:  # noqa: BLE001
        raise DataError(f"the R/S history file did not come ({type(e).__name__})") from e
    return data["stocks"].get(symbol.upper()) or {}


def now_ist(hhmm: str | None = None) -> str:
    return hhmm or datetime.now(IST).strftime("%H:%M")


# ---------------------------------------------------------------- smart money concepts

def fair_value_gaps(bars: list[dict], lookback: int = 60) -> list[dict]:
    """Three-candle gaps: a bullish one where candle 1's high is below candle 3's low, a bearish one where candle 1's
    low is above candle 3's high. Each says whether later prices have filled it."""
    out = []
    start = max(0, len(bars) - lookback)
    for i in range(start + 2, len(bars)):
        c1, c3 = bars[i - 2], bars[i]
        if c1["high"] < c3["low"]:
            gap = {"type": "BULLISH_FVG", "bottom": c1["high"], "top": c3["low"], "time": c3["time"]}
            later = bars[i + 1:]
            gap["filled"] = any(b["low"] <= gap["bottom"] for b in later)
            gap["touched"] = any(b["low"] <= gap["top"] for b in later)
            out.append(gap)
        elif c1["low"] > c3["high"]:
            gap = {"type": "BEARISH_FVG", "top": c1["low"], "bottom": c3["high"], "time": c3["time"]}
            later = bars[i + 1:]
            gap["filled"] = any(b["high"] >= gap["top"] for b in later)
            gap["touched"] = any(b["high"] >= gap["bottom"] for b in later)
            out.append(gap)
    return out


def order_blocks(bars: list[dict], lookback: int = 60, push: float = 1.0) -> list[dict]:
    """The last opposite candle before a strong move: a bullish order block is a down candle followed by an up candle
    whose body is larger than the down candle's whole range times [push] (and the reverse for bearish). Each says
    whether price has since closed through it (mitigated)."""
    out = []
    start = max(1, len(bars) - lookback)
    for i in range(start, len(bars)):
        cur, prev = bars[i], bars[i - 1]
        body = cur["close"] - cur["open"]
        span = prev["high"] - prev["low"]
        if span <= 0:
            continue
        later = bars[i + 1:]
        if body > push * span and prev["close"] < prev["open"]:
            out.append({"type": "BULLISH_OB", "high": prev["high"], "low": prev["low"], "time": prev["time"],
                        "mitigated": any(b["close"] < prev["low"] for b in later)})
        elif -body > push * span and prev["close"] > prev["open"]:
            out.append({"type": "BEARISH_OB", "high": prev["high"], "low": prev["low"], "time": prev["time"],
                        "mitigated": any(b["close"] > prev["high"] for b in later)})
    return out


def liquidity_sweeps(bars: list[dict], swing: int = 5, lookback: int = 60) -> list[dict]:
    """A candle whose wick runs past the highest high (or lowest low) of the [swing] candles before it but closes back
    inside: stops taken above or below, then price turned."""
    out = []
    start = max(swing, len(bars) - lookback)
    for i in range(start, len(bars)):
        before = bars[i - swing:i]
        hi, lo = max(b["high"] for b in before), min(b["low"] for b in before)
        b = bars[i]
        if b["high"] > hi and b["close"] < hi:
            out.append({"type": "SWEEP_HIGH", "level": hi, "wick": b["high"], "close": b["close"], "time": b["time"]})
        if b["low"] < lo and b["close"] > lo:
            out.append({"type": "SWEEP_LOW", "level": lo, "wick": b["low"], "close": b["close"], "time": b["time"]})
    return out


# ---------------------------------------------------------------- intraday entry and exit

def opening_range(bars: list[dict], minutes: int = 15) -> dict:
    """The latest session's opening range (its first [minutes] from 09:15 IST) and where price is against it."""
    if not bars:
        raise DataError("no candles")
    day = bars[-1]["time"].astimezone(IST).date()
    session = [b for b in bars if b["time"].astimezone(IST).date() == day]
    open_at = datetime.combine(day, datetime.strptime(MARKET_OPEN, "%H:%M").time(), IST)
    first = [b for b in session if b["time"] < open_at + timedelta(minutes=minutes)]
    if not first or len(session) <= len(first):
        return {"status": "WAITING_DATA", "day": str(day), "reason": f"the first {minutes} minutes have not closed yet"}
    orb_high, orb_low = max(b["high"] for b in first), min(b["low"] for b in first)
    after = session[len(first):]
    last = session[-1]
    breakout = next((b for b in after if b["close"] > orb_high or b["close"] < orb_low), None)
    out = {"day": str(day), "orb_high": orb_high, "orb_low": orb_low, "cmp": last["close"], "time": last["time"].strftime("%H:%M")}
    if breakout is None:
        return {**out, "status": "WAITING", "direction": "NONE"}
    side = "BUY" if breakout["close"] > orb_high else "SELL"
    return {**out, "status": "TRIGGERED", "direction": side, "entry": breakout["close"],
            "trigger_time": breakout["time"].strftime("%H:%M"), "level": orb_high if side == "BUY" else orb_low}


def plan_exit(entry: float, orb_high: float, orb_low: float, side: str = "BUY", r_multiples=(1.5, 2.0),
              atr: float | None = None, atr_mult: float = 1.0) -> dict:
    """Stop at the opening range's midpoint (or [atr_mult] ATRs from entry when [atr] is given) and targets at each R."""
    side = side.upper()
    mid = round((orb_high + orb_low) / 2, 2)
    if atr:
        stop = entry - atr_mult * atr if side == "BUY" else entry + atr_mult * atr
    else:
        stop = mid
    risk = entry - stop if side == "BUY" else stop - entry
    if risk <= 0:
        raise DataError(f"the stop ({stop:.2f}) is on the wrong side of the entry ({entry:.2f}) for a {side}")
    targets = {f"1:{r:g}": round(entry + r * risk if side == "BUY" else entry - r * risk, 2) for r in r_multiples}
    return {"side": side, "entry": round(entry, 2), "stop_loss": round(stop, 2), "risk_per_share": round(risk, 2),
            "targets": targets, "square_off": f"{SQUARE_OFF} IST"}


# ---------------------------------------------------------------- money and risk

def position_size(capital: float, entry: float, stop: float, risk_rs: float | None = None, risk_pct: float = 1.0,
                  product: str = "MIS", leverage: float = 5.0) -> dict:
    """Shares to buy so that a stop-out loses at most [risk_rs] (or [risk_pct]% of capital), within the money the
    account can put up: capital times [leverage] for intraday (MIS), the capital itself for delivery (CNC)."""
    per_share = abs(entry - stop)
    if per_share <= 0 or entry <= 0:
        raise DataError("entry and stop must differ and be above zero")
    budget = risk_rs if risk_rs is not None else capital * risk_pct / 100
    lev = leverage if product.upper() == "MIS" else 1.0
    by_risk = math.floor(budget / per_share)
    by_money = math.floor(capital * lev / entry)
    qty = max(0, min(by_risk, by_money))
    return {"qty": qty, "limited_by": "risk" if by_risk <= by_money else "margin", "risk_budget": round(budget, 2),
            "risk_if_stopped": round(qty * per_share, 2), "order_value": round(qty * entry, 2),
            "margin_needed": round(qty * entry / lev, 2), "product": product.upper(), "leverage": lev}


RISK_RULES = {"max_daily_loss": 1500.0, "max_trades_per_day": 3, "no_entry_after": "14:45", "market_close": MARKET_CLOSE}


def validate_trade(proposed_loss: float, day_loss: float, trades_done: int, hhmm: str, rules: dict = RISK_RULES) -> tuple[str, list[str]]:
    """PASS, or BLOCK with every rule the trade would break: the day's loss limit, the number of trades, the time."""
    why = []
    if day_loss + proposed_loss > rules["max_daily_loss"]:
        why.append(f"the day's loss would reach ₹{day_loss + proposed_loss:,.0f}, over the ₹{rules['max_daily_loss']:,.0f} limit")
    if trades_done >= rules["max_trades_per_day"]:
        why.append(f"{trades_done} trades already today (limit {rules['max_trades_per_day']})")
    if hhmm > rules["no_entry_after"]:
        why.append(f"no new intraday entry after {rules['no_entry_after']} IST (now {hhmm})")
    if hhmm < MARKET_OPEN or hhmm >= rules["market_close"]:
        why.append(f"the market is closed (open {MARKET_OPEN}-{rules['market_close']} IST, now {hhmm})")
    return ("BLOCK" if why else "PASS"), why


def check_alerts(cmp: float, stop: float, target: float, side: str = "BUY", hhmm: str | None = None) -> list[str]:
    hhmm = now_ist(hhmm)
    out = []
    if side.upper() == "BUY":
        if cmp >= target:
            out.append(f"TARGET HIT: ₹{cmp:,.2f} reached the target ₹{target:,.2f} - book profit")
        elif cmp <= stop:
            out.append(f"STOP LOSS HIT: ₹{cmp:,.2f} at or below the stop ₹{stop:,.2f} - exit")
    else:
        if cmp <= target:
            out.append(f"TARGET HIT: short at ₹{cmp:,.2f} reached the target ₹{target:,.2f} - book profit")
        elif cmp >= stop:
            out.append(f"STOP LOSS HIT: short at ₹{cmp:,.2f} at or above the stop ₹{stop:,.2f} - exit")
    if SQUARE_OFF <= hhmm < MARKET_CLOSE:
        out.append(f"SQUARE-OFF: it is {hhmm} IST - close intraday (MIS) positions before the broker does")
    return out


def broker_tickets(symbol: str, side: str, qty: int, price: float, stop: float, target: float, product: str = "MIS") -> dict:
    """Order tickets in each broker API's own fields, for a paper trade or to type in: the entry, then a stop-loss
    order and a target order to close it. Nothing is sent. Angel One needs the instrument's numeric token, which
    comes from its instrument list."""
    sym = symbol.upper().replace(".NS", "").replace(".BO", "")
    side, exit_side = side.upper(), ("SELL" if side.upper() == "BUY" else "BUY")
    kite_product = {"MIS": "MIS", "CNC": "CNC"}[product.upper()]
    kite = [
        {"tradingsymbol": sym, "exchange": "NSE", "transaction_type": side, "quantity": qty, "product": kite_product,
         "order_type": "LIMIT", "price": price, "validity": "DAY", "variety": "regular"},
        {"tradingsymbol": sym, "exchange": "NSE", "transaction_type": exit_side, "quantity": qty, "product": kite_product,
         "order_type": "SL-M", "trigger_price": stop, "validity": "DAY", "variety": "regular"},
        {"tradingsymbol": sym, "exchange": "NSE", "transaction_type": exit_side, "quantity": qty, "product": kite_product,
         "order_type": "LIMIT", "price": target, "validity": "DAY", "variety": "regular"},
    ]
    angel_product = {"MIS": "INTRADAY", "CNC": "DELIVERY"}[product.upper()]
    angel = [
        {"variety": "NORMAL", "tradingsymbol": f"{sym}-EQ", "symboltoken": "<from Angel's instrument list>", "exchange": "NSE",
         "transactiontype": side, "ordertype": "LIMIT", "producttype": angel_product, "duration": "DAY", "price": str(price),
         "quantity": str(qty)},
        {"variety": "STOPLOSS", "tradingsymbol": f"{sym}-EQ", "symboltoken": "<from Angel's instrument list>", "exchange": "NSE",
         "transactiontype": exit_side, "ordertype": "STOPLOSS_MARKET", "producttype": angel_product, "duration": "DAY",
         "triggerprice": str(stop), "quantity": str(qty)},
        {"variety": "NORMAL", "tradingsymbol": f"{sym}-EQ", "symboltoken": "<from Angel's instrument list>", "exchange": "NSE",
         "transactiontype": exit_side, "ordertype": "LIMIT", "producttype": angel_product, "duration": "DAY",
         "price": str(target), "quantity": str(qty)},
    ]
    return {"paper_only": True, "note": "Not sent to any broker. Cancel the other exit order once one fills.",
            "zerodha_kite": kite, "angel_one": angel}


# ---------------------------------------------------------------- time machine

def monte_carlo(returns: list[float], entry: float, stop: float, target: float, paths: int = 10000, steps: int = 30,
                seed: int | None = 7) -> dict:
    """Replays [paths] made-up futures of [steps] candles each, every candle's move drawn from the stock's own past
    moves; how many reach the target first, the stop first, or neither."""
    if len(returns) < 30:
        raise DataError(f"only {len(returns)} past moves; the replay needs at least 30")
    rng = random.Random(seed)
    up = target > entry
    hit_t = hit_s = 0
    for _ in range(paths):
        p = entry
        for _ in range(steps):
            p *= 1 + rng.choice(returns)
            if (p >= target) if up else (p <= target):
                hit_t += 1
                break
            if (p <= stop) if up else (p >= stop):
                hit_s += 1
                break
    return {"paths": paths, "steps": steps, "target_first_pct": round(100 * hit_t / paths, 2),
            "stop_first_pct": round(100 * hit_s / paths, 2), "neither_pct": round(100 * (paths - hit_t - hit_s) / paths, 2),
            "moves_used": len(returns)}


def returns_of(bars: list[dict]) -> list[float]:
    """Candle-to-candle moves; for candles inside a day, only within each day (the overnight gap left out)."""
    gaps = sorted(b["time"] - a["time"] for a, b in zip(bars, bars[1:]))
    intraday = bool(gaps) and gaps[len(gaps) // 2] < timedelta(hours=12)
    out = []
    for a, b in zip(bars, bars[1:]):
        if intraday and a["time"].astimezone(IST).date() != b["time"].astimezone(IST).date():
            continue
        if a["close"]:
            out.append(b["close"] / a["close"] - 1)
    return out


# ---------------------------------------------------------------- insider and results

def _crore(rupees) -> str:
    return "-" if rupees is None else f"₹{rupees / 1e7:,.2f} Cr"


def insider_report(symbol: str) -> dict:
    row, hist = rs_stock(symbol), rs_history(symbol)
    f = row.get("f", {})
    year_ago = (datetime.now(IST) - timedelta(days=365)).strftime("%Y-%m-%d")

    def trades(rows, n=10):
        recent = [r for r in rows if r and r[0] >= year_ago]
        out = []
        for r in sorted(rows, key=lambda r: r[0], reverse=True)[:n]:
            out.append({"day": r[0], "side": r[1], "value": _crore(r[2]), "shares": r[3] if len(r) > 3 else None,
                        "who": r[4] if len(r) > 4 else None, "as": r[5] if len(r) > 5 else None,
                        "how": r[6] if len(r) > 6 else None})
        buys = sum(r[2] or 0 for r in recent if r[1] == "buy")
        sells = sum(r[2] or 0 for r in recent if r[1] == "sell")
        return out, {"buys_12m": _crore(buys), "sells_12m": _crore(sells), "count_12m": len(recent)}

    insiders, insider_sum = trades(hist.get("i") or [])
    deals, deal_sum = trades(hist.get("d") or [])
    holding = [{"quarter": s[0], "promoter": s[1], "fii": s[2], "dii": s[3], "public": s[4] if len(s) > 4 else None}
               for s in (hist.get("s") or [])[-4:]]
    return {"symbol": symbol.upper(), "data_as_of": row.get("_updated"), "insider_trades_12m": insider_sum,
            "recent_insider_trades": insiders, "bulk_block_deals_12m": deal_sum, "recent_deals": deals,
            "promoter_pledged_pct": f.get("pl"), "promoter_holding_change_pct": f.get("pmc"), "shareholding_last_4q": holding}


def analyst_report(symbol: str) -> dict:
    row, hist = rs_stock(symbol), rs_history(symbol)
    f = row.get("f", {})
    q = hist.get("q") or []
    by_period = {r[0]: r for r in q}
    quarters = []
    for r in q[-8:]:
        prev = by_period.get(f"{int(r[0][:2]) - 1:02d}{r[0][2:]}")
        yoy = lambda i: (round(100 * (r[i] - prev[i]) / abs(prev[i]), 1) if prev and r[i] is not None and prev[i] else None)
        quarters.append({"quarter": r[0], "sales_cr": r[1], "net_profit_cr": r[2], "eps": r[3],
                         "net_margin_pct": round(100 * r[2] / r[1], 1) if r[1] and r[2] is not None else None,
                         "sales_yoy_pct": yoy(1), "profit_yoy_pct": yoy(2)})
    years = [{"year": y[0], "sales_cr": y[1], "net_profit_cr": y[2], "operating_cash_flow_cr": y[3] if len(y) > 3 else None,
              "free_cash_flow_cr": y[4] if len(y) > 4 else None, "debt_cr": y[5] if len(y) > 5 else None}
             for y in (hist.get("y") or [])[-5:]]
    return {"symbol": symbol.upper(), "data_as_of": row.get("_updated"), "rs_score": row.get("s"), "rs_state": row.get("st"),
            "latest_quarter": row.get("q"), "pe": f.get("pe"), "debt_to_equity": f.get("de"),
            "revenue_growth_pct": f.get("rv"), "profit_growth_pct": f.get("np"), "net_margin_pct": f.get("nm"),
            "quarters": quarters, "years": years}


# ---------------------------------------------------------------- command line

def _fmt(x):
    if isinstance(x, datetime):
        return x.strftime("%Y-%m-%d %H:%M")
    return x


def show(obj) -> None:
    print(json.dumps(obj, default=_fmt, ensure_ascii=False, indent=1))


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("smc", help="fair value gaps, order blocks and liquidity sweeps")
    s.add_argument("symbol"); s.add_argument("--interval", default="1d"); s.add_argument("--csv"); s.add_argument("--lookback", type=int, default=60)
    s = sub.add_parser("entry", help="opening-range breakout status of the latest session")
    s.add_argument("symbol"); s.add_argument("--orb-minutes", type=int, default=15); s.add_argument("--csv")
    s = sub.add_parser("exit", help="stop and R-multiple targets")
    s.add_argument("--entry", type=float, required=True); s.add_argument("--orb-high", type=float, required=True)
    s.add_argument("--orb-low", type=float, required=True); s.add_argument("--side", default="BUY")
    s.add_argument("--r", type=float, nargs="+", default=[1.5, 2.0]); s.add_argument("--atr", type=float); s.add_argument("--atr-mult", type=float, default=1.0)
    s = sub.add_parser("budget", help="position size within the risk and the margin")
    s.add_argument("--capital", type=float, required=True); s.add_argument("--entry", type=float, required=True)
    s.add_argument("--stop", type=float, required=True); s.add_argument("--risk-pct", type=float, default=1.0)
    s.add_argument("--risk-rs", type=float); s.add_argument("--product", default="MIS", choices=["MIS", "CNC", "mis", "cnc"])
    s.add_argument("--leverage", type=float, default=5.0)
    s = sub.add_parser("risk", help="pre-trade guard: day loss, trade count, time")
    s.add_argument("--loss", type=float, required=True, help="what this trade loses if stopped out (₹)")
    s.add_argument("--day-loss", type=float, default=0.0); s.add_argument("--trades", type=int, default=0)
    s.add_argument("--time"); s.add_argument("--max-loss", type=float, default=RISK_RULES["max_daily_loss"])
    s.add_argument("--max-trades", type=int, default=RISK_RULES["max_trades_per_day"]); s.add_argument("--no-entry-after", default=RISK_RULES["no_entry_after"])
    s = sub.add_parser("alerts", help="stop, target and square-off alerts")
    s.add_argument("--cmp", type=float); s.add_argument("--symbol"); s.add_argument("--stop", type=float, required=True)
    s.add_argument("--target", type=float, required=True); s.add_argument("--side", default="BUY"); s.add_argument("--time")
    s = sub.add_parser("ticket", help="paper order tickets for Zerodha Kite and Angel One")
    s.add_argument("symbol"); s.add_argument("--side", required=True); s.add_argument("--qty", type=int, required=True)
    s.add_argument("--price", type=float, required=True); s.add_argument("--stop", type=float, required=True)
    s.add_argument("--target", type=float, required=True); s.add_argument("--product", default="MIS", choices=["MIS", "CNC", "mis", "cnc"])
    s = sub.add_parser("timemachine", help="10,000 replayed futures from the stock's own moves")
    s.add_argument("symbol"); s.add_argument("--entry", type=float, required=True); s.add_argument("--stop", type=float, required=True)
    s.add_argument("--target", type=float, required=True); s.add_argument("--paths", type=int, default=10000)
    s.add_argument("--steps", type=int, default=30); s.add_argument("--interval", default="5m"); s.add_argument("--csv"); s.add_argument("--seed", type=int, default=7)
    s = sub.add_parser("insider", help="insider trades, bulk/block deals, pledges and shareholding")
    s.add_argument("symbol")
    s = sub.add_parser("analyst", help="quarterly results and fundamentals")
    s.add_argument("symbol")
    a = p.parse_args(argv)

    try:
        if a.cmd == "smc":
            bars = load_bars(a, a.interval)
            show({"symbol": a.symbol.upper(), "interval": a.interval, "candles": len(bars), "last_close": bars[-1]["close"],
                  "fair_value_gaps": [g for g in fair_value_gaps(bars, a.lookback) if not g["filled"]][-5:],
                  "order_blocks": [o for o in order_blocks(bars, a.lookback) if not o["mitigated"]][-5:],
                  "liquidity_sweeps": liquidity_sweeps(bars, lookback=a.lookback)[-5:]})
        elif a.cmd == "entry":
            bars = bars_from_csv(a.csv) if a.csv else bars_from_yahoo(a.symbol, "5m", "5d")
            orb = opening_range(bars, a.orb_minutes)
            if orb.get("status") == "TRIGGERED":
                day = [b for b in bars if b["time"].astimezone(IST).date() == bars[-1]["time"].astimezone(IST).date()]
                gaps = [g for g in fair_value_gaps(day, len(day)) if not g["filled"]]
                want = "BULLISH_FVG" if orb["direction"] == "BUY" else "BEARISH_FVG"
                orb["smc_confirmation"] = any(g["type"] == want for g in gaps)
            show({"symbol": a.symbol.upper(), **orb})
        elif a.cmd == "exit":
            show(plan_exit(a.entry, a.orb_high, a.orb_low, a.side, a.r, a.atr, a.atr_mult))
        elif a.cmd == "budget":
            show(position_size(a.capital, a.entry, a.stop, a.risk_rs, a.risk_pct, a.product, a.leverage))
        elif a.cmd == "risk":
            rules = {**RISK_RULES, "max_daily_loss": a.max_loss, "max_trades_per_day": a.max_trades, "no_entry_after": a.no_entry_after}
            verdict, why = validate_trade(a.loss, a.day_loss, a.trades, now_ist(a.time), rules)
            show({"verdict": verdict, "reasons": why or ["within the day's loss limit, trade count and entry time"], "rules": rules})
        elif a.cmd == "alerts":
            cmp = a.cmp if a.cmp is not None else bars_from_yahoo(a.symbol, "1m", "1d")[-1]["close"]
            show({"cmp": cmp, "time_ist": now_ist(a.time), "alerts": check_alerts(cmp, a.stop, a.target, a.side, a.time) or ["no alert"]})
        elif a.cmd == "ticket":
            show(broker_tickets(a.symbol, a.side, a.qty, a.price, a.stop, a.target, a.product))
        elif a.cmd == "timemachine":
            bars = bars_from_csv(a.csv) if a.csv else bars_from_yahoo(a.symbol, a.interval, "60d" if a.interval in ("5m", "15m") else None)
            show({"symbol": a.symbol.upper(), "interval": a.interval,
                  **monte_carlo(returns_of(bars), a.entry, a.stop, a.target, a.paths, a.steps, a.seed)})
        elif a.cmd == "insider":
            show(insider_report(a.symbol))
        elif a.cmd == "analyst":
            show(analyst_report(a.symbol))
    except DataError as e:
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
