"""The sector-wise valuation framework (cr360/valuation.py) and how 360CR, the app's rows and the daily build use it."""
from datetime import date, timedelta
from pathlib import Path

from cr360 import app_store, screener_page
from cr360 import valuation as v
from cr360.build_app_cache import auditor_resignations, company_key, pledge_rows
from cr360.engine import analyse_360cr, score_valuation
from cr360.models import ResearchInput

FIXTURES = Path(__file__).parent / "fixtures" / "cr360"
TODAY = date(2026, 10, 21)


def page_stock(name):
    store = app_store.empty()
    app_store.put_page(store, name, screener_page.parse((FIXTURES / f"screener_{name}.html").read_text()), TODAY, "standalone")
    return store["stocks"][name]


def flat(price, days=1095, end=TODAY):
    """`days` daily rows at one price up to `end`."""
    return [{"date": (end - timedelta(days=days - 1 - i)).isoformat(), "close": price, "high": price, "low": price}
            for i in range(days)]


QUARTER_ENDS = ["2023-06-30", "2023-09-30", "2023-12-31", "2024-03-31", "2024-06-30", "2024-09-30", "2024-12-31",
                "2025-03-31", "2025-06-30", "2025-09-30", "2025-12-31", "2026-03-31", "2026-06-30"]


def maker(**over):
    """A maker of 10 crore shares (₹10 crore capital at ₹1 face value) earning ₹25 crore EBITDA a quarter, ₹200 crore
    debt on ₹500 crore equity, ROCE 18%, interest ₹2 crore on ₹20 crore profit before tax a quarter."""
    stock = {
        "quarters": [{"period": p, "ebitda": 25.0, "interest": 2.0, "pbt": 20.0, "net_profit": 15.0, "eps": 1.5}
                     for p in QUARTER_ENDS],
        "balance": [{"period": p, "equity": 500.0, "total_debt": 200.0, "capital": 10.0}
                    for p in ("2023-03-31", "2024-03-31", "2025-03-31", "2026-03-31")],
        "annual": [{"period": p, "ebitda": e, "eps": 6.0} for p, e in (("2024-03-31", 60.0), ("2025-03-31", 100.0), ("2026-03-31", 140.0))],
        "cashflow": [{"period": p, "operating_cash_flow": 70.0, "net_profit": 60.0} for p in ("2024-03-31", "2025-03-31", "2026-03-31")],
        "shareholding": [{"period": p, "fii_fpi": 10.0, "dii": 10.0} for p in QUARTER_ENDS[-4:]],
        "ratios": {"face_value": 1.0, "roce": 18.0},
    }
    stock.update(over)
    return stock


def test_an_asset_heavy_maker_is_worth_its_own_ev_to_ebitda_less_debt():
    # At ₹50 the market values it at 10 × 50 + 200 = ₹700 crore, 7 times its ₹100 crore EBITDA, every month.
    out = v.ev_value(maker(), flat(50.0), 50.0)
    assert out["ev_ebitda_own"] == 7.0 and out["ev_ebitda_now"] == 7.0 and out["ebitda"] == 100.0
    assert out["iv"] == (7.0 * 100.0 - 200.0) / 10.0 == 50.0 and not out["roce_cut"]
    # ROCE under 12% takes 20% off.
    weak = v.ev_value(maker(ratios={"face_value": 1.0, "roce": 9.0}), flat(50.0), 50.0)
    assert weak["roce_cut"] and weak["iv"] == 40.0
    # A cyclical is valued on its years' average EBITDA, ₹100 crore here too, whatever this year's.
    boom = maker(quarters=[q | {"ebitda": 25.0 if q["period"] < "2025-07-01" else 50.0} for q in maker()["quarters"]])
    assert v.ev_value(boom, flat(50.0), 50.0, mid_cycle=True)["ebitda"] == 100.0
    assert v.ev_value(boom, flat(50.0), 50.0)["ebitda"] == 200.0
    # Under twelve month-ends with known figures there is no own multiple to go by.
    assert v.ev_value(maker(), flat(50.0, days=200), 50.0) is None


