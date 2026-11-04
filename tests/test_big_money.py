"""बड़े पैसे की चाल (bigmoney/): NSE's delivery file, the four signals, the score, the store, the track record and
the evening build."""
from datetime import date

from bigmoney.nse_files import Row, fetch_delivery, parse_delivery

HEADER = ("SYMBOL, SERIES, DATE1, PREV_CLOSE, OPEN_PRICE, HIGH_PRICE, LOW_PRICE, LAST_PRICE, CLOSE_PRICE, AVG_PRICE, "
          "TTL_TRD_QNTY, TURNOVER_LACS, NO_OF_TRADES, DELIV_QTY, DELIV_PER")
SAMPLE = "\n".join([
    HEADER,
    "RELIANCE, EQ, 04-Nov-2026, 1307.90, 1301.90, 1318.90, 1296.00, 1311.00, 1311.00, 1308.40, 19451679, "
    "254505.47, 308327, 9831252, 50.54",
    "3IINFOLTD, BE, 04-Nov-2026, 19.30, 19.30, 19.31, 18.84, 19.05, 19.13, 19.11, 88453, 16.91, 668, -, -",
    "AAKAAR, SM, 04-Nov-2026, 71.70, 73.00, 73.00, 68.45, 70.90, 70.90, 70.93, 9600, 6.81, 6, 8000, 83.33",
    "GOI2033, GS, 04-Nov-2026, 101.00, 101.00, 101.00, 101.00, 101.00, 101.00, 101.00, 10, 0.01, 1, 10, 100.00",
])


def test_parse_delivery_keeps_eq_and_sm():
    day, rows = parse_delivery(SAMPLE)
    assert day == "2026-11-04"
    assert set(rows) == {"RELIANCE", "AAKAAR"}
    assert rows["RELIANCE"] == Row("EQ", 1311.0, 1307.9, 19451679, 50.54)
    assert rows["AAKAAR"] == Row("SM", 70.9, 71.7, 9600, 83.33)


def test_parse_delivery_reads_nothing_from_an_error_page():
    assert parse_delivery("<!DOCTYPE html><html></html>") == (None, {})


def test_fetch_delivery_rejects_error_pages():
    assert fetch_delivery(date(2026, 11, 4), get=lambda *a, **k: (200, "<html>", {})) is None
    assert fetch_delivery(date(2026, 11, 4), get=lambda *a, **k: (404, "", {})) is None


def test_fetch_delivery_asks_for_the_day():
    asked = []

    def get(url, *a, **k):
        asked.append(url)
        return 200, SAMPLE, {}

    assert fetch_delivery(date(2026, 11, 4), get=get) == SAMPLE
    assert asked == ["https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_04112026.csv"]


# --- signals, deal and promoter net, score ------------------------------------------------------------------------

from bigmoney.rules import (Day, DealNet, PromoterNet, Ratios, accumulation, churn, deal_net, flags,  # noqa: E402
                            promoter_net, ratios, score)


def make_days(deliv, today_deliv, change=1.0, volume=200_000, n=20, avg_volume=100_000):
    """`n` quiet sessions at `deliv`% delivery and `avg_volume` shares, then today."""
    out = [Day(f"2026-10-{i + 1:02d}", 100.0, 100.0, avg_volume, deliv) for i in range(n)]
    out.append(Day("2026-11-04", 100.0 + change, 100.0, volume, today_deliv))
    return out


def test_accumulation_threshold():
    assert accumulation(make_days(20.0, 37.0))  # 1.85x the 20-session average
    assert not accumulation(make_days(20.0, 35.0))  # 1.75x
    assert not accumulation(make_days(20.0, 37.0, change=2.5))  # price not calm
    assert not accumulation(make_days(20.0, 37.0, change=-2.5))
    assert not accumulation(make_days(20.0, 37.0, volume=90_000))  # too thin


def test_accumulation_needs_15_sessions():
    assert accumulation(make_days(20.0, 37.0, n=15))
    assert not accumulation(make_days(20.0, 37.0, n=14))


def test_gaps_are_none():
    days = make_days(20.0, 37.0)
    for i in range(6):
        days[i] = days[i]._replace(deliv_pct=None, volume=None, close=None)
    assert not accumulation(days)  # 14 sessions with a delivery figure
    assert not churn(days)
    days[-1] = days[-1]._replace(deliv_pct=None)
    assert not accumulation(days) and ratios(days).spike is None


