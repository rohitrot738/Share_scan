from datetime import date
import gzip

import pandas as pd

from market_cap import (
    NSEIssuedCapitalSnapshot,
    apply_market_cap_filter,
    parse_nse_security_file,
)


def test_parse_nse_security_file_keeps_live_eq_rows_only():
    raw = (
        "TckrSymb,SctySrs,IssdCptl,DelFlg\n"
        "BIG,EQ,20000000,N\n"
        "BIG,BE,99999999,N\n"
        "DELETED,EQ,30000000,Y\n"
        "BROKEN,EQ,not-a-number,N\n"
    ).encode("utf-8")

    snapshot = parse_nse_security_file(
        gzip.compress(raw),
        as_of=date(2026, 9, 1),
    )

    assert snapshot.as_of == date(2026, 9, 1)
    assert snapshot.issued_shares == {"BIG": 20_000_000.0}


def test_market_cap_filter_is_strictly_above_1000_crore_and_fail_closed():
    snapshot = NSEIssuedCapitalSnapshot(
        as_of=date(2026, 9, 1),
        issued_shares={
            "ABOVE": 10_000_001,
            "EQUAL": 10_000_000,
            "BELOW": 9_999_999,
        },
        source_url="https://nsearchives.nseindia.com/example.csv.gz",
    )
    rows = [
        {"symbol": "ABOVE", "exchange": "NSE", "price": 1000.0},
        {"symbol": "EQUAL", "exchange": "NSE", "price": 1000.0},
        {"symbol": "BELOW", "exchange": "NSE", "price": 1000.0},
        {"symbol": "MISSING", "exchange": "NSE", "price": 5000.0},
        {"symbol": "BSEONLY", "exchange": "BSE", "price": 5000.0},
    ]

    eligible, stats = apply_market_cap_filter(rows, snapshot, min_market_cap_cr=1000.0)

    assert [row["symbol"] for row in eligible] == ["ABOVE"]
    assert eligible[0]["market_cap_cr"] == 1000.0001
    assert eligible[0]["issued_shares"] == 10_000_001
    assert eligible[0]["market_cap_source_date"] == "2026-09-01"
    assert stats == {
        "input_rows": 5,
        "eligible_rows": 1,
        "at_or_below_limit": 2,
        "missing_market_cap": 2,
    }


def test_market_cap_filter_does_not_mutate_stage1_rows():
    original = {"symbol": "SAFE", "exchange": "NSE", "price": 2000.0}
    snapshot = NSEIssuedCapitalSnapshot(
        as_of=date(2026, 9, 1),
        issued_shares={"SAFE": 10_000_000},
        source_url="https://nsearchives.nseindia.com/example.csv.gz",
    )

    eligible, _ = apply_market_cap_filter([original], snapshot, min_market_cap_cr=1000.0)

    assert "market_cap_cr" not in original
    assert eligible[0]["market_cap_cr"] == 2000.0


def test_market_cap_filter_accepts_cache_close_price_field():
    snapshot = NSEIssuedCapitalSnapshot(
        as_of=date(2026, 9, 1),
        issued_shares={"CACHE": 10_000_000},
        source_url="https://nsearchives.nseindia.com/example.csv.gz",
    )

    eligible, stats = apply_market_cap_filter(
        [{"symbol": "CACHE", "exchange": "NSE", "close": 2000.0}],
        snapshot,
        min_market_cap_cr=1000.0,
    )

    assert [row["symbol"] for row in eligible] == ["CACHE"]
    assert eligible[0]["market_cap_cr"] == 2000.0
    assert stats["missing_market_cap"] == 0


def test_full_scanner_filters_market_cap_before_deep_analysis(monkeypatch):
    import scanner
    from market_data import Instrument

    instruments = [
        Instrument("BIG", "NSE", "BIG.NS", "Big Ltd"),
        Instrument("SMALL", "NSE", "SMALL.NS", "Small Ltd"),
        Instrument("UNKNOWN", "NSE", "UNKNOWN.NS", "Unknown Ltd"),
    ]
    stage1_rows = [
        {"symbol": "BIG", "exchange": "NSE", "yahoo_symbol": "BIG.NS", "price": 2000.0},
        {"symbol": "SMALL", "exchange": "NSE", "yahoo_symbol": "SMALL.NS", "price": 2000.0},
        {"symbol": "UNKNOWN", "exchange": "NSE", "yahoo_symbol": "UNKNOWN.NS", "price": 5000.0},
    ]
    snapshot = NSEIssuedCapitalSnapshot(
        as_of=date(2026, 9, 1),
        issued_shares={"BIG": 10_000_000, "SMALL": 1_000_000},
        source_url="https://nsearchives.nseindia.com/example.csv.gz",
    )
    deep_symbols = []

    monkeypatch.setattr(scanner, "load_market", lambda market: instruments)
    monkeypatch.setattr(scanner, "stage1_full_market", lambda universe: (stage1_rows, {}))
    monkeypatch.setattr(scanner, "fetch_nse_issued_capital", lambda: snapshot)

    def fake_download_all_frames(selected):
        deep_symbols.extend(item.symbol for item in selected)
        return {item.yahoo_symbol: {} for item in selected}, {}

    monkeypatch.setattr(scanner, "download_all_frames", fake_download_all_frames)
    monkeypatch.setattr(
        scanner,
        "analyse_symbol",
        lambda inst, frames, stage1: {
            **stage1,
            "ghost_status": "OK",
            "volume_flow_score": 80,
            "rvol20": 2,
            "ghost_score": 85,
            "false_breakout_risk": 10,
            "volume": 100,
        },
    )

    rows, shortlist, errors, feed_errors = scanner.scan_full_market(
        "NSE", shortlist=10, deep=10
    )

    assert [row["symbol"] for row in shortlist] == ["BIG"]
    assert [row["symbol"] for row in rows] == ["BIG"]
    assert deep_symbols == ["BIG"]
    assert errors == {}
    assert feed_errors == {}


def test_live_cache_requires_and_rechecks_market_cap(monkeypatch, tmp_path):
    import live_scan

    cache_file = tmp_path / "nse_stage1.csv"
    monkeypatch.setattr(live_scan, "CACHE_FILE", cache_file)
    frame = pd.DataFrame(
        [
            {
                "symbol": "ABOVE",
                "exchange": "NSE",
                "yahoo_symbol": "ABOVE.NS",
                "score": 90,
                "turnover_proxy": 1,
                "current_volume": 100,
                "market_cap_cr": 1000.0001,
                "issued_shares": 10_000_001,
                "market_cap_source_date": "2026-09-01",
            },
            {
                "symbol": "EQUAL",
                "exchange": "NSE",
                "yahoo_symbol": "EQUAL.NS",
                "score": 99,
                "turnover_proxy": 2,
                "current_volume": 200,
                "market_cap_cr": 1000.0,
                "issued_shares": 10_000_000,
                "market_cap_source_date": "2026-09-01",
            },
        ]
    )
    frame.to_csv(cache_file, index=False)

    assert live_scan.cache_is_valid(1) is True
    loaded = live_scan.load_stage1_cache(1)
    assert loaded.symbol.tolist() == ["ABOVE"]
    assert (loaded.market_cap_cr > 1000).all()

    frame.drop(columns=["market_cap_cr"]).to_csv(cache_file, index=False)
    assert live_scan.cache_is_valid(1) is False

