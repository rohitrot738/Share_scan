"""The app's 360CR cache: Screener pages, the store's merging and pruning, when pages are read again, the app rows."""
import json
from datetime import date, timedelta
from pathlib import Path

from cr360 import app_store, screener_page
from cr360.build_app_cache import (Screener, bse_listings, combine, deal_rows, iso_date, nse_listings, parse_insider,
                                   screener_slug, yahoo_tickers)
from cr360.nse_public_adapter import NSEPublicAdapter

FIXTURES = Path(__file__).parent / "fixtures" / "cr360"


def page(name):
    return screener_page.parse((FIXTURES / f"screener_{name}.html").read_text())


def test_a_company_page_gives_quarters_years_shareholding_and_ratios():
    p = page("reliance")
    assert p["name"] == "Reliance Industries Ltd"
    assert len(p["quarters"]) == 13 and p["quarters"][-1]["period"] == "2026-06-30"
    assert p["quarters"][-1]["net_profit"] == 23196.0 and p["quarters"][-1]["eps"] == 15.48
    assert len(p["shareholding"]) == 12 and p["shareholding"][-1]["promoter"] == 50.48
    assert p["balance"][-1]["equity"] > p["balance"][-1]["total_debt"] > 0
    # Yearly cash flow carries its year's profit, so 360CR compares like with like.
    assert p["cashflow"][-1]["net_profit"] == p["annual"][-1]["net_profit"]
    assert p["ratios"]["pe"] == 21.8 and p["ratios"]["book_value"] == 668.0


def test_banks_and_small_companies_parse_too():
    bank = page("hdfcbank")
    assert bank["quarters"][-1]["revenue"] == 95132.0 and bank["quarters"][-1]["net_profit"] == 21537.0
    assert bank["shareholding"][-1]["promoter"] is None  # a bank without promoters
    assert len(page("tsc_sme")["quarters"]) == 4
    assert len(page("bse_539410")["quarters"]) == 13


def test_an_empty_consolidated_page_has_no_quarters():
    assert page("irfc_consolidated")["quarters"] == []
    assert len(page("irfc")["quarters"]) == 13


def test_periods_and_numbers():
    assert screener_page.period_end("Sep 2023") == date(2023, 9, 30)
    assert screener_page.period_end("Feb 2024") == date(2024, 2, 29)
    assert screener_page.period_end("TTM") is None
    assert screener_page.number("2,31,886") == 231886.0
    assert screener_page.number("-12.5%") == -12.5
    assert screener_page.number("") is None


def test_new_readings_add_quarters_and_old_ones_stay_up_to_the_limit():
    old = [{"period": f"20{y}-03-31", "v": y} for y in range(10, 30)]
    new = [{"period": "2029-03-31", "v": 99}, {"period": "2030-03-31", "v": 30}]
    merged = app_store.merge_periods(old, new, keep=20)
    assert len(merged) == 20
    assert merged[0]["period"] == "2011-03-31" and merged[-1]["period"] == "2030-03-31"
    assert merged[-2]["v"] == 99  # a restated quarter takes the new figure


def test_pruning_drops_aged_rows_and_delisted_stocks():
    today = date(2026, 10, 21)
    store = app_store.empty()
    store["stocks"] = {
        "KEEP": {"quarters": [{"period": f"{2000 + i // 4}-{(i % 4) * 3 + 3:02d}-28"} for i in range(30)],
                 "insider": [["2020-01-01", "buy", 1.0], ["2026-01-01", "sell", 2.0]],
                 "deals": [["2021-10-20", "buy", 3.0], ["2021-10-22", "buy", 4.0]]},
        "GONE": {"quarters": []},
    }
    report = app_store.prune(store, {"KEEP": {}}, today)
    kept = store["stocks"]["KEEP"]
    assert "GONE" not in store["stocks"] and report["stocks_removed"] == 1
    assert len(kept["quarters"]) == app_store.QUARTERS_KEPT
    assert kept["insider"] == [["2026-01-01", "sell", 2.0]]
    assert kept["deals"] == [["2021-10-22", "buy", 4.0]]
    assert report["rows_removed"] == 10 + 1 + 1


