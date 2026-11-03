"""tools/invest.py: the /short-* and /long-* skills' calculations, on made-up data (no network)."""
import os
import sys
from datetime import date, datetime, timedelta

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import invest  # noqa: E402
import trader  # noqa: E402


def daily(closes, volumes=None, start=datetime(2025, 1, 1, tzinfo=trader.IST)):
    volumes = volumes or [1000] * len(closes)
    out, prev = [], closes[0]
    for i, (c, v) in enumerate(zip(closes, volumes)):
        out.append({"time": start + timedelta(days=i), "open": prev, "high": max(prev, c) * 1.005, "low": min(prev, c) * 0.995,
                    "close": c, "volume": v})
        prev = c
    return out


def test_ema_and_atr():
    assert invest.ema_series([1, 2, 3], 3) == [2.0]
    e = invest.ema_series([10] * 20 + [20], 20)
    assert e[0] == 10 and round(e[1], 4) == round(10 + (20 - 10) * 2 / 21, 4)
    bars = daily([100] * 30)
    assert invest.atr(bars) == pytest.approx(1.0, rel=0.02)


def test_scanner_finds_a_stage_two_breakout_on_volume():
    closes = [100 + i * 0.4 - (1.0 if i % 4 == 3 else 0) for i in range(300)] + [100 + 300 * 0.4 + 6]
    vols = [1500 if i % 4 == 3 else 1000 for i in range(300)] + [4000]  # down days on more volume, then a big up day
    s = invest.swing_signals(daily(closes, vols))
    assert {"52W_BREAKOUT", "STAGE_2_UPTREND", "HIGH_VOLUME_20D_BREAKOUT", "POCKET_PIVOT"} <= set(s["signals"])
    with pytest.raises(trader.DataError):
        invest.swing_signals(daily([100] * 30))


def test_volume_dry_up():
    s = invest.swing_signals(daily([100 + (i % 3) for i in range(300)], [1000] * 295 + [300] * 5))
    assert "VOLUME_DRY_UP" in s["signals"]


def test_trend_and_setup_alignment():
    up = [100 + i for i in range(120)]
    assert invest.trend(up, 20, 50) == "UP" and invest.trend(list(reversed(up)), 20, 50) == "DOWN"
    setup = invest.swing_setup(daily(up), daily(up[::5]))
    assert setup["timeframes"] in ("ALIGNED_UP", "MIXED") and setup["atr14"] > 0


def test_swing_position_caps():
    p = invest.swing_position(100000, 500, 480, risk_pct=1.5)
    assert p["qty"] == 50 and p["risk_if_stopped"] == 1000 and p["target"] == 550  # 25% cap: 25,000 / 500 = 50
    assert p["warnings"] == ["capped at 25% of capital in one stock"]
    q = invest.swing_position(100000, 500, 400, risk_pct=5)  # asked 5%, allowed 2%
    assert q["risk_budget"] == 2000 and q["qty"] == 20
    assert invest.swing_position(100000, 500, 400, open_positions=5)["warnings"][0].startswith("5 positions")
    with pytest.raises(trader.DataError):
        invest.swing_position(100000, 500, 510)


def test_trailing_stop_never_drops():
    bars = daily([100, 110, 120, 115])
    assert invest.chandelier(bars, 5, 95) == 105  # 120 - 3 x 5
    assert invest.chandelier(bars, 20, 95) == 95


def test_swing_tickets_put_stop_and_target_in_a_gtt():
    t = invest.swing_tickets("TITAN.NS", "BUY", 10, 3500, 3400, 3750)
    gtt = t["zerodha_kite"]["gtt_oco"]
    assert gtt["condition"]["trigger_values"] == [3400, 3750] and gtt["orders"][0]["price"] == 3383.0
    assert t["zerodha_kite"]["entry"]["product"] == "CNC" and t["shoonya"]["entry"]["prd"] == "C" and t["paper_only"]


