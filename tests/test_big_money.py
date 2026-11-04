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