def test_a_lender_is_worth_its_book_less_bad_loans_at_its_own_price_to_book():
    stock = {
        "ratios": {"book_value": 100.0, "face_value": 1.0},
        "balance": [{"period": p, "equity": 1000.0, "capital": 10.0, "total_assets": 10000.0, "other_assets": 6000.0}
                    for p in ("2023-03-31", "2024-03-31", "2025-03-31", "2026-03-31")],
        "quarters": [{"period": p, "net_profit": 40.0, "net_npa": 0.5} for p in QUARTER_ENDS],
    }
    out = v.bank_value(stock, flat(200.0))
    # Net NPA 0.5% of ₹6,000 crore loans over 10 crore shares: ₹3 a share off the ₹100 book; at ₹200 it trades at 2× book.
    assert out["adjusted_bvps"] == 97.0 and out["pb"] == 2.0 and out["iv"] == 194.0 and out["own_history"]
    assert out["roa"] == 160.0 / 10000.0 * 100.0
    # A short history goes by the sector's P/B; consolidated pages leave NPA blank and the book counts whole.
    short = v.bank_value(stock | {"quarters": [q | {"net_npa": None} for q in stock["quarters"]]}, flat(200.0, days=100), 1.5)
    assert short["iv"] == 150.0 and not short["own_history"] and short["adjusted_bvps"] == 100.0


def test_a_bonus_issue_does_not_halve_a_lenders_past_price_to_book():
    # Yahoo halves the old prices after a 1:1 bonus; the book per share then is read on today's share count.
    stock = page_stock("hdfcbank")
    capitals = [b["capital"] for b in stock["balance"]]
    assert capitals[-1] > capitals[0]  # the bonus doubled HDFC Bank's capital at the same face value
    out = v.bank_value(stock, flat(stock["ratios"]["price"]))
    assert 2.0 < out["pb"] < 3.0 and out["roa"] > 1.0


def test_peg_values_growers_at_their_own_pe_up_to_a_peg_of_two():
    stock = {"annual": [{"period": f"{y}-03-31", "eps": e} for y, e in ((2022, 10.0), (2023, 12.0), (2024, 14.4), (2025, 17.28))]}
    out = v.peg_value(stock, 345.6, 17.28, own_pe=30.0)
    assert round(out["growth"], 6) == 20.0 and round(out["pe"], 6) == 20.0 and round(out["peg"], 6) == 1.0
    # Its own usual P/E of 30 is under the cap of 2 × 20 = 40.
    assert out["fair_pe"] == 30.0 and round(out["iv"], 6) == round(17.28 * 30, 6) and not out["base_sector"]
    # A usual P/E of 60 is capped at a PEG of 2; the sector's stands in without a history; with neither, PEG 1.5.
    assert round(v.peg_value(stock, 345.6, 17.28, own_pe=60.0)["fair_pe"], 6) == 40.0
    sector = v.peg_value(stock, 345.6, 17.28, sector_pe=25.0)
    assert sector["fair_pe"] == 25.0 and sector["base_sector"]
    assert round(v.peg_value(stock, 345.6, 17.28)["fair_pe"], 6) == 30.0
    # Growth over 40% a year counts as 40; under 5% PEG says nothing and gives no value.
    fast = {"annual": [{"period": f"{y}-03-31", "eps": e} for y, e in ((2022, 1.0), (2023, 2.0), (2024, 4.0), (2025, 8.0))]}
    assert round(v.peg_value(fast, 400.0, 8.0, own_pe=200.0)["pe_cap"], 6) == 80.0
    slow = {"annual": [{"period": f"{y}-03-31", "eps": 10.0} for y in range(2022, 2026)]}
    assert "iv" not in v.peg_value(slow, 200.0, 10.0)
    assert v.peg_value(stock, 345.6, -1.0) is None and v.peg_value({"annual": stock["annual"][1:]}, 345.6, 17.28) is None


def test_the_universal_screen_rejects_and_flags():
    flags, out = v.screen(maker(pledge=16.0), [], None, financial=False)
    assert flags & v.REJECT and out["pledge"] == 16.0
    # Interest cover: (80 + 8) / 8 = 11; cash 210 over profit 180.
    assert out["icr"] == 11.0 and round(out["cfo_pat"], 4) == round(210 / 180, 4) and not flags & (v.HIGH_RISK | v.MANIPULATION)
    risky = maker(quarters=[q | {"interest": 10.0, "pbt": 2.0} for q in maker()["quarters"]],
                  cashflow=[c | {"operating_cash_flow": 30.0} for c in maker()["cashflow"]])
    flags, out = v.screen(risky, [], None, financial=False)
    assert out["icr"] == 1.2 and out["cfo_pat"] == .5 and flags & v.HIGH_RISK and flags & v.MANIPULATION
    # A lender's interest and cash flow are its business.
    assert v.screen(risky, [], None, financial=True) == (0, {})
    assert v.screen(maker(pledge=15.0), [], None, financial=False)[0] & v.REJECT == 0


