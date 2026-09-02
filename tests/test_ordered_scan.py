from copy import deepcopy

from ordered_scan import OrderedThresholds, evaluate_ordered_results


def candidate(symbol: str, **changes) -> dict:
    row = {
        "symbol": symbol,
        "exchange": "NSE",
        "market_cap_cr": 5000.0,
        "cr360_score": 70.0,
        "cr360_state": "POSITIVE",
        "cr360_confidence": 70.0,
        "rvol20": 2.0,
        "volume": 1_000_000,
        "ghost_state": "READY",
        "ghost_score": 85.0,
        "timeframes_used": "15m,1h,1d",
        "false_breakout_risk": 25.0,
        "entry": 100.0,
        "stop": 95.0,
        "target1": 110.0,
        "target2": 120.0,
        "advanced_veto_count": 0,
    }
    row.update(changes)
    return row


def test_stage4_and_stage5_are_preserved_even_when_later_gates_fail():
    rows = [
        candidate("FINAL"),
        candidate("STAGE4_ONLY", ghost_score=75.0),
        candidate("STAGE5_ONLY", timeframes_used="15m,1h"),
    ]

    result = evaluate_ordered_results(rows)

    assert [r["symbol"] for r in result["stage4_ready_confirmed"]] == [
        "FINAL",
        "STAGE4_ONLY",
        "STAGE5_ONLY",
    ]
    assert [r["symbol"] for r in result["stage5_ghost_score"]] == [
        "FINAL",
        "STAGE5_ONLY",
    ]
    assert [r["symbol"] for r in result["final_pass"]] == ["FINAL"]


def test_requested_gate_order_is_fail_closed_and_strict():
    rows = [
        candidate("PASS"),
        candidate("MCAP_EQUAL", market_cap_cr=1000.0),
        candidate("CR360_LOW", cr360_score=61.99, cr360_state="NEUTRAL"),
        candidate("VOLUME_LOW", rvol20=1.49),
        candidate("GHOST_WATCH", ghost_state="WATCH"),
        candidate("SCORE_LOW", ghost_score=79.99),
        candidate("FALSE_RISK", false_breakout_risk=35.01),
        candidate("BAD_PLAN", stop=101.0),
        candidate("HAS_VETO", advanced_veto_count=1),
        candidate("MISSING_VALUE", cr360_score=None),
    ]
    original = deepcopy(rows)

    result = evaluate_ordered_results(rows, OrderedThresholds())

    assert result["process_order"] == [
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
    assert result["counts"] == {
        "input": 10,
        "market_cap": 9,
        "cr360": 7,
        "volume": 6,
        "stage4_ready_confirmed": 5,
        "stage5_ghost_score": 4,
        "timeframes": 4,
        "false_breakout": 3,
        "valid_entry": 2,
        "final_pass": 1,
    }
    assert [r["symbol"] for r in result["final_pass"]] == ["PASS"]
    assert rows == original

