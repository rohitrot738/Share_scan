from __future__ import annotations

import os
from pathlib import Path


def configure(base_dir: str) -> Path:
    root = Path(base_dir)
    root.mkdir(parents=True, exist_ok=True)
    os.environ["SCAN_CACHE_DIR"] = str(root / "cache")
    os.environ["SCAN_OUTPUT_DIR"] = str(root / "scan_output")
    return root


def build_cache(base_dir: str) -> str:
    configure(base_dir)
    from build_nse_cache import main
    main()
    return "NSE cache ready"


def run_scan(base_dir: str, top: int = 100, shortlist: int = 500) -> str:
    configure(base_dir)
    import live_scan
    if not live_scan.cache_is_valid():
        return "CACHE_MISSING: Build NSE cache first."
    cache = live_scan.load_stage1_cache(max(shortlist, top))
    started = __import__("time").perf_counter()
    result = live_scan.stage2(cache, top, started)
    live_scan.save_results(result, cache, __import__("time").perf_counter() - started)
    return str(live_scan.OUTPUT_DIR / "dashboard.html")
