import json
from xml.etree import ElementTree as ET

from reporting import write_scan_bundle


def test_offline_dashboard_and_xml_are_complete(tmp_path):
    payload = {
        "generated_at_utc": "2026-09-02T10:00:00+00:00",
        "mode": "ORDERED_ALL_CHECKS_NSE_SCAN",
        "counts": {"market_cap": 3, "final_pass": 1},
        "errors": {"feeds": {"1h:1": "provider timeout"}},
        "final_pass": [{"rank": 1, "symbol": "TEST", "ghost_score": 88.5}],
    }

    files = write_scan_bundle(tmp_path, payload)

    assert files["dashboard"].is_file()
    assert (tmp_path / "dashboard.css").is_file()
    assert (tmp_path / "dashboard.js").is_file()
    data = (tmp_path / "dashboard_data.js").read_text(encoding="utf-8")
    assert data.startswith("window.SHARE_SCAN_DATA = ")
    assert '"symbol":"TEST"' in data

    root = ET.parse(files["xml"]).getroot()
    assert root.tag == "share_scan_report"
    assert root.findtext("metadata/result_count") == "1"
    stock = root.find("results/stock")
    assert stock is not None and stock.attrib == {"rank": "1", "symbol": "TEST"}


def test_dashboard_data_is_valid_json_assignment(tmp_path):
    files = write_scan_bundle(tmp_path, {"results": [{"symbol": "A&B"}]})
    text = files["data"].read_text(encoding="utf-8")
    raw = text.removeprefix("window.SHARE_SCAN_DATA = ").removesuffix(";\n")
    assert json.loads(raw)["rows"][0]["symbol"] == "A&B"