def test_a_page_is_read_again_only_when_new_results_can_be_out():
    today = date(2026, 10, 21)
    assert app_store.latest_due_quarter(today) == date(2026, 9, 30)
    assert app_store.latest_due_quarter(date(2026, 10, 10)) == date(2026, 6, 30)
    assert app_store.is_due(None, today)
    fresh = {"checked": "2026-10-20", "quarters": [{"period": "2026-06-30"}]}
    assert not app_store.is_due(fresh, today)  # read yesterday
    waiting = {"checked": "2026-10-10", "quarters": [{"period": "2026-06-30"}]}
    assert app_store.is_due(waiting, today)  # September's results may be out
    current = {"checked": "2026-10-10", "quarters": [{"period": "2026-09-30"}]}
    assert not app_store.is_due(current, today)
    assert app_store.is_due({"checked": (today - timedelta(days=100)).isoformat(), "quarters": [{"period": "2026-09-30"}]}, today)


def test_never_read_stocks_come_first():
    store = app_store.empty()
    store["stocks"] = {"OLD": {"checked": "2026-01-01", "quarters": [{"period": "2025-12-31"}]}}
    assert app_store.due_order(store, {"OLD": {}, "NEW": {}}, date(2026, 10, 21)) == ["NEW", "OLD"]


def test_put_page_merges_and_keeps_the_ratios():
    store = app_store.empty()
    app_store.put_page(store, "RELIANCE", page("reliance"), date(2026, 10, 21), "consolidated")
    s = store["stocks"]["RELIANCE"]
    assert s["checked"] == "2026-10-21" and s["source"] == "consolidated"
    assert len(s["balance"]) == app_store.YEARS_KEPT and s["ratios"]["pe"] == 21.8


def test_nse_rows_become_dated_signed_values():
    nse = NSEPublicAdapter()
    rows = json.loads((FIXTURES / "nse_pit_reliance.json").read_text())["data"]
    parsed = [parse_insider(nse, r) for r in rows]
    # Who traded, how many shares, how, when NSE published it and what they held after.
    assert ["2026-02-13", "sell", 3294168.0, 2320, "BALANADU NARAYAN", "Other", "Off Market", "2026-02-18 19:06", 0.0,
            None] in parsed
    assert parsed[-1] is None  # a pledge revoked is neither a buy nor a sell
    deals = deal_rows("Date,Symbol,Security Name,Client Name,Buy/Sell,Quantity Traded,Trade Price / Wght. Avg. Price,Remarks\n"
                      "19-OCT-2026,COMSYN,Commercial Syn Bags Ltd,ARIHANT,BUY,235877,256.03,-\n", "block")
    assert deals == {"COMSYN": [["2026-10-19", "buy", round(235877 * 256.03, 2), 235877, "ARIHANT", "block"]]}
    assert iso_date("18-Feb-2026 19:06") == "2026-02-18"
    assert iso_date("01-10-2026") == "2026-10-01"
    assert iso_date("nonsense") is None


def test_app_record_scores_a_stock_from_its_page_prices_and_deals():
    today = date(2026, 10, 21)
    store = app_store.empty()
    app_store.put_page(store, "RELIANCE", page("reliance"), today, "consolidated")
    app_store.put_events(store, "RELIANCE", "insider", [["2026-02-13", "sell", 3294168.0], ["2026-03-01", "buy", 1.0e7]])
    stock = store["stocks"]["RELIANCE"]
    prices = [{"date": (today - timedelta(days=760 - i)).isoformat(), "high": 1000 + i * .3 + 5, "low": 1000 + i * .3 - 5,
               "close": 1000 + i * .3} for i in range(760)]
    research = app_store.research_input("RELIANCE", stock, prices, (25.0, 3.0))
    rec = app_store.app_record(stock, research)
    assert rec["st"] in {"HIGH_CONVICTION", "POSITIVE", "NEUTRAL", "CAUTION", "AVOID"}
    assert rec["c"] == 100.0  # every part had data
    assert set(rec["sec"]) == set(app_store.SECTION_KEYS.values())
    assert rec["q"] == "2026-06-30" and rec["d"] == "2026-10-21"
    assert rec["f"]["nq"] == 13 and rec["f"]["ib"] == 1.0 and rec["f"]["is"] == 0.3
    assert rec["f"]["pe"] > 0 and rec["f"]["fv"] > 0


