from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any


class CheckpointStore:
    def __init__(self, path: str = ".scan_cache/checkpoints.sqlite3"):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        with self._connect() as con:
            con.execute(
                "CREATE TABLE IF NOT EXISTS jobs("
                "run_id TEXT,item_key TEXT,status TEXT,payload TEXT,error TEXT,"
                "updated_at REAL,PRIMARY KEY(run_id,item_key))"
            )

    def _connect(self):
        con = sqlite3.connect(self.path, timeout=30)
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("PRAGMA busy_timeout=30000")
        return con

    def success(self, run_id: str, item_key: str, payload: Any) -> None:
        self._put(run_id, item_key, "SUCCESS", payload, None)

    def failure(self, run_id: str, item_key: str, error: str) -> None:
        self._put(run_id, item_key, "FAILED", None, error)

    def _put(self, run_id, item_key, status, payload, error):
        body = None if payload is None else json.dumps(payload, default=str, separators=(",", ":"))
        with self.lock, self._connect() as con:
            con.execute(
                "INSERT INTO jobs VALUES(?,?,?,?,?,?) "
                "ON CONFLICT(run_id,item_key) DO UPDATE SET "
                "status=excluded.status,payload=excluded.payload,error=excluded.error,"
                "updated_at=excluded.updated_at",
                (run_id, item_key, status, body, error, time.time()),
            )

    def completed(
        self, run_id: str, *, max_age_seconds: float | None = None
    ) -> dict[str, Any]:
        query = "SELECT item_key,payload FROM jobs WHERE run_id=? AND status='SUCCESS'"
        params: list[Any] = [run_id]
        if max_age_seconds is not None:
            if max_age_seconds < 0:
                raise ValueError("max_age_seconds must not be negative")
            query += " AND updated_at>=?"
            params.append(time.time() - max_age_seconds)
        with self.lock, self._connect() as con:
            rows = con.execute(query, params).fetchall()
        return {key: json.loads(payload) for key, payload in rows}

    def pending(
        self,
        run_id: str,
        item_keys: list[str],
        *,
        max_age_seconds: float | None = None,
    ) -> list[str]:
        done = set(self.completed(run_id, max_age_seconds=max_age_seconds))
        return [key for key in item_keys if key not in done]
