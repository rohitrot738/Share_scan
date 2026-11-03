"""tools/trader.py: the /trader-* skills' calculations, on made-up candles (no network)."""
import os
import sys
from datetime import datetime, timedelta

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import trader  # noqa: E402

IST = trader.IST


def bar(t, o, h, l, c):
    return {"time": t, "open": o, "high": h, "low": l, "close": c, "volume": 1000}


def day(minutes_ohlc, date=datetime(2026, 11, 3, 9, 15, tzinfo=IST), step=5):
    return [bar(date + timedelta(minutes=i * step), *ohlc) for i, ohlc in enumerate(minutes_ohlc)]


def test_bullish_fair_value_gap_and_its_fill():
    bars = day([(100, 101, 99, 100.5), (101, 106, 100.8, 105.5), (105.5, 107, 102, 106.5), (106, 106.5, 104, 105)])
    gaps = trader.fair_value_gaps(bars)
    assert gaps[0]["type"] == "BULLISH_FVG" and (gaps[0]["bottom"], gaps[0]["top"]) == (101, 102)
    assert not gaps[0]["filled"] and not gaps[0]["touched"]
    filled = trader.fair_value_gaps(bars + day([(105, 105, 100.5, 101)], bars[-1]["time"] + timedelta(minutes=5)))
    assert filled[0]["filled"]


def test_bearish_order_block_after_a_strong_down_candle():
    bars = day([(100, 101, 99, 100), (100, 102, 99.5, 101.8), (101.8, 102, 96, 96.5)])
    obs = trader.order_blocks(bars)
    assert obs[-1]["type"] == "BEARISH_OB" and (obs[-1]["low"], obs[-1]["high"]) == (99.5, 102)
    assert not obs[-1]["mitigated"]


def test_liquidity_sweep_of_the_lows():
    bars = day([(100, 101, 99, 100)] * 5 + [(100, 100.5, 98, 100.2)])
    sweeps = trader.liquidity_sweeps(bars, swing=5)
    assert sweeps[-1]["type"] == "SWEEP_LOW" and sweeps[-1]["level"] == 99 and sweeps[-1]["wick"] == 98


def test_opening_range_breakout():
    first = [(100, 102, 99, 101), (101, 103, 100, 102), (102, 102.5, 100.5, 101)]  # 09:15-09:30: ORB 99-103
    waiting = trader.opening_range(day(first + [(101, 102.8, 100.8, 102.5)]))
    assert waiting["status"] == "WAITING" and (waiting["orb_low"], waiting["orb_high"]) == (99, 103)
    buy = trader.opening_range(day(first + [(102.5, 104, 102, 103.6), (103.6, 104.2, 103, 104)]))
    assert buy["status"] == "TRIGGERED" and buy["direction"] == "BUY" and buy["entry"] == 103.6 and buy["trigger_time"] == "09:30"
    early = trader.opening_range(day(first[:2]))
    assert early["status"] == "WAITING_DATA"


def test_exit_plan_stop_at_the_range_middle_and_r_targets():
    plan = trader.plan_exit(2950, 2960, 2920, "BUY", (1.5, 2))
    assert plan["stop_loss"] == 2940 and plan["risk_per_share"] == 10 and plan["targets"] == {"1:1.5": 2965, "1:2": 2970}
    short = trader.plan_exit(2910, 2960, 2920, "SELL", (2,))
    assert short["stop_loss"] == 2940 and short["targets"] == {"1:2": 2850}
    with pytest.raises(trader.DataError):
        trader.plan_exit(2930, 2960, 2920, "BUY")  # the middle (2940) is above a buy at 2930


def test_position_size_by_risk_and_by_margin():
    by_risk = trader.position_size(50000, 2950, 2935, risk_rs=500)
    assert by_risk["qty"] == 33 and by_risk["limited_by"] == "risk" and by_risk["risk_if_stopped"] == 495
    assert by_risk["margin_needed"] == round(33 * 2950 / 5, 2)
    by_margin = trader.position_size(10000, 1000, 999, risk_pct=1, product="CNC")
    assert by_margin["qty"] == 10 and by_margin["limited_by"] == "margin" and by_margin["leverage"] == 1


def test_risk_guard_blocks_with_every_reason():
    assert trader.validate_trade(500, 400, 1, "11:00") == ("PASS", [])
    verdict, why = trader.validate_trade(500, 1200, 3, "14:50")
    assert verdict == "BLOCK" and len(why) == 3
    assert trader.validate_trade(100, 0, 0, "16:00")[0] == "BLOCK"


def test_alerts_use_the_given_ist_time():
    assert trader.check_alerts(2995, 2935, 2990, "BUY", "11:00")[0].startswith("TARGET HIT")
    assert trader.check_alerts(2930, 2935, 2990, "BUY", "11:00")[0].startswith("STOP LOSS HIT")
    assert trader.check_alerts(2960, 2935, 2990, "BUY", "15:16") == [
        "SQUARE-OFF: it is 15:16 IST - close intraday (MIS) positions before the broker does"]
    assert trader.check_alerts(2960, 2935, 2990, "BUY", "15:40") == []


def test_tickets_put_the_stop_in_its_own_order():
    t = trader.broker_tickets("RELIANCE.NS", "BUY", 33, 2950, 2935, 2980)
    entry, stop, target = t["zerodha_kite"]
    assert entry["order_type"] == "LIMIT" and "trigger_price" not in entry and entry["tradingsymbol"] == "RELIANCE"
    assert stop["order_type"] == "SL-M" and stop["transaction_type"] == "SELL" and stop["trigger_price"] == 2935
    assert target["price"] == 2980 and t["angel_one"][0]["tradingsymbol"] == "RELIANCE-EQ" and t["paper_only"]


def test_time_machine_counts_paths_and_wants_enough_moves():
    up_only = [0.002] * 50
    odds = trader.monte_carlo(up_only, 100, 99, 101, paths=200, steps=10)
    assert odds["target_first_pct"] == 100.0
    with pytest.raises(trader.DataError):
        trader.monte_carlo([0.001] * 5, 100, 99, 101)


def test_moves_leave_out_the_overnight_and_weekend_gaps():
    friday = day([(100, 100, 100, 100), (100, 101, 100, 101)], datetime(2026, 10, 30, 15, 20, tzinfo=IST))
    monday = day([(110, 110, 110, 110), (110, 111, 110, 111)], datetime(2026, 11, 2, 9, 15, tzinfo=IST))
    moves = trader.returns_of(friday + monday)
    assert len(moves) == 2 and all(m < 0.02 for m in moves)


def test_csv_candles(tmp_path):
    f = tmp_path / "bars.csv"
    f.write_text("time,open,high,low,close,volume\n2026-11-03 09:20,101,103,100,102,5\n2026-11-03 09:15,100,102,99,101,4\n")
    bars = trader.bars_from_csv(str(f))
    assert [b["close"] for b in bars] == [101, 102] and bars[0]["time"].tzinfo is not None