def test_no_results_means_no_row():
    store = app_store.empty()
    stock = store["stocks"].setdefault("X", {})
    assert app_store.app_record(stock, app_store.research_input("X", stock, [], None)) is None


def test_an_old_consolidated_page_gives_way_to_a_newer_standalone_one():
    # Consolidated results up to March 2026, standalone up to June 2026.
    pages = {"X/consolidated/": (FIXTURES / "screener_tsc_sme.html").read_text(),
             "X/": (FIXTURES / "screener_reliance.html").read_text()}
    asked = []

    class Fake(Screener):
        def _get(self, path):
            asked.append(path)
            return 200, pages[path]

    got, source = Fake().page("X", date(2026, 12, 31))
    assert source == "standalone" and got["quarters"][-1]["period"] == "2026-06-30"
    assert asked == ["X/consolidated/", "X/"]
    asked.clear()
    got, source = Fake().page("X", date(2026, 9, 30))
    assert source == "consolidated" and asked == ["X/consolidated/"]  # a current consolidated page is enough


def test_switching_pages_drops_the_other_pages_figures():
    store = app_store.empty()
    today = date(2026, 10, 21)
    app_store.put_page(store, "R", page("reliance"), today, "consolidated")
    app_store.put_page(store, "R", page("tsc_sme"), today, "standalone")
    assert [q["period"] for q in store["stocks"]["R"]["quarters"]] == [q["period"] for q in page("tsc_sme")["quarters"]]
    assert len(store["stocks"]["R"]["shareholding"]) > len(page("tsc_sme")["shareholding"])  # holders are the same either way


def test_half_yearly_reporters_wait_half_a_year_and_late_ones_are_looked_at_monthly():
    today = date(2026, 10, 21)
    sme = [{"period": "2025-09-30"}, {"period": "2026-03-31"}]
    assert app_store.next_results(sme) == date(2026, 9, 29)
    assert app_store.next_results([{"period": "2026-06-30"}]) == date(2026, 9, 29)
    assert app_store.next_results([]) is None
    # September's half-year results are not expected before mid-October.
    assert not app_store.is_due({"checked": "2026-10-05", "quarters": sme}, date(2026, 10, 15))
    assert app_store.is_due({"checked": "2026-10-05", "quarters": sme}, today)
    # Half-yearly results through March 2026: nothing new is due in August.
    assert not app_store.is_due({"checked": "2026-07-01", "quarters": sme}, date(2026, 8, 20))
    # Four months late: once a month.
    late = [{"period": "2025-12-31"}]
    assert not app_store.is_due({"checked": "2026-10-01", "quarters": late}, today)
    assert app_store.is_due({"checked": "2026-09-20", "quarters": late}, today)
    # Not on Screener: once a month too.
    assert not app_store.is_due({"checked": "2026-10-01"}, today)
    assert app_store.is_due({"checked": "2026-09-20"}, today)


def test_never_read_stocks_go_nse_then_sme_then_bse():
    universe = {"B": {"market": "bse"}, "S": {"market": "sme"}, "N": {"market": "nse"}, "A": {"market": "bse"}}
    assert app_store.due_order(app_store.empty(), universe, date(2026, 10, 21)) == ["N", "S", "A", "B"]


def test_a_market_whose_list_failed_keeps_its_stocks():
    store = app_store.empty()
    store["stocks"] = {"N": {}, "GONE": {}, "S": {"market": "sme"}, "B": {"market": "bse"}}
    report = app_store.prune(store, {"N": {}}, date(2026, 10, 21), {"nse", "sme"})
    assert set(store["stocks"]) == {"N", "B"} and report["stocks_removed"] == 2


