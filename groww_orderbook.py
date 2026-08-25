from __future__ import annotations

import os
import time
from typing import Dict, Iterable, Tuple

import pandas as pd


def _num(v, default=0.0):
    try:
        return float(v)
    except Exception:
        return default


def _levels(book: dict) -> list[tuple[float, float]]:
    rows = []
    if not isinstance(book, dict):
        return rows
    for _, item in book.items():
        if not isinstance(item, dict):
            continue
        price = _num(item.get("price"))
        qty = _num(item.get("qty"))
        if price > 0 and qty >= 0:
            rows.append((price, qty))
    return rows


def score_depth_snapshot(payload: dict) -> dict:
    """Convert Groww 5-level market depth into a 0-100 demand score.

    50 = balanced book, >50 = bid-heavy, <50 = ask-heavy.
    Spread is reported separately in bps and used by the scanner as a penalty.
    """
    buys = _levels(payload.get("buyBook", {}))
    sells = _levels(payload.get("sellBook", {}))
    bid_qty = sum(q for _, q in buys)
    ask_qty = sum(q for _, q in sells)
    total = bid_qty + ask_qty
    imbalance = (bid_qty - ask_qty) / total if total > 0 else 0.0

    best_bid = max((p for p, _ in buys), default=0.0)
    best_ask = min((p for p, _ in sells), default=0.0)
    mid = (best_bid + best_ask) / 2.0 if best_bid > 0 and best_ask > 0 else 0.0
    spread_bps = ((best_ask - best_bid) / mid * 10000.0) if mid > 0 and best_ask >= best_bid else 0.0

    depth_score = max(0.0, min(100.0, 50.0 + 50.0 * imbalance))
    return {
        "depth_score": round(depth_score, 2),
        "bid_qty_5": round(bid_qty, 2),
        "ask_qty_5": round(ask_qty, 2),
        "imbalance": round(imbalance, 4),
        "best_bid": round(best_bid, 4),
        "best_ask": round(best_ask, 4),
        "spread_bps": round(spread_bps, 2),
        "depth_source": "GROWW_LIVE_5_LEVEL",
    }


def _find_depth(root: dict, exchange: str, token: str) -> dict:
    try:
        return root[str(exchange)]["CASH"][str(token)]
    except Exception:
        return {}


def fetch_depth_scores(rows: Iterable[Tuple[str, str]], wait_seconds: float | None = None) -> Dict[Tuple[str, str], dict]:
    """Fetch true 5-level market depth for (exchange, trading_symbol) pairs.

    Requires environment variable GROWW_ACCESS_TOKEN and an active Groww Trading API subscription.
    Returns an empty dict when credentials/feed are unavailable, so the scanner can safely fall back.
    """
    token = os.getenv("GROWW_ACCESS_TOKEN", "").strip()
    if not token:
        return {}

    try:
        from growwapi import GrowwAPI, GrowwFeed
    except Exception as exc:
        print(f"[WARN] growwapi unavailable: {exc}")
        return {}

    pairs = [(str(ex).upper(), str(sym).strip()) for ex, sym in rows]
    pairs = list(dict.fromkeys(pairs))
    if not pairs:
        return {}

    groww = GrowwAPI(token)
    instruments = groww.get_all_instruments()
    if not isinstance(instruments, pd.DataFrame) or instruments.empty:
        return {}

    x = instruments.copy()
    for col in ("exchange", "trading_symbol", "segment", "exchange_token"):
        if col not in x.columns:
            return {}
    x["exchange"] = x["exchange"].astype(str).str.upper()
    x["trading_symbol"] = x["trading_symbol"].astype(str)
    x["segment"] = x["segment"].astype(str).str.upper()
    x = x[x["segment"].eq("CASH")]

    token_map = {
        (r.exchange, r.trading_symbol): str(r.exchange_token)
        for r in x[["exchange", "trading_symbol", "exchange_token"]].itertuples(index=False)
    }

    subscriptions = []
    reverse = {}
    for ex, sym in pairs:
        exch_token = token_map.get((ex, sym))
        if not exch_token:
            continue
        subscriptions.append({"exchange": ex, "segment": "CASH", "exchange_token": exch_token})
        reverse[(ex, exch_token)] = (ex, sym)

    if not subscriptions:
        return {}

    feed = GrowwFeed(groww)
    try:
        feed.subscribe_market_depth(subscriptions)
        time.sleep(wait_seconds if wait_seconds is not None else float(os.getenv("GROWW_DEPTH_WAIT_SECONDS", "1.5")))
        root = feed.get_market_depth() or {}
    finally:
        try:
            feed.unsubscribe_market_depth(subscriptions)
        except Exception:
            pass

    out: Dict[Tuple[str, str], dict] = {}
    for sub in subscriptions:
        ex = str(sub["exchange"])
        exch_token = str(sub["exchange_token"])
        pair = reverse.get((ex, exch_token))
        if not pair:
            continue
        depth = _find_depth(root, ex, exch_token)
        if depth:
            out[pair] = score_depth_snapshot(depth)
    return out
