from __future__ import annotations

import argparse
import json
import math
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterable

import pandas as pd

from cr360.scan_integration import _one as analyse_360cr_symbol
from market_cap import apply_market_cap_filter, fetch_nse_issued_capital
from scanner import (
    analyse_symbol,
    download_all_frames,
    load_market,
    stage1_full_market,
)


PROCESS_ORDER = [
    "market_cap",
    "cr360",
    "volume",
    "ghost_ready_confirmed",
    "ghost_score",
    "timeframes",
    "false_breakout",
    "valid_entry",
    "zero_advanced_veto",
]


@dataclass(frozen=True)
class OrderedThresholds:
    min_market_cap_cr: float = 1000.0
    min_cr360_score: float = 62.0
    min_cr360_confidence: float = 60.0
    min_rvol20: float = 1.5
    min_ghost_score: float = 80.0
    max_false_breakout_risk: float = 35.0
    required_timeframes: tuple[str, ...] = ("15m", "1h", "1d")


def _number(value, default: float | None = None) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    return result if math.isfinite(result) else default


def _timeframes(row: dict) -> set[str]:
    return {
        value.strip()
        for value in str(row.get("timeframes_used") or "").split(",")
        if value.strip()
    }


def _valid_plan(row: dict) -> bool:
    entry = _number(row.get("entry"))
    stop = _number(row.get("stop"))
    target1 = _number(row.get("target1"))
    target2 = _number(row.get("target2"))
    return (
        None not in (entry, stop, target1, target2)
        and stop < entry < target1 <= target2
    )


def _gate_functions(
    thresholds: OrderedThresholds,
) -> list[tuple[str, str, Callable[[dict], bool]]]:
    required = set(thresholds.required_timeframes)
    return [
        (
            "market_cap",
            "market_cap",
            lambda row: _number(row.get("market_cap_cr"), -1.0)
            > thresholds.min_market_cap_cr,
        ),
        (
            "cr360",
            "cr360",
            lambda row: (
                _number(row.get("cr360_score"), -1.0) >= thresholds.min_cr360_score
                and _number(row.get("cr360_confidence"), -1.0)
                >= thresholds.min_cr360_confidence
                and row.get("cr360_state") in {"POSITIVE", "HIGH_CONVICTION"}
            ),
        ),
        (
            "volume",
            "volume",
            lambda row: (
                _number(row.get("rvol20"), -1.0) >= thresholds.min_rvol20
                and _number(row.get("volume"), 0.0) > 0
            ),
        ),
        (
            "ghost_ready_confirmed",
            "stage4_ready_confirmed",
            lambda row: row.get("ghost_state") in {"READY", "CONFIRMED"},
        ),
        (
            "ghost_score",
            "stage5_ghost_score",
            lambda row: _number(row.get("ghost_score"), -1.0)
            >= thresholds.min_ghost_score,
        ),
        (
            "timeframes",
            "timeframes",
            lambda row: required.issubset(_timeframes(row)),
        ),
        (
            "false_breakout",
            "false_breakout",
            lambda row: _number(row.get("false_breakout_risk"), 101.0)
            <= thresholds.max_false_breakout_risk,
        ),
        ("valid_entry", "valid_entry", _valid_plan),
        (
            "zero_advanced_veto",
            "final_pass",
            lambda row: _number(row.get("advanced_veto_count"), 999.0) == 0,
        ),
    ]


def evaluate_ordered_results(
    rows: Iterable[dict],
    thresholds: OrderedThresholds | None = None,
) -> dict:
    """Apply the requested gates sequentially without mutating input rows."""
    thresholds = thresholds or OrderedThresholds()
    original = [dict(row) for row in rows]
    current = original
    output: dict[str, object] = {
        "process_order": list(PROCESS_ORDER),
        "thresholds": asdict(thresholds),
        "counts": {"input": len(original)},
    }
    for _, output_key, predicate in _gate_functions(thresholds):
        current = [row for row in current if predicate(row)]
        output[output_key] = current
        output["counts"][output_key] = len(current)
    return output


