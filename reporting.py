from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from xml.etree import ElementTree as ET


DASHBOARD_SOURCE = Path(__file__).with_name("dashboard")


def _first_rows(payload: dict[str, Any]) -> list[dict[str, Any]]:
    for key in ("final_pass", "ranked", "results", "stage5_ghost_score", "stage4_ready_confirmed"):
        value = payload.get(key)
        if isinstance(value, list):
            return [row for row in value if isinstance(row, dict)]
    return []


def _scalar(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (str, int, float)):
        return str(value)
    return json.dumps(value, ensure_ascii=False, default=str, separators=(",", ":"))


def write_xml_report(
    output_path: str | Path,
    payload: dict[str, Any],
    *,
    rows: Iterable[dict[str, Any]] | None = None,
) -> Path:
    """Write a compact, standards-based XML companion to the JSON result."""
    selected = list(rows) if rows is not None else _first_rows(payload)
    root = ET.Element("share_scan_report", version="1")
    metadata = ET.SubElement(root, "metadata")
    ET.SubElement(metadata, "generated_at_utc").text = _scalar(
        payload.get("generated_at_utc") or payload.get("generated_at")
        or datetime.now(timezone.utc).isoformat()
    )
    ET.SubElement(metadata, "mode").text = _scalar(payload.get("mode"))
    ET.SubElement(metadata, "result_count").text = str(len(selected))

    counts = ET.SubElement(root, "counts")
    raw_counts = payload.get("counts") if isinstance(payload.get("counts"), dict) else {}
    for key, value in raw_counts.items():
        item = ET.SubElement(counts, "count", name=str(key))
        item.text = _scalar(value)

    errors = ET.SubElement(root, "errors")
    raw_errors = payload.get("errors") if isinstance(payload.get("errors"), dict) else {}
    for group, values in raw_errors.items():
        if not values:
            continue
        node = ET.SubElement(errors, "group", name=str(group))
        if isinstance(values, dict):
            for key, value in values.items():
                ET.SubElement(node, "error", key=str(key)).text = _scalar(value)
        else:
            ET.SubElement(node, "error").text = _scalar(values)

    results = ET.SubElement(root, "results")
    for position, row in enumerate(selected, 1):
        stock = ET.SubElement(
            results,
            "stock",
            rank=_scalar(row.get("rank") or row.get("volume_rank") or position),
            symbol=_scalar(row.get("symbol")),
        )
        for key, value in row.items():
            if key in {"ghost_details", "cr360_evidence", "cr360_sections", "cr360_metadata"}:
                continue
            field = ET.SubElement(stock, "field", name=str(key))
            field.text = _scalar(value)

    destination = Path(output_path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    tree = ET.ElementTree(root)
    ET.indent(tree, space="  ")
    tree.write(destination, encoding="utf-8", xml_declaration=True)
    return destination


def write_scan_bundle(
    output_dir: str | Path,
    payload: dict[str, Any],
    *,
    rows: Iterable[dict[str, Any]] | None = None,
    title: str = "Share_scan परिणाम",
) -> dict[str, Path]:
    """Create an offline dashboard and XML report beside scan artifacts."""
    destination = Path(output_dir)
    destination.mkdir(parents=True, exist_ok=True)
    selected = list(rows) if rows is not None else _first_rows(payload)
    dashboard_payload = {
        "title": title,
        "generated_at": payload.get("generated_at_utc") or payload.get("generated_at"),
        "mode": payload.get("mode"),
        "counts": payload.get("counts", {}),
        "errors": payload.get("errors", {}),
        "market_cap_stats": payload.get("market_cap_stats", {}),
        "execution": payload.get("execution", {}),
        "rows": selected,
    }

    for name in ("dashboard.html", "dashboard.css", "dashboard.js"):
        source = DASHBOARD_SOURCE / name
        if not source.is_file():
            raise FileNotFoundError(f"dashboard asset missing: {source}")
        shutil.copy2(source, destination / name)

    data_path = destination / "dashboard_data.js"
    data_path.write_text(
        "window.SHARE_SCAN_DATA = "
        + json.dumps(dashboard_payload, ensure_ascii=False, default=str, separators=(",", ":"))
        + ";\n",
        encoding="utf-8",
    )
    xml_path = write_xml_report(destination / "scan_report.xml", payload, rows=selected)
    return {
        "dashboard": destination / "dashboard.html",
        "data": data_path,
        "xml": xml_path,
    }