def test_lists_follow_the_apps_rules():
    main = nse_listings("SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, FACE VALUE\n"
                        "RELIANCE,Reliance Industries Limited,EQ,29-NOV-1995,10,1,INE002A01018,10\n"
                        "ZEEL,Zee Entertainment,BZ,01-JAN-2000,1,1,INE256A01028,1\n"
                        "bad sym,Nothing,EQ,01-JAN-2000,1,1,INE000000000,1\n", "nse")
    sme = nse_listings("﻿SYMBOL,NAME_OF_COMPANY,SERIES,DATE_OF_LISTING,PAID_UP_VALUE,MARKET_LOT,ISIN_NUMBER,FACE_VALUE\n"
                       "TSC,TSC India Limited,SM,25-JUL-2025,10,1200,INE0TSC01017,10\n"
                       "RELIANCE,Not this one,SM,01-JAN-2020,10,1,INE999999999,10\n", "sme")
    bse = bse_listings("TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,FininstrmActlXpryDt,StrkPric,OptnTp,FinInstrmNm\n"
                       "2026-10-20,2026-10-20,CM,BSE,STK,500325,INE002A01018,RELIANCE,A,,,,,RELIANCE INDUSTRIES LTD.\n"
                       "2026-10-20,2026-10-20,CM,BSE,STK,539410,INE00BS01016,BSEONLY,X,,,,,BSE ONLY LTD\n"
                       "2026-10-20,2026-10-20,CM,BSE,STK,590001,INE00ZZ01011,TSC,B,,,,,ANOTHER TSC\n"
                       "2026-10-20,2026-10-20,CM,BSE,STK,543000,INF000000001,GOLDBEES,E,,,,,NIPPON GOLD ETF\n"
                       "2026-10-20,2026-10-20,CM,BSE,STK,543001,INF000000002,SILVERX,B,,,,,SILVER BEES\n"
                       "2026-10-20,2026-10-20,CM,BSE,STK,950001,IN0020000001,GSEC26,GS,,,,,GOI 2026\n")
    assert set(main) == {"RELIANCE", "ZEEL"} and main["RELIANCE"]["isin"] == "INE002A01018"
    assert sme["TSC"] == {"name": "TSC India Limited", "isin": "INE0TSC01017", "market": "sme", "code": ""}
    assert set(bse) == {"RELIANCE", "BSEONLY", "TSC"}
    universe = combine(main, sme, bse)
    # Dual-listed shares stay NSE's; a BSE scrip under an NSE symbol never takes its place.
    assert {s: u["market"] for s, u in universe.items()} == {"RELIANCE": "nse", "ZEEL": "nse", "TSC": "sme", "BSEONLY": "bse"}
    assert universe["BSEONLY"]["code"] == "539410"


def test_bse_only_stocks_are_found_by_their_code():
    nse_stock, bse_stock = {"market": "nse"}, {"market": "bse", "code": "539410"}
    assert screener_slug("RELIANCE", nse_stock) == "RELIANCE" and screener_slug("BSEONLY", bse_stock) == "539410"
    assert yahoo_tickers("TSC", {"market": "sme"}) == ["TSC.NS"]
    assert yahoo_tickers("BSEONLY", bse_stock) == ["BSEONLY.BO", "539410.BO"]
    assert yahoo_tickers("OLD", {}) == ["OLD.NS"]  # stocks stored before markets were recorded are NSE's


def test_without_a_chart_screeners_price_values_the_stock():
    stock = {"ratios": {"price": 120.0}, "quarters": [{"period": "2026-03-31"}]}
    assert app_store.research_input("X", stock, [], None).price == 120.0


def test_a_half_yearly_reporters_twelve_months_are_two_half_years():
    today = date(2026, 10, 21)
    store = app_store.empty()
    app_store.put_page(store, "TSC", page("tsc_sme"), today, "standalone")
    stock = store["stocks"]["TSC"]
    halves = stock["quarters"]
    assert app_store.half_yearly(halves) and not app_store.half_yearly(page("reliance")["quarters"])
    assert app_store.eps_ttm(stock) == halves[-1]["eps"] + halves[-2]["eps"]
    rec = app_store.app_record(stock, app_store.research_input("TSC", stock, [], None))
    assert rec["f"]["hy"] == 1 and rec["f"]["nq"] == 4
    assert "hy" not in app_store.app_record(stock | {"quarters": page("reliance")["quarters"]},
                                            app_store.research_input("R", stock | {"quarters": page("reliance")["quarters"]}, [], None))["f"]


def _daily(start, days, price):
    return [{"date": (start + timedelta(days=i)).isoformat(), "close": price(i), "high": price(i), "low": price(i)}
            for i in range(days)]