def test_dates_and_catalysts():
    assert invest.dates_in("Board Meeting on 12/11/2026 to consider results") == [date(2026, 11, 12)]
    assert invest.dates_in("Record date fixed as 5th December 2026") == [date(2026, 12, 5)]
    assert invest.dates_in("AGM on November 20, 2026") == [date(2026, 11, 20)]
    filings = [{"filed": "2026-11-01 10:00", "title": "Board Meeting Intimation for financial results on 12-11-2026"},
               {"filed": "2026-10-01 10:00", "title": "Outcome of Board Meeting held on 30/09/2026 - Dividend"},
               {"filed": "2026-10-02 10:00", "title": "Change in registered office"}]
    c = invest.catalysts_of(filings, date(2026, 11, 3))
    assert c["upcoming"][0]["event_date"] == "2026-11-12" and "results" in c["upcoming"][0]["kinds"]
    assert len(c["recent"]) == 1 and "dividend" in c["recent"][0]["kinds"]


def test_quality_checks_and_roe():
    row = {"f": {"roce": 22.0, "de": 0.2, "pm": 60.0, "pl": 0.0, "bv": 100.0}}
    hist = {"q": [["2509", 0, 0, 5.0], ["2512", 0, 0, 5.0], ["2603", 0, 0, 5.0], ["2606", 0, 0, 5.0]],
            "y": [["2203", 100, 10, 0, 0, 50], ["2303", 110, 12, 0, 0, 50], ["2403", 120, 14, 0, 0, 50],
                  ["2503", 130, 16, 0, 0, 50], ["2603", 140, 18, 0, 0, 50]]}
    q = invest.quality_checks(row, hist)
    assert q["roe"]["value"] == 20.0 and q["roe"]["pass"]
    assert q["profit_cagr_pct"]["value"] == pytest.approx(15.8, abs=0.1) and q["profit_cagr_pct"]["pass"]
    assert all(c["pass"] for c in q.values())
    # without a book value, the equity its debt and debt-to-equity imply: 50 / 0.2 = 250, profit 18 -> 7.2%
    assert invest.roe_of({"f": {"de": 0.2}}, hist) == 7.2
    assert invest.roe_of({"f": {}}, hist) is None


def test_screen_keeps_only_passes_with_enough_known():
    data = {"updated": "x", "stocks": {"GOOD": {"s": 80, "f": {"roce": 20, "de": 0.1, "pm": 55, "pl": 0}},
                                         "DEBT": {"s": 90, "f": {"roce": 20, "de": 2.0, "pm": 55, "pl": 0}},
                                         "THIN": {"s": 95, "f": {"roce": 20}}}}
    r = invest.screen(data, {}, top=10)
    assert [x["symbol"] for x in r["top"]] == ["GOOD"]


def test_dcf_and_price():
    v = invest.dcf_per_share(100, 10, 10)
    assert v and v > 100  # ten years of growing cash and a terminal value, per crore shares
    assert invest.dcf_per_share(-5, 10, 10) is None
    assert invest.cmp_of({"f": {"fv": 120, "up": 20}}) == 100


def test_allocation_inverse_volatility():
    a = invest.allocation(100000, {"core": ["A", "B"], "growth": ["C"]}, [60, 30, 10], {"A": 10, "B": 30, "C": None}, {"A": 100, "B": 100, "C": 50})
    core = a["buckets"]["core_compounders"]
    assert core["weighting"] == "inverse volatility" and core["stocks"][0]["amount"] == 45000 and core["stocks"][1]["amount"] == 15000
    assert a["buckets"]["growth"]["weighting"] == "equal" and a["buckets"]["cash_reserve"]["amount"] == 10000


def test_rebalance_trades_and_tax():
    holdings = [{"symbol": "A", "qty": 100, "avg_price": 100, "buy_date": date(2024, 1, 1), "target_pct": None},
                {"symbol": "B", "qty": 10, "avg_price": 200, "buy_date": date(2026, 6, 1), "target_pct": None}]
    r = invest.rebalance(holdings, {"A": 150, "B": 150}, band=5, today=date(2026, 11, 3))
    rows = {x["symbol"]: x for x in r["positions"]}
    assert rows["A"]["weight_pct"] == pytest.approx(90.9, abs=0.1) and rows["A"]["trade_qty"] < 0 and rows["B"]["trade_qty"] > 0
    lots = {x["symbol"]: x for x in r["lots"]}
    assert lots["A"]["term"] == "long" and lots["B"]["term"] == "short" and lots["B"]["harvest_candidate"]
    assert r["if_all_sold"]["long_term_gain"] == 5000 and r["if_all_sold"]["estimated_tax"] == 0  # under the ₹1.25 lakh exemption
