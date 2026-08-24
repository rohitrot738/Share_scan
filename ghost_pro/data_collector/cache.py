from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any


class JsonCache:
    def __init__(self, root: str = ".cache/share_scan", ttl_seconds: int = 21600):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.ttl_seconds = ttl_seconds

    def _path(self, key: str) -> Path:
        safe = key.replace("/", "_").replace(":", "_").replace(" ", "_")
        return self.root / f"{safe}.json"

    def get(self, key: str) -> Any | None:
        p = self._path(key)
        if not p.exists():
            return None
        try:
            obj = json.loads(p.read_text(encoding="utf-8"))
            if time.time() - float(obj.get("saved_at", 0)) > self.ttl_seconds:
                return None
            return obj.get("payload")
        except Exception:
            return None

    def set(self, key: str, payload: Any) -> None:
        p = self._path(key)
        p.write_text(json.dumps({"saved_at": time.time(), "payload": payload}, default=str), encoding="utf-8")
