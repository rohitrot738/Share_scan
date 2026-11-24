"""Strict decoder for vendor-normalized executed-trade CSV/JSON records.

This is deliberately NOT an NSE native packet decoder. Map a licensed vendor's
documented schema into these canonical fields only after validating its semantics.
"""
from __future__ import annotations
import csv
import json
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Iterable
from .footprint import Trade

REQUIRED = {"symbol", "timestamp", "price", "quantity", "aggressor"}

def decode_record(row: dict, *, source: str = "NORMALIZED_FILE") -> Trade:
    missing = REQUIRED - set(row)
    if missing:
        raise ValueError(f"missing required fields: {', '.join(sorted(missing))}")
    try:
        ts = datetime.fromisoformat(str(row["timestamp"]).replace("Z", "+00:00"))
        if ts.tzinfo is None or ts.utcoffset() is None:
            raise ValueError("timestamp must include timezone offset")
        price = Decimal(str(row["price"]))
        quantity_raw = str(row["quantity"])
        quantity = int(quantity_raw)
        if str(quantity) != quantity_raw.strip() and not isinstance(row["quantity"], int):
            raise ValueError("quantity must be an integer")
        aggressor = str(row["aggressor"]).strip().lower()
        if aggressor not in ("buy", "sell"):
            raise ValueError("aggressor must be explicitly buy or sell")
        return Trade(
            symbol=str(row["symbol"]).strip().upper(),
            timestamp=ts,
            price=price,
            quantity=quantity,
            aggressor=aggressor,
            trade_id=(str(row["trade_id"]).strip() or None) if row.get("trade_id") is not None else None,
        )
    except (InvalidOperation, TypeError, OverflowError) as exc:
        raise ValueError(f"invalid normalized trade record: {exc}") from exc

def decode_csv(path: str | Path) -> list[Trade]:
    with Path(path).open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames or not REQUIRED.issubset(set(reader.fieldnames)):
            raise ValueError(f"CSV header must contain: {', '.join(sorted(REQUIRED))}")
        return [decode_record(row, source="CSV") for row in reader]

def decode_json(path: str | Path) -> list[Trade]:
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(payload, dict):
        payload = payload.get("trades")
    if not isinstance(payload, list):
        raise ValueError("JSON must be an array of trades or an object with a trades array")
    return [decode_record(row, source="JSON") for row in payload]

def decode_file(path: str | Path) -> list[Trade]:
    suffix = Path(path).suffix.lower()
    if suffix == ".csv": return decode_csv(path)
    if suffix in (".json", ".jsonl"):
        if suffix == ".jsonl":
            rows = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
            return [decode_record(row, source="JSONL") for row in rows]
        return decode_json(path)
    raise ValueError("supported formats: .csv, .json, .jsonl")
