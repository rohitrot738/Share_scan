from __future__ import annotations

import json
import sqlite3
import threading
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any

from .models import ResearchInput


SECTION_MAX_AGE_SECONDS = {
    "market": 15 * 60,
    "financials": 7 * 24 * 60 * 60,
    "shareholding": 24 * 60 * 60,
    "regulatory": 6 * 60 * 60,
}


class PersistentResearchCache:
    """SQLite-backed 360CR cache safe for repeated and concurrent scans."""

    def __init__(self, path: str = ".scan_cache/cr360.sqlite3"):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._init_db()

    def _connect(self) -> sqlite3.Connection:
        con = sqlite3.connect(self.path, timeout=30)
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("PRAGMA busy_timeout=30000")
        return con

    def _init_db(self) -> None:
        with self._connect() as con:
            con.execute(
                """CREATE TABLE IF NOT EXISTS research_sections (
                    symbol TEXT NOT NULL, section TEXT NOT NULL, payload TEXT NOT NULL,
                    saved_at REAL NOT NULL, PRIMARY KEY(symbol, section)
                )"""
            )

    def _put_many_locked(
        self, con: sqlite3.Connection, symbol: str,
        sections: dict[str, Any], saved_at: float | None = None,
    ) -> None:
        timestamp = saved_at if saved_at is not None else time.time()
        normalized = symbol.replace(".NS", "").upper()
        rows = [
            (normalized, section, json.dumps(payload, default=str, separators=(",", ":")), timestamp)
            for section, payload in sections.items()
        ]
        con.executemany(
            """INSERT INTO research_sections(symbol, section, payload, saved_at)
               VALUES(?, ?, ?, ?) ON CONFLICT(symbol, section) DO UPDATE SET
               payload=excluded.payload, saved_at=excluded.saved_at""",
            rows,
        )

    def put(self, symbol: str, section: str, payload: Any, saved_at: float | None = None) -> None:
        with self._lock, self._connect() as con:
            self._put_many_locked(con, symbol, {section: payload}, saved_at)

    def put_many(self, symbol: str, sections: dict[str, Any], saved_at: float | None = None) -> None:
        """Persist multiple sections in one SQLite transaction."""
        if not sections:
            return
        with self._lock, self._connect() as con:
            self._put_many_locked(con, symbol, sections, saved_at)

    def get(self, symbol: str, section: str, max_age_seconds: int | None = None) -> dict:
        with self._lock, self._connect() as con:
            row = con.execute(
                "SELECT payload, saved_at FROM research_sections WHERE symbol=? AND section=?",
                (symbol.replace(".NS", "").upper(), section),
            ).fetchone()
        if row is None:
            return {"status": "MISS", "payload": None, "saved_at": None, "age_seconds": None}
        payload, saved_at = json.loads(row[0]), float(row[1])
        age = max(0.0, time.time() - saved_at)
        limit = SECTION_MAX_AGE_SECONDS.get(section) if max_age_seconds is None else max_age_seconds
        status = "HIT" if limit is None or age <= limit else "STALE"
        return {"status": status, "payload": payload, "saved_at": saved_at, "age_seconds": age}

    def store_research(self, value: ResearchInput) -> None:
        data = asdict(value)
        sections = {
            "market": {k: data[k] for k in ("symbol", "price", "price_history", "valuation")},
            "financials": {k: data[k] for k in ("quarterly_financials", "balance_sheet_quarters", "cashflow_quarters")},
            "shareholding": {"shareholding_quarters": data["shareholding_quarters"]},
            "regulatory": {k: data[k] for k in ("insider_transactions", "bulk_block_deals", "corporate_actions")},
            "metadata": {"metadata": data.get("metadata", {})},
        }
        self.put_many(value.symbol, sections)

    def store_regulatory(self, value: ResearchInput) -> None:
        self.put_many(value.symbol, {
            "shareholding": {"shareholding_quarters": value.shareholding_quarters},
            "regulatory": {
                "insider_transactions": value.insider_transactions,
                "bulk_block_deals": value.bulk_block_deals,
                "corporate_actions": value.corporate_actions,
            },
            "metadata": {"metadata": value.metadata},
        })

    def load_research(self, symbol: str, allow_stale: bool = True) -> tuple[ResearchInput | None, dict[str, str]]:
        names = ("market", "financials", "shareholding", "regulatory", "metadata")
        found = {name: self.get(symbol, name) for name in names}
        states = {name: found[name]["status"] for name in names}
        required = ("market", "financials")
        if any(states[name] == "MISS" for name in required):
            return None, states
        if not allow_stale and any(states[name] == "STALE" for name in required):
            return None, states
        merged: dict[str, Any] = {"symbol": symbol.replace(".NS", "").upper()}
        for name in names:
            if found[name]["payload"] is not None:
                merged.update(found[name]["payload"])
        merged.setdefault("metadata", {})["cache_sections"] = states
        return ResearchInput(**merged), states