def _enrich_360cr(rows: list[dict], workers: int) -> tuple[list[dict], dict[str, str]]:
    enriched: list[dict] = []
    errors: dict[str, str] = {}
    completed: dict[str, dict] = {}
    with ThreadPoolExecutor(max_workers=max(1, min(int(workers), 12))) as executor:
        futures = {
            executor.submit(analyse_360cr_symbol, str(row["symbol"])): str(row["symbol"])
            for row in rows
        }
        total = len(futures)
        for index, future in enumerate(as_completed(futures), 1):
            symbol = futures[future]
            try:
                completed[symbol] = future.result()
            except Exception as exc:
                errors[symbol] = f"{type(exc).__name__}: {exc}"
            if index % 25 == 0 or index == total:
                print(f"360CR stage: {index}/{total}", flush=True)
    for row in rows:
        symbol = str(row["symbol"])
        result = completed.get(symbol)
        if result is None:
            continue
        enriched.append({**row, **result})
    return enriched, errors


def _compact(row: dict) -> dict:
    fields = (
        "symbol",
        "exchange",
        "name",
        "price",
        "market_cap_cr",
        "market_cap_source_date",
        "volume",
        "avg_volume20",
        "rvol20",
        "baseline_score",
        "cr360_score",
        "cr360_state",
        "cr360_confidence",
        "cr360_complete",
        "ghost_state",
        "ghost_score",
        "ghost_confidence",
        "volume_flow_score",
        "false_breakout_risk",
        "advanced_veto_count",
        "timeframes_used",
        "timeframe_count",
        "entry",
        "stop",
        "target1",
        "target2",
        "target3",
        "rr1",
        "rr2",
        "rr3",
    )
    return {field: row.get(field) for field in fields}


def _write_rows(output_dir: Path, name: str, rows: list[dict]) -> None:
    compact = [_compact(row) for row in rows]
    (output_dir / f"{name}.json").write_text(
        json.dumps(compact, indent=2, default=str),
        encoding="utf-8",
    )
    pd.DataFrame(compact).to_csv(output_dir / f"{name}.csv", index=False)