def test_the_companys_own_pe_is_its_usual_multiple_over_the_price_history():
    quarters = [{"period": p, "eps": 1.0} for p in ("2024-03-31", "2024-06-30", "2024-09-30", "2024-12-31",
                                                     "2025-03-31", "2025-06-30", "2025-09-30", "2025-12-31")]
    stock = {"quarters": quarters}
    # Twelve months' EPS of 4 is public from mid-February 2025; at 80 the stock trades at 20 times it.
    prices = _daily(date(2024, 1, 1), 900, lambda i: 80.0)
    assert app_store.history_pe(stock, prices) == 20.0
    assert app_store.history_pe(stock, prices[:500]) is None  # under twelve month-ends with a known P/E
    assert app_store.history_pe({"quarters": quarters[:3]}, prices) is None


def test_valuation_weighs_the_sector_and_the_companys_own_history_alike():
    from cr360.engine import score_valuation
    from cr360.models import ResearchInput
    # CDSL on 21 Oct 2026: ₹1,237 against ₹274 from its sector's multiples and ₹1,353 from its own usual P/E of 60.
    x = ResearchInput(symbol="CDSL", price=1237.2, valuation={"earnings_fair_value": 388.76, "book_fair_value": 159.78,
                                                                "history_fair_value": 1353.0})
    score, fair, missing = score_valuation(x)
    assert fair["base"] == 274.27 and fair["history"] == 1353.0 and round(fair["history_upside_pct"], 1) == 9.4
    assert round(score, 1) == round((0 + 50 + 9.36 * 1.5) / 2, 1) and not missing
    only_sector = ResearchInput(symbol="X", price=100.0, valuation={"earnings_fair_value": 120.0})
    assert score_valuation(only_sector)[0] == 80.0  # as before when there is no history
    only_history = ResearchInput(symbol="X", price=100.0, valuation={"history_fair_value": 90.0})
    assert score_valuation(only_history)[0] == 35.0


def test_the_app_row_carries_both_fair_values_and_insider_only_when_read():
    today = date(2026, 10, 21)
    store = app_store.empty()
    app_store.put_page(store, "RELIANCE", page("reliance"), today, "consolidated")
    stock = store["stocks"]["RELIANCE"]
    prices = _daily(today - timedelta(days=1095), 1095, lambda i: 1200.0 + i * .2)
    rec = app_store.app_record(stock, app_store.research_input("RELIANCE", stock, prices, (25.0, 3.0)))
    f = rec["f"]
    assert f["hpe"] > 0 and f["fh"] > 0 and "uh" in f and f["oq"] == 12
    assert "ib" not in f and "is" not in f  # NSE's insider filings were never read for it
    stock["insider_checked"] = "2026-10-21"
    assert app_store.app_record(stock, app_store.research_input("RELIANCE", stock, prices, None))["f"]["ib"] == 0.0


def test_history_rows_are_compact_and_complete():
    store = app_store.empty()
    app_store.put_page(store, "RELIANCE", page("reliance"), date(2026, 10, 21), "consolidated")
    h = app_store.history_record(store["stocks"]["RELIANCE"])
    assert len(h["q"]) == 13 and h["q"][-1][0] == "2606" and h["q"][-1][2] == 23196
    assert len(h["s"]) == 12 and h["s"][-1][1] == 50.48
    assert len(h["y"]) == 5 and all(len(y) == 6 for y in h["y"]) and h["y"][-1][0].endswith("03")
    assert app_store.history_record({}) is None


def test_monthly_closes_keep_a_month_without_trading_as_a_gap():
    rows = [{"date": "2026-01-30", "close": 10.0}, {"date": "2026-01-31", "close": 11.0}, {"date": "2026-03-31", "close": 12.0}]
    assert app_store.monthly_closes(rows) == {"m": "2601", "c": [11.0, None, 12.0]}
    assert app_store.monthly_closes(rows[:1]) is None


def test_history_files_are_chosen_as_the_app_chooses_them():
    # The same numbers are checked in the app (Cr360HistoryTest), so both sides find a stock in the same file.
    assert {s: app_store.history_shard(s) for s in ("CDSL", "RELIANCE", "TSC", "M&M")} == \
        {"CDSL": 31, "RELIANCE": 8, "TSC": 3, "M&M": 8}


