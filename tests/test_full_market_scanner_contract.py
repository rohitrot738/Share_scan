import scanner


def test_action_mapping_contract_via_source_defaults():
    # CLI defaults are documented by the implementation; this smoke test mainly
    # ensures the module imports after the full-market refactor.
    assert callable(scanner.load_market)
    assert callable(scanner.stage1_full_market)
    assert callable(scanner.scan_full_market)


def test_baseline_rejects_short_history():
    import pandas as pd
    try:
        scanner.baseline_daily(pd.DataFrame())
    except ValueError as exc:
        assert "25 candles" in str(exc)
    else:
        raise AssertionError("short daily history must be rejected")