def test_churn():
    assert churn(make_days(15.0, 15.0, volume=450_000))  # 4.5x volume, 15% delivery
    assert not churn(make_days(15.0, 15.0, volume=350_000))
    assert not churn(make_days(25.0, 25.0, volume=450_000))


def test_ratios():
    r = ratios(make_days(20.0, 40.0, change=1.5, volume=300_000))
    assert r.spike == 2.0 and r.volume == 3.0 and r.change == 1.5
    assert r.build == 1.2  # (20+20+20+20+40)/5 = 24, over 20


SESSIONS = [f"2026-10-{d:02d}" for d in range(1, 31)]
DEALS = [
    ["2026-09-15", "buy", 5e6, 50_000, "OLD", "bulk"],  # before the 30 sessions
    ["2026-10-10", "buy", 1e6, 10_000, "X", "bulk"],
    ["2026-10-12", "sell", 3e6, 30_000, "X", "bulk"],  # X bought and sold two sessions apart
    ["2026-10-20", "buy", 4e6, 40_000, "A", "bulk"],
    ["2026-10-25", "buy", 2e6, 20_000, "B", "block"],
    ["2026-10-25", "sell", 1e6, 10_000, "C", "block"],
]


def test_deal_net_streak_and_circular():
    net = deal_net(DEALS, SESSIONS)
    assert net == DealNet(30_000, 3e6, 4, 2, ["X"])
    assert deal_net(DEALS[:1], SESSIONS) is None
    selling = deal_net([["2026-10-28", "sell", 1e6, 9_000, "Z", "bulk"], ["2026-10-29", "sell", 1e6, 9_000, "Z", "bulk"]],
                       SESSIONS)
    assert selling.streak == -2 and selling.circular == []


def test_promoter_net_rules():
    rows = [
        ["2026-10-20", "buy", 2e7, 1, "P1", "Promoters", "Market Purchase"],
        ["2026-10-21", "sell", 5e6, 1, "P2", "Promoter Group", "Market Sale"],
        ["2026-10-22", "buy", 1e7, 1, "P3", "Promoters", "ESOP"],
        ["2026-10-23", "buy", 1e7, 1, "D", "Director", "Market Purchase"],
        ["3034-03-05", "buy", 9e9, 1, "P1", "Promoters", "Market Purchase"],  # a mistyped filing date
        ["2026-07-27", "buy", 3e7, 1, "P1", "Promoters", "Market Purchase"],  # 100 days back
        ["2026-10-24", "buy", 1e7, 1, "P1", "Promoters"],  # a short row from an older cache
    ]
    assert promoter_net(rows, date(2026, 11, 4)) == PromoterNet(1.5e7, 1, 1)
    assert promoter_net(rows[2:4], date(2026, 11, 4)) is None


def test_score_edges():
    assert score(Ratios(3.0, 1.5, None, 0.0), None, None, False, None) == (50, [30, 20, 0, 0, 0, 0])
    assert score(Ratios(2.0, 1.0, None, 0.0), None, None, False, None)[0] == 15
    full_deal = DealNet(100_000, 1e7, 2, 2, [])
    assert score(Ratios(1.0, 1.0, None, 0.0), full_deal, PromoterNet(5e7, 1, 0), False, 100_000)[0] == 50
    assert score(Ratios(1.0, 1.0, None, 0.0), DealNet(50_000, 5e6, 1, 1, []), None, False, 100_000)[0] == 12
    assert score(Ratios(3.0, 1.0, None, 0.0), None, None, True, None) == (0, [30, 0, 0, 0, -30, 0])
    selling = score(Ratios(3.0, 1.5, None, 0.0), DealNet(-100_000, -1e7, 2, -2, []), PromoterNet(-2.5e7, 0, 1), False,
                    100_000)
    assert selling == (12, [30, 20, 0, 0, 0, -37.5])
    assert score(Ratios(None, None, None, None), None, None, False, None) == (0, [0, 0, 0, 0, 0, 0])