def test_support_and_room_above_come_from_the_sessions_before_today():
    from cr360.engine import score_price
    from cr360.models import ResearchInput
    # A stock swinging between 94 and 106, back at 100 today: its own close was its support and resistance before.
    swing = [100.0, 103.0, 106.0, 103.0, 100.0, 97.0, 94.0, 97.0]
    rows = _daily(date(2024, 1, 1), 297, lambda i: swing[i % 8])
    x = ResearchInput(symbol="X", price=rows[-1]["close"], price_history=rows)
    _, _, technical, risk, _ = score_price(x)
    assert risk["price"] == 100.0 and risk["support_proxy"] == 97.0 and risk["resistance_proxy"] == 103.0
    assert risk["risk_reward_proxy"] == 1.0 and technical > 25  # every stock scored under 25 before
    # At its lowest close in three years nothing holds it up, and at its highest the three-year high is the room above.
    low = ResearchInput(symbol="X", price=90.0, price_history=rows[:-1] + [{**rows[-1], "close": 90.0, "low": 90.0}])
    assert score_price(low)[3]["support_proxy"] is None


def test_growth_compares_four_quarters_with_four_and_gives_no_percent_from_nothing():
    from cr360.engine import change
    assert change([100, 100, 100, 100, 150, 150, 150, 150]) == (50.0, 75.0)
    assert change([0.0, 0.0, 0.01, 0.0, 30, 40, 50, 60]) == (None, 100)  # +3,66,200 crore % before
    assert change([-5, -5, -5, -5, 5, 5, 5, 5]) == (None, 100)  # from a loss into profit
    assert change([-10, -10, -10, -10, -5, -5, -5, -5]) == (None, 60)  # a loss shrinking
    assert change([-5, -5, -5, -5, -10, -10, -10, -10]) == (None, 0)
    assert change([10, 10, 10, 10, -10, -10, -10, -10]) == (-200.0, 0)
    assert change([4, 6]) == (50.0, 75.0) and change([5]) == (None, None)


def test_a_one_off_quarter_does_not_make_the_year_and_unbelievable_earnings_value_nothing():
    quarters = lambda eps: [{"period": p, "eps": e} for p, e in zip(("2025-09-30", "2025-12-31", "2026-03-31", "2026-06-30"), eps)]
    # SKF India's misprinted 76,930 in one quarter made its P/E 0.0 and its fair value ₹10 lakh.
    one_off = {"quarters": quarters([76930.0, -10.13, 24.06, 12.52]), "ratios": {"book_value": 299.0}}
    assert round(app_store.eps_ttm(one_off), 2) == 44.74  # 76,930 counts as the middle quarter, 18.29
    x = app_store.research_input("SKFINDUS", one_off | {"ratios": {"book_value": 299.0, "price": 2464.0}}, [], (30.0, 4.0))
    assert round(x.valuation["earnings_fair_value"]) == 1342
    assert app_store.eps_ttm({"quarters": quarters([10.0, 12.0, 11.0, 25.0])}) == 58.0  # an ordinary year is its sum
    # Earnings priced at 0.2 times (a written-off debt in the year) and a loss with a big book value give none.
    for eps, book, price in (([-2.51, -0.17, 38.72, 20.48], 10.0, 6.42), ([-1.0, -1.0, -1.0, -1.0], 308.0, 12.0)):
        stock = {"quarters": quarters(eps), "ratios": {"book_value": book, "price": price}}
        assert app_store.research_input("X", stock, [], (20.0, 2.0)).valuation == {}
    # A sector needs five believable P/Es: four at 10 times and one at half its year's earnings make none.
    store = {"stocks": {f"S{i}": {"quarters": quarters([1.0] * 4), "ratios": {"book_value": 10.0}} for i in range(5)}}
    assert app_store.sector_anchors(store, {f"S{i}": 40.0 for i in range(5)}, {}) == {"": (10.0, 4.0)}
    assert app_store.sector_anchors(store, {f"S{i}": 40.0 for i in range(4)} | {"S4": 2.0}, {}) == {}


