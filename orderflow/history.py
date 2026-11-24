"""Historical replay over normalized executed-trade files."""
from __future__ import annotations
import argparse
import json
from decimal import Decimal
from pathlib import Path
from .decoder import decode_file
from .footprint import FootprintBuilder
from .signals import analyze_footprint

def replay(path: str | Path, *, tick_size: str, bar_seconds: int = 60) -> list[dict]:
    trades = decode_file(path)
    bars = FootprintBuilder(Decimal(tick_size), bar_seconds=bar_seconds).build(trades)
    return [analyze_footprint(bar) for bar in bars]

def main() -> None:
    parser = argparse.ArgumentParser(description="Replay normalized historical trades into footprint bars")
    parser.add_argument("file", help="normalized CSV, JSON or JSONL trade file")
    parser.add_argument("--tick-size", required=True, help="instrument tick size, e.g. 0.05")
    parser.add_argument("--bar-seconds", type=int, default=60)
    parser.add_argument("--output", help="optional JSON output path")
    args = parser.parse_args()
    result = replay(args.file, tick_size=args.tick_size, bar_seconds=args.bar_seconds)
    rendered = json.dumps({"data_status":"HISTORICAL_FILE_REPLAY","source_file":Path(args.file).name,"bars":result}, indent=2)
    if args.output: Path(args.output).write_text(rendered + "\\n", encoding="utf-8")
    else: print(rendered)

if __name__ == "__main__": main()