def test_the_buy_zone_is_over_the_200_day_ema_at_an_unfilled_bullish_gap():
    rows = flat(100.0, days=250)
    # A gap up: the session after a 101 high opens a range from 104, and the price holds in it.
    rows += [{"date": "2026-10-22", "close": 103.0, "high": 103.5, "low": 100.0},
             {"date": "2026-10-23", "close": 105.0, "high": 106.0, "low": 102.0},
             {"date": "2026-10-26", "close": 107.0, "high": 108.0, "low": 104.0},
             {"date": "2026-10-27", "close": 105.0, "high": 106.0, "low": 104.5}]
    rows[-5]["high"] = 101.0
    # The latest gap, 103.5 to 104 (the 104.5 low never filled it), with the price in it or up to 5% over it.
    assert v.bullish_gap(rows, 104.0) == v.bullish_gap(rows, 109.0) == (103.5, 104.0)
    assert v.bullish_gap(rows, 110.0) is None
    flags, out = v.screen({}, rows, 104.0, financial=False)
    assert flags & v.BUY_ZONE and out["fvg"] == (103.5, 104.0) and out["ema200"] < 104.0
    # Under the 200-day EMA the gap is not a buy zone.
    assert not v.screen({}, [r | {"close": 200.0} for r in rows[:-4]] + rows[-4:], 104.0, financial=False)[0] & v.BUY_ZONE
    assert v.ema([1.0] * 199) is None and v.ema([2.0] * 300) == 2.0


def test_a_weekly_sweep_dips_under_a_weekly_swing_low_and_closes_back_above():
    start = date(2025, 10, 6)  # a Monday
    lows = [100.0] * 20 + [90.0] + [100.0] * 20  # one weekly swing low at 90
    rows = []
    for w, low in enumerate(lows):
        for d in range(5):
            day = start + timedelta(weeks=w, days=d)
            rows.append({"date": day.isoformat(), "close": 105.0, "high": 106.0, "low": low if d == 2 else 103.0})
    assert v.weekly_sweep(rows) is None
    # This week dips to 88 and closes at 95, back above 90.
    week = start + timedelta(weeks=len(lows))
    swept = rows + [{"date": (week + timedelta(days=d)).isoformat(), "close": 95.0, "high": 96.0, "low": 88.0 if d == 1 else 93.0}
                    for d in range(5)]
    assert v.weekly_sweep(swept) == 90.0
    # Closing under it is a break, not a sweep.
    broken = rows + [{"date": (week + timedelta(days=d)).isoformat(), "close": 85.0, "high": 96.0, "low": 84.0} for d in range(5)]
    assert v.weekly_sweep(broken) is None


def test_penalties_cut_the_intrinsic_value():
    assert v.penalties(maker(), False, TODAY) == (1.0, 0)
    falling = [{"period": p, "fii_fpi": f, "dii": 10.0} for p, f in zip(QUARTER_ENDS[-4:], (12.0, 11.0, 10.5, 10.0))]
    stock = maker(auditor_resigned=["2026-02-01"], balance=[{"period": "2026-03-31", "equity": 100.0, "total_debt": 200.0}],
                  shareholding=falling)
    factor, bits = v.penalties(stock, False, TODAY)
    assert round(factor, 6) == round(.6 * .8 * .9, 6) and bits == v.P_AUDITOR | v.P_DEBT | v.P_INSTITUTIONS
    # A lender's debt is its business; an auditor who left over a year ago no longer counts.
    assert v.penalties(stock | {"auditor_resigned": ["2025-10-01"]}, True, TODAY) == (.9, v.P_INSTITUTIONS)


def test_assess_applies_penalties_then_the_margin_of_safety():
    stock = maker(auditor_resigned=["2026-05-01"])
    out = v.assess(stock, flat(50.0), 50.0, "Capital Goods", 6.0, None, v.MOS["large"], TODAY)
    assert out["model"] == out["sector_model"] == v.ASSET
    assert out["raw_iv"] == 50.0 and out["iv"] == 30.0 and out["buy"] == 24.0 and out["auditor"] == "2026-05-01"
    assert out["penalties"] == v.P_AUDITOR and out["factor"] == .6
    # A sector model with too little to go on falls back to the blend, penalties and margin still applied.
    short = v.assess(stock, flat(50.0, days=100), 50.0, "Capital Goods", 6.0, 80.0, v.MOS["small"], TODAY)
    assert short["model"] == v.GENERAL and short["sector_model"] == v.ASSET and short["iv"] == 48.0
    assert round(short["buy"], 6) == round(48.0 * .65, 6)
    # Metals and oil carry the cyclical warning; an unknown sector goes by the blend.
    assert v.assess(maker(), flat(50.0), 50.0, "Metals & Mining", 6.0, None, .35, TODAY)["flags"] & v.CYCLICAL_WARNING
    assert v.assess(maker(), flat(50.0), 50.0, "", 6.0, 70.0, .35, TODAY)["model"] == v.GENERAL