def test_flags():
    assert flags(True, False, DealNet(1, 1.0, 2, 2, []), PromoterNet(1.0, 1, 0)) == ["acc", "deal_buy", "promoter_buy"]
    assert flags(False, True, DealNet(-1, -1.0, 3, -2, []), PromoterNet(-1.0, 0, 1)) == ["churn", "deal_sell", "promoter_sell"]
    assert flags(False, False, DealNet(1, 1.0, 1, 1, []), None) == []


# --- the store and the track record -------------------------------------------------------------------------------

from bigmoney.record import track_record  # noqa: E402
from bigmoney.store import add_deals, add_session, empty_store, history, prune  # noqa: E402


def row(close=100.0, prev=100.0, volume=100_000, deliv=20.0, series="EQ"):
    return Row(series, close, prev, volume, deliv)


def test_add_session_aligns_and_fills():
    store = empty_store()
    assert add_session(store, "2026-11-02", {"A": row(), "B": row()})
    assert add_session(store, "2026-11-03", {"A": row(101.0), "C": row(50.0)})
    assert not add_session(store, "2026-11-03", {"A": row()})
    assert store["dates"] == ["2026-11-02", "2026-11-03"]
    assert store["stocks"]["A"]["c"] == [100.0, 101.0]
    assert store["stocks"]["B"]["c"] == [100.0, None]
    assert store["stocks"]["C"]["c"] == [None, 50.0]
    # A session found late (the job missed a day) goes in its place.
    assert add_session(store, "2026-10-30", {"A": row(99.0)})
    assert store["dates"] == ["2026-10-30", "2026-11-02", "2026-11-03"]
    assert store["stocks"]["A"]["c"] == [99.0, 100.0, 101.0]
    assert store["stocks"]["C"]["d"] == [None, None, 20.0]


def test_prune_keeps_latest():
    store = empty_store()
    for d in range(1, 6):
        add_session(store, f"2026-10-0{d}", {"A": row(float(d))} | ({"B": row()} if d == 1 else {}))
    add_deals(store, {"A": [["2026-10-01", "buy", 1.0, 1, "X", "bulk"], ["2026-10-04", "buy", 1.0, 1, "X", "bulk"]]})
    prune(store, keep=3)
    assert store["dates"] == ["2026-10-03", "2026-10-04", "2026-10-05"]
    assert store["stocks"]["A"]["c"] == [3.0, 4.0, 5.0]
    assert "B" not in store["stocks"]  # nothing left of it
    assert store["deals"]["A"] == [["2026-10-04", "buy", 1.0, 1, "X", "bulk"]]


def test_add_deals_once_each():
    store = empty_store()
    deal = ["2026-11-03", "buy", 1e6, 10_000, "X", "bulk"]
    assert add_deals(store, {"A": [deal]}) == 1
    assert add_deals(store, {"A": [list(deal)]}) == 0
    assert store["deals"]["A"] == [deal]


def test_history_is_the_21_days_to_a_session():
    store = empty_store()
    for i in range(25):
        add_session(store, f"2026-09-{i + 1:02d}", {"A": row(100.0 + i)})
    days = history(store, "A", 24)
    assert len(days) == 21 and days[-1].close == 124.0 and days[0].close == 104.0
    assert len(history(store, "A", 5)) == 6


def record_store():
    """Three flat stocks over 40 sessions; A's delivery doubles on session 25 and its price is 10% up 10 sessions on."""
    store = empty_store()
    for i in range(40):
        a = row(110.0 if i >= 35 else 100.0, 110.0 if i > 35 else 100.0, 200_000 if i == 25 else 100_000,
                40.0 if i == 25 else 20.0)
        add_session(store, f"2026-{8 + i // 28:02d}-{i % 28 + 1:02d}", {"A": a, "B": row(), "C": row()})
    return store


def test_track_record_hand_worked():
    rec = track_record(record_store(), {}, horizon=10, min_cases=1)
    assert rec["acc"] == {"n": 1, "avg": 10.0, "base": 3.33, "up": 100.0}
    assert rec["churn"]["n"] == 0 and rec["churn"]["avg"] is None
    assert set(rec) == {"acc", "churn", "deal_buy", "deal_sell", "promoter_buy", "promoter_sell", "score60"}


def test_track_record_needs_30_cases():
    rec = track_record(record_store(), {}, horizon=10)
    assert rec["acc"] == {"n": 1, "avg": None, "base": None, "up": None}