def run_ordered_scan(
    *,
    thresholds: OrderedThresholds,
    cr360_workers: int = 8,
    ghost_limit: int = 0,
    output_dir: str = "ordered_scan_results",
) -> dict:
    universe = load_market("NSE")
    print(f"Stage 1 — NSE universe: {len(universe)}", flush=True)
    daily_rows, daily_errors = stage1_full_market(universe)
    if not daily_rows:
        raise RuntimeError("daily market stage produced no usable rows")

    snapshot = fetch_nse_issued_capital()
    market_cap_rows, market_cap_stats = apply_market_cap_filter(
        daily_rows,
        snapshot,
        min_market_cap_cr=thresholds.min_market_cap_cr,
    )
    print(
        f"Stage 1 — market cap pass: {len(market_cap_rows)}/{len(daily_rows)}",
        flush=True,
    )

    cr360_rows, cr360_errors = _enrich_360cr(market_cap_rows, cr360_workers)
    cr360_pass = [
        row
        for row in cr360_rows
        if _gate_functions(thresholds)[1][2](row)
    ]
    print(f"Stage 2 — 360CR pass: {len(cr360_pass)}/{len(market_cap_rows)}", flush=True)

    volume_pass = [
        row
        for row in cr360_pass
        if _gate_functions(thresholds)[2][2](row)
    ]
    volume_pass.sort(
        key=lambda row: (
            _number(row.get("rvol20"), 0.0),
            _number(row.get("volume"), 0.0),
            _number(row.get("turnover"), 0.0),
        ),
        reverse=True,
    )
    print(f"Stage 3 — volume pass: {len(volume_pass)}/{len(cr360_pass)}", flush=True)

    ghost_input = volume_pass[:ghost_limit] if ghost_limit > 0 else volume_pass
    instrument_map = {(item.exchange, item.symbol): item for item in universe}
    ghost_instruments = [
        instrument_map[(row["exchange"], row["symbol"])]
        for row in ghost_input
        if (row["exchange"], row["symbol"]) in instrument_map
    ]
    frames, feed_errors = download_all_frames(ghost_instruments)
    ghost_rows: list[dict] = []
    ghost_errors: dict[str, str] = {}
    for row in ghost_input:
        key = (row["exchange"], row["symbol"])
        instrument = instrument_map.get(key)
        if instrument is None:
            ghost_errors[str(row["symbol"])] = "instrument metadata missing"
            continue
        try:
            ghost_rows.append(
                analyse_symbol(
                    instrument,
                    frames.get(instrument.yahoo_symbol, {}),
                    row,
                )
            )
        except Exception as exc:
            ghost_errors[str(row["symbol"])] = f"{type(exc).__name__}: {exc}"

    evaluated = evaluate_ordered_results(ghost_rows, thresholds)
    stage4 = list(evaluated["stage4_ready_confirmed"])
    stage5 = list(evaluated["stage5_ghost_score"])
    final_pass = list(evaluated["final_pass"])
    print(f"Stage 4 — READY/CONFIRMED: {len(stage4)}", flush=True)
    print(f"Stage 5 — Ghost score pass: {len(stage5)}", flush=True)
    print(f"Final — all later gates pass: {len(final_pass)}", flush=True)

    counts = {
        "daily_usable": len(daily_rows),
        "market_cap": len(market_cap_rows),
        "cr360_collected": len(cr360_rows),
        "cr360": len(cr360_pass),
        "volume": len(volume_pass),
        "ghost_attempted": len(ghost_input),
        "ghost_analysed": len(ghost_rows),
        "stage4_ready_confirmed": len(stage4),
        "stage5_ghost_score": len(stage5),
        "timeframes": len(evaluated["timeframes"]),
        "false_breakout": len(evaluated["false_breakout"]),
        "valid_entry": len(evaluated["valid_entry"]),
        "final_pass": len(final_pass),
    }
    payload = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "mode": "ORDERED_ALL_CHECKS_NSE_SCAN",
        "process_order": list(PROCESS_ORDER),
        "thresholds": asdict(thresholds),
        "counts": counts,
        "market_cap_source_date": snapshot.as_of.isoformat(),
        "market_cap_stats": market_cap_stats,
        "errors": {
            "daily": daily_errors,
            "cr360": cr360_errors,
            "feeds": feed_errors,
            "ghost": ghost_errors,
        },
        "stage4_ready_confirmed": [_compact(row) for row in stage4],
        "stage5_ghost_score": [_compact(row) for row in stage5],
        "final_pass": [_compact(row) for row in final_pass],
    }

    destination = Path(output_dir)
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "ordered_scan_summary.json").write_text(
        json.dumps(payload, indent=2, default=str),
        encoding="utf-8",
    )
    _write_rows(destination, "stage4_ready_confirmed", stage4)
    _write_rows(destination, "stage5_ghost_score", stage5)
    _write_rows(destination, "final_all_pass", final_pass)
    return payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", default="ordered_scan_results")
    parser.add_argument("--cr360-workers", type=int, default=8)
    parser.add_argument(
        "--ghost-limit",
        type=int,
        default=0,
        help="0 analyses every Stage-3 pass; positive values cap the Ghost stage",
    )
    parser.add_argument("--min-market-cap-cr", type=float, default=1000.0)
    parser.add_argument("--min-cr360-score", type=float, default=62.0)
    parser.add_argument("--min-cr360-confidence", type=float, default=60.0)
    parser.add_argument("--min-rvol20", type=float, default=1.5)
    parser.add_argument("--min-ghost-score", type=float, default=80.0)
    parser.add_argument("--max-false-breakout-risk", type=float, default=35.0)
    args = parser.parse_args()
    thresholds = OrderedThresholds(
        min_market_cap_cr=args.min_market_cap_cr,
        min_cr360_score=args.min_cr360_score,
        min_cr360_confidence=args.min_cr360_confidence,
        min_rvol20=args.min_rvol20,
        min_ghost_score=args.min_ghost_score,
        max_false_breakout_risk=args.max_false_breakout_risk,
    )
    run_ordered_scan(
        thresholds=thresholds,
        cr360_workers=args.cr360_workers,
        ghost_limit=max(0, args.ghost_limit),
        output_dir=args.output_dir,
    )


if __name__ == "__main__":
    main()