def test_best_is_under_the_buy_price_while_sweeping_and_nothing_blocks():
    start = date(2025, 10, 6)
    rows = []
    for w in range(41):
        for d in range(5):
            day = start + timedelta(weeks=w, days=d)
            rows.append({"date": day.isoformat(), "close": 30.0, "high": 31.0, "low": 25.0 if (w == 20 and d == 2) else 29.0})
    week = start + timedelta(weeks=41)
    rows += [{"date": (week + timedelta(days=d)).isoformat(), "close": 26.0, "high": 27.0, "low": 24.0} for d in range(5)]
    stock = maker()
    out = v.assess(stock, rows, 26.0, "", 3.0, 100.0, .35, week + timedelta(days=4))
    assert out["sweep"] == 25.0 and out["buy"] == 65.0 and out["flags"] & v.BEST
    pledged = v.assess(stock | {"pledge": 40.0}, rows, 26.0, "", 3.0, 100.0, .35, week + timedelta(days=4))
    assert not pledged["flags"] & v.BEST and pledged["flags"] & v.REJECT


def test_margin_of_safety_by_market_value_rank():
    assert [v.size_mos(r) for r in (1, 100, 101, 250, 251, None)] == [.20, .20, .275, .275, .35, .35]
    store = {"stocks": {"A": maker(), "B": maker(balance=[{"period": "2026-03-31", "capital": 20.0}]),
                        "C": {"ratios": {"market_cap_cr": 50.0}}, "D": {}}}
    # A: 10 crore shares at ₹50; B: 20 crore at ₹40; C by Screener's market cap; D unknown.
    assert app_store.size_ranks(store, {"A": 50.0, "B": 40.0}) == {"B": 1, "A": 2, "C": 3}


def test_360cr_uses_the_frameworks_value_and_its_flags_hold_the_verdict_down():
    x = ResearchInput(symbol="X", price=100.0, valuation={"earnings_fair_value": 300.0, "framework_fair_value": 120.0})
    score, fair, _ = score_valuation(x)
    assert fair["base"] == 120.0 and fair["upside_pct"] == 20.0 and score == 80.0
    stock = page_stock("reliance")
    prices = [{"date": r["date"], "close": 1200.0 + i * .2, "high": 1201.0 + i * .2, "low": 1199.0 + i * .2}
              for i, r in enumerate(flat(0, days=1095))]
    research = app_store.research_input("R", stock, prices, (25.0, 3.0), sector="Oil Gas & Consumable Fuels", today=TODAY)
    clean = analyse_360cr(research)
    research.metadata["framework"] = research.metadata["framework"] | {"flags": v.REJECT}
    rejected = analyse_360cr(research)
    assert rejected.state == "AVOID" and rejected.sections["valuation"] <= 40 and any("REJECT" in w for w in rejected.warnings)
    research.metadata["framework"] = research.metadata["framework"] | {"flags": v.HIGH_RISK}
    risky = analyse_360cr(research)
    assert risky.state in ("CAUTION", "AVOID") and risky.score <= clean.score


def test_the_app_row_carries_the_frameworks_verdict():
    stock = page_stock("reliance")
    research = app_store.research_input("R", stock, flat(1206.0), (25.0, 3.0), sector="Oil Gas & Consumable Fuels",
                                        mos=v.MOS["large"], today=TODAY)
    f = app_store.app_record(stock, research)["f"]
    assert f["mdl"] == f["smd"] == v.MODEL_CODE[v.CYCLICAL] and f["mos"] == 20.0
    assert f["flg"] & v.CYCLICAL_WARNING and f["pf"] & v.P_ROCE and f["roce"] < v.ROCE_MIN
    assert f["fv"] == round(f["ivr"], 2) and f["bp"] == round(f["ivr"] * .8, 2)
    assert f["evo"] > 0 and f["eb"] > 0 and f["dt"] > 0 and f["sh"] > 1000 and f["icr"] > v.ICR_RISK
    # The value the model gives, less debt, per share, less 20% for the low ROCE (the facts' rounding aside).
    assert abs((f["evo"] * f["eb"] - f["dt"]) / f["sh"] * .8 - f["ivr"]) < 2
    bank = page_stock("hdfcbank")
    fb = app_store.app_record(bank, app_store.research_input("H", bank, flat(704.0), (20.0, 2.5), financial=True,
                                                            sector="Financial Services", today=TODAY))["f"]
    assert fb["mdl"] == v.MODEL_CODE[v.BANK] and fb["abv"] == fb["bv"] and fb["roa"] > 1 and "pbs" not in fb