def test_lenders_debt_and_cash_flow_are_not_judged():
    today = date(2026, 10, 21)
    store = app_store.empty()
    app_store.put_page(store, "RELIANCE", page("reliance"), today, "consolidated")
    app_store.put_events(store, "RELIANCE", "insider", [["2026-03-01", "buy", 1.0e7]])
    stock = store["stocks"]["RELIANCE"]
    prices = _daily(today - timedelta(days=1095), 1095, lambda i: 1200.0 + i * .2)
    lender = app_store.app_record(stock, app_store.research_input("X", stock, prices, (25.0, 3.0), financial=True))
    assert lender["sec"]["cf"] is None and lender["sec"]["bs"] is None and lender["c"] == 100.0  # nothing is missing
    other = app_store.app_record(stock, app_store.research_input("X", stock, prices, (25.0, 3.0)))
    assert other["sec"]["cf"] is not None and other["sec"]["bs"] is not None and other["c"] == 100.0


def test_a_named_trade_takes_the_place_of_the_same_trade_kept_without_a_name():
    old = [["2026-02-13", "sell", 3294168.0], ["2026-03-01", "buy", 1.0e7]]
    named = ["2026-02-13", "sell", 3294168.0, 2320, "BALANADU NARAYAN", "Other", "Off Market", "2026-02-18 19:06", 0.0, None]
    same_day_other = ["2026-02-13", "sell", 3294168.0, 2320, "SOMEONE ELSE", "Other", "Off Market", "2026-02-18 19:07", 0.0, None]
    merged = app_store.merge_events(old, [named, same_day_other])
    assert merged == [named, same_day_other, ["2026-03-01", "buy", 1.0e7]]
    assert app_store.merge_events(merged, [named]) == merged  # read again: no repeats
    assert app_store.merge_events([named], old[:1]) == [named]  # an old row read after the named one adds nothing


def test_history_carries_every_insider_trade_and_deal():
    stock = {"quarters": [{"period": "2026-06-30", "revenue": 1.0, "net_profit": 1.0, "eps": 1.0}],
             "insider": [["2026-02-13", "sell", 3294168.0, 2320, "A", "Other", "Off Market", "2026-02-18 19:06", 0.0, None]],
             "deals": [["2026-10-19", "buy", 6.0e7, 235877, "ARIHANT", "bulk"]]}
    h = app_store.history_record(stock)
    assert h["i"] == stock["insider"] and h["d"] == stock["deals"]
    assert "i" not in app_store.history_record({"quarters": stock["quarters"]})


def test_insider_trades_are_topped_up_first_and_old_rows_read_again_in_full(monkeypatch):
    from cr360 import build_app_cache as b
    today = date(2026, 10, 23)
    named = ["2026-10-20", "buy", 5.0e6, 100, "A", "Promoter", "Market Purchase", "2026-10-21 18:30", 51.2, None]
    store = {"stocks": {
        "OLD": {"insider_checked": "2026-10-22", "insider": [["2026-01-05", "sell", 1.0e6]]},  # checked yesterday, no names
        "WEEK": {"insider_checked": "2026-10-10", "insider": []},
        "FRESH": {"insider_checked": "2026-10-20", "insider": []},
    }}
    asked = []

    def fake(nse, sym, start, end, index="equities"):
        asked.append((sym, start))
        return [["2026-01-05", "sell", 1.0e6, 10, "B", "Director", "Market Sale", "2026-01-06 20:00", 1.0, None]] if sym == "OLD" else [named]
    monkeypatch.setattr(b, "insider_events", fake)
    monkeypatch.setattr(b, "INSIDER_GAP", 0)
    b.top_up_insiders(store, None, today, until=float("inf"))
    assert asked == [("OLD", today - timedelta(days=365 * 5)), ("WEEK", date(2026, 10, 7))]  # FRESH waits its week
    assert store["stocks"]["OLD"]["insider"][0][4] == "B" and len(store["stocks"]["OLD"]["insider"]) == 1
    assert store["stocks"]["WEEK"]["insider"] == [named] and store["stocks"]["WEEK"]["insider_checked"] == "2026-10-23"
    # NSE refusing stock after stock ends the top-up for the run.
    asked.clear()
    monkeypatch.setattr(b, "insider_events", lambda *a, **k: asked.append(a[1]) or None)
    many = {"stocks": {f"S{i}": {"insider_checked": "2026-10-01", "insider": []} for i in range(9)}}
    b.top_up_insiders(many, None, today, until=float("inf"))
    assert len(asked) == b.INSIDER_FAILURES
