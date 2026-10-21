"""The app's 360CR cache: Screener pages, the store's merging and pruning, when pages are read again, the app rows."""
import json
from datetime import date, timedelta
from pathlib import Path

from cr360 import app_store, screener_page
from cr360.build_app_cache import deal_rows, iso_date, parse_insider
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
    assert ["2026-02-13", "sell", 3294168.0] in parsed
    deals = deal_rows("Date,Symbol,Security Name,Client Name,Buy/Sell,Quantity Traded,Trade Price / Wght. Avg. Price,Remarks\n"
                      "19-OCT-2026,COMSYN,Commercial Syn Bags Ltd,ARIHANT,BUY,235877,256.03,-\n")
    assert deals == {"COMSYN": [["2026-10-19", "buy", round(235877 * 256.03, 2)]]}
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
