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


def _levels(book) -> list[tuple[float, float]]:
    rows = []
    items = book.values() if isinstance(book, dict) else book if isinstance(book, list) else []
    for item in items:
        if not isinstance(item, dict):
            continue
        price = _num(item.get("price") or item.get("p"))
        qty = _num(item.get("qty") or item.get("quantity") or item.get("q"))
        if price > 0 and qty >= 0:
            rows.append((price, qty))
    return rows


def score_depth_snapshot(payload: dict) -> dict:
    if not isinstance(payload, dict):
        return {}
    buys = _levels(payload.get("buyBook") or payload.get("buy_book") or payload.get("bids") or {})
    sells = _levels(payload.get("sellBook") or payload.get("sell_book") or payload.get("asks") or {})
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
        "depth_score": round(depth_score, 2), "bid_qty_5": round(bid_qty, 2),
        "ask_qty_5": round(ask_qty, 2), "imbalance": round(imbalance, 4),
        "best_bid": round(best_bid, 4), "best_ask": round(best_ask, 4),
        "spread_bps": round(spread_bps, 2), "depth_source": "GROWW_LIVE_5_LEVEL",
    }


def _find_depth(root, exchange: str, token: str) -> dict:
    if not isinstance(root, dict):
        return {}
    candidates = [
        root.get(str(exchange), {}),
        root.get(str(exchange).upper(), {}),
        root.get(str(exchange).lower(), {}),
    ]
    for ex_root in candidates:
        if not isinstance(ex_root, dict):
            continue
        cash = ex_root.get("CASH") or ex_root.get("cash") or ex_root
        if isinstance(cash, dict):
            depth = cash.get(str(token)) or cash.get(token)
            if isinstance(depth, dict):
                return depth
    return {}


def fetch_depth_scores(rows: Iterable[Tuple[str, str]], wait_seconds: float | None = None) -> Dict[Tuple[str, str], dict]:
    token = os.getenv("GROWW_ACCESS_TOKEN", "").strip()
    if not token:
        print("[WARN] GROWW_ACCESS_TOKEN missing; using OHLCV fallback")
        return {}

    try:
        from growwapi import GrowwAPI, GrowwFeed
    except Exception as exc:
        print(f"[WARN] growwapi unavailable: {exc}")
        return {}

    pairs = list(dict.fromkeys((str(ex).upper(), str(sym).strip()) for ex, sym in rows if str(sym).strip()))
    if not pairs:
        return {}

    try:
        groww = GrowwAPI(token)
    except Exception as exc:
        print(f"[WARN] Groww client init failed: {exc}")
        return {}

    instruments = None
    for attempt in range(3):
        try:
            instruments = groww.get_all_instruments()
            if isinstance(instruments, pd.DataFrame) and not instruments.empty:
                break
        except Exception as exc:
            print(f"[WARN] Groww instruments attempt {attempt + 1} failed: {exc}")
        time.sleep(1.5 * (attempt + 1))
    if not isinstance(instruments, pd.DataFrame) or instruments.empty:
        return {}

    x = instruments.copy()
    required = ("exchange", "trading_symbol", "segment", "exchange_token")
    if any(col not in x.columns for col in required):
        print("[WARN] Groww instrument schema changed; using OHLCV fallback")
        return {}
    x["exchange"] = x["exchange"].astype(str).str.upper()
    x["trading_symbol"] = x["trading_symbol"].astype(str).str.strip()
    x["segment"] = x["segment"].astype(str).str.upper()
    x = x[x["segment"].eq("CASH")]
    token_map = {(r.exchange, r.trading_symbol): str(r.exchange_token) for r in x[list(required)].itertuples(index=False)}

    subscriptions, reverse = [], {}
    for ex, sym in pairs:
        exch_token = token_map.get((ex, sym))
        if not exch_token:
            continue
        subscriptions.append({"exchange": ex, "segment": "CASH", "exchange_token": exch_token})
        reverse[(ex, exch_token)] = (ex, sym)
    if not subscriptions:
        return {}

    wait = wait_seconds if wait_seconds is not None else _num(os.getenv("GROWW_DEPTH_WAIT_SECONDS", "1.5"), 1.5)
    root = {}
    feed = None
    try:
        feed = GrowwFeed(groww)
        feed.subscribe_market_depth(subscriptions)
        for attempt in range(3):
            time.sleep(max(0.5, wait if attempt == 0 else 1.0))
            try:
                root = feed.get_market_depth() or {}
            except Exception as exc:
                print(f"[WARN] Groww depth read attempt {attempt + 1} failed: {exc}")
                root = {}
            if root:
                break
    except Exception as exc:
        print(f"[WARN] Groww market depth unavailable: {exc}")
        return {}
    finally:
        if feed is not None:
            try:
                feed.unsubscribe_market_depth(subscriptions)
            except Exception:
                pass

    out: Dict[Tuple[str, str], dict] = {}
    for sub in subscriptions:
        ex, exch_token = str(sub["exchange"]), str(sub["exchange_token"])
        pair = reverse.get((ex, exch_token))
        if not pair:
            continue
        depth = _find_depth(root, ex, exch_token)
        score = score_depth_snapshot(depth) if depth else {}
        if score:
            out[pair] = score
    return out
