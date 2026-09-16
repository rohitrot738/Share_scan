from __future__ import annotations

import json
import os
from pathlib import Path


def run_scan(top: int = 100, shortlist: int = 500) -> str:
    """Run the real Share_scan engine locally inside the Android app."""
    home = Path(os.environ.get("HOME", "."))
    output = home / "scan_output"
    cache = home / "scan_cache"
    output.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)

    os.environ["SCAN_OUTPUT_DIR"] = str(output)
    os.environ["SCAN_CACHE_DIR"] = str(cache)
    os.environ["CACHE_BATCH_SIZE"] = "120"
    os.environ["CACHE_BATCH_SLEEP"] = "1.0"

    cache_file = cache / "nse_stage1.csv"
    if not cache_file.exists():
        import build_nse_cache
        build_nse_cache.main()

    import sys
    import live_scan

    sys.argv = ["live_scan.py", "--top", str(top), "--shortlist", str(shortlist)]
    live_scan.main()

    result_file = output / "top100_by_volume.json"
    if result_file.exists():
        return result_file.read_text(encoding="utf-8")

    return json.dumps({
        "status": "completed",
        "message": "Scan completed but no JSON result was produced."
    }, ensure_ascii=False, indent=2)