def test_pages_read_before_the_framework_are_read_again():
    store = app_store.empty()
    app_store.put_page(store, "R", screener_page.parse((FIXTURES / "screener_reliance.html").read_text()), TODAY, "consolidated")
    stock = store["stocks"]["R"]
    assert stock["pv"] == app_store.PAGE_VERSION and not app_store.is_due(stock, TODAY + timedelta(days=1))
    del stock["pv"]
    assert app_store.is_due(stock, TODAY + timedelta(days=1))
    # A stock Screener does not have (no page, so no source) waits as before.
    assert not app_store.is_due({"checked": TODAY.isoformat()}, TODAY + timedelta(days=1))


def test_nse_pledge_rows_map_to_symbols_by_company_name():
    names = {company_key("20 Microns Limited"): "20MICRONS", company_key("Larsen & Toubro Ltd."): "LT"}
    rows = [
        {"comName": "20 Microns Limited", "percPromoterShares": "     0.00", "percSharesPledged": "4.56", "shp": "30-Sep-2026"},
        {"comName": "LARSEN AND TOUBRO LIMITED", "percPromoterShares": " 18.50", "shp": "30-Jun-2026"},
        {"comName": "Larsen & Toubro Limited", "percPromoterShares": " 12.00", "shp": "31-Mar-2026"},
        {"comName": "Unknown Limited", "percPromoterShares": "50.0", "shp": "30-Sep-2026"},
    ]
    # Brokers' margin pledges (percSharesPledged) are not the promoters'; the latest shareholding wins.
    assert pledge_rows(rows, names) == {"20MICRONS": 0.0, "LT": 18.5}


def test_statutory_auditor_resignations_are_found_in_nse_announcements():
    rows = [
        {"symbol": "AAA", "desc": "Resignation of Statutory Auditor", "sort_date": "2026-10-20 18:01:00", "attchmntText": ""},
        {"symbol": "BBB", "desc": "Change in Auditors", "an_dt": "21-Oct-2026 10:00:00",
         "attchmntText": "BBB Limited has informed the Exchange regarding resignation of M/s X & Co as Statutory Auditors."},
        {"symbol": "CCC", "desc": "Change in Auditors", "sort_date": "2026-10-21",
         "attchmntText": "CCC Limited has informed the Exchange regarding resignation of M/s Y as Internal Auditor."},
        {"symbol": "DDD", "desc": "Change in Auditors", "sort_date": "2026-10-21",
         "attchmntText": "DDD Limited has informed the Exchange regarding appointment of M/s Z as Statutory Auditors."},
        {"symbol": "EEE", "desc": "Board Meeting Outcome", "sort_date": "2026-10-21", "attchmntText": "resignation of director"},
    ]
    assert auditor_resignations(rows) == {"AAA": {"2026-10-20"}, "BBB": {"2026-10-21"}}


def test_parts_without_data_count_as_the_middle_not_as_left_out():
    from cr360.engine import UNKNOWN_PART, WEIGHTS
    # Only a cheap price and a steady chart: three or four strong parts, the rest unknown.
    rows = [{"date": (TODAY - timedelta(days=400 - i)).isoformat(), "close": 100.0 + i * .1, "high": 100.5 + i * .1,
             "low": 99.5 + i * .1} for i in range(400)]
    r = analyse_360cr(ResearchInput(symbol="FEW", price=rows[-1]["close"], price_history=rows,
                                    valuation={"framework_fair_value": 300.0}))
    found = {k: v for k, v in r.sections.items() if v is not None}
    assert 0 < len(found) < len(WEIGHTS)
    found_only = sum(v * WEIGHTS[k] for k, v in found.items()) / sum(WEIGHTS[k] for k in found)
    expected = (sum(v * WEIGHTS[k] for k, v in found.items()) + UNKNOWN_PART * sum(w for k, w in WEIGHTS.items() if k not in found)) / 100
    assert round(r.score, 2) == round(expected, 2)
    # Strong where it was judged, it no longer scores as if it were strong everywhere.
    assert found_only > 75 and r.score < found_only - 10 and r.state != "HIGH_CONVICTION"
