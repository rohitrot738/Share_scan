from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import yfinance as yf

from false_breakout_filter import false_breakout_risk

DEFAULT_SYMBOLS = [
    "RELIANCE", "HDFCBANK", "ICICIBANK", "SBIN", "INFY",
    "TCS", "ITC", "BHARTIARTL", "LT", "AXISBANK",
    "TATASTEEL", "HINDALCO", "PFC", "RECLTD", "CANBK",
    "BEL", "HAL", "IRFC", "IREDA", "RVNL",
]


def clean_frame(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    out = df.copy()
    out.columns = [str(c).lower() for c in out.columns]
    need = {"open", "high", "low", "close", "volume"}
    if not need.issubset(out.columns):
        return pd.DataFrame()
    return out[["open", "high", "low", "close", "volume"]].dropna(subset=["close"])


def baseline_metrics(df: pd.DataFrame) -> dict | None:
    if len(df) < 25:
        return None
    close = df["close"].astype(float)
    volume = df["volume"].astype(float).fillna(0)
    price = float(close.iloc[-1])
    prev = float(close.iloc[-2])
    avg20 = float(volume.iloc[-21:-1].mean())
    vol = float(volume.iloc[-1])
    rvol = vol / avg20 if avg20 > 0 else 0.0
    change = ((price / prev) - 1.0) * 100 if prev > 0 else 0.0
    high20 = float(df["high"].astype(float).iloc[-21:-1].max())
    distance_high = ((high20 - price) / high20) * 100 if high20 > 0 else 999.0

    score = 0.0
    score += min(max(rvol, 0.0), 4.0) * 20.0
    score += max(min(change + 2.0, 6.0), 0.0) * 5.0
    score += max(20.0 - max(distance_high, 0.0) * 3.0, 0.0)

    return {
        "price": round(price, 2),
        "volume": int(vol),
        "avg_volume20": int(avg20),
        "rvol": round(rvol, 2),
        "change_pct": round(change, 2),
        "distance_to_20d_high_pct": round(distance_high, 2),
        "baseline_score": round(score, 2),
    }


def add_false_breakout_stage(df: pd.DataFrame, row: dict) -> dict:
    """Stage 1: add only false-breakout risk. No other old engine is enabled yet."""
    fb = false_breakout_risk(df, window=20)
    risk = float(fb.get("risk", 100.0))
    row.update({
        "false_breakout_risk": round(risk, 2),
        "failed_up_breakout": bool(fb.get("failed_up_breakout", False)),
        "failed_down_breakout": bool(fb.get("failed_down_breakout", False)),
        "upper_wick_ratio": fb.get("upper_wick_ratio"),
        "stage1_status": "OK",
        "score": round(max(0.0, row["baseline_score"] - 0.15 * risk), 2),
    })
    return row


def scan(symbols: list[str]) -> tuple[list[dict], dict]:
    tickers = [f"{s}.NS" for s in symbols]
    raw = yf.download(
        tickers=" ".join(tickers),
        period="3mo",
        interval="1d",
        group_by="ticker",
        auto_adjust=False,
        threads=True,
        progress=False,
        timeout=30,
    )

    rows = []
    errors = {}
    for symbol, ticker in zip(symbols, tickers):
        try:
            part = raw[ticker] if len(tickers) > 1 else raw
            df = clean_frame(part)
            metrics = baseline_metrics(df)
            if not metrics:
                errors[symbol] = "baseline: insufficient data"
                continue

            row = {"symbol": symbol, "exchange": "NSE", **metrics}
            try:
                row = add_false_breakout_stage(df, row)
            except Exception as exc:
                # Stage failure is isolated: baseline result remains usable.
                row.update({
                    "false_breakout_risk": None,
                    "failed_up_breakout": None,
                    "failed_down_breakout": None,
                    "upper_wick_ratio": None,
                    "stage1_status": f"ERROR: {type(exc).__name__}: {exc}",
                    "score": row["baseline_score"],
                })
                errors[f"{symbol}:stage1"] = row["stage1_status"]
            rows.append(row)
        except Exception as exc:
            errors[symbol] = f"baseline: {type(exc).__name__}: {exc}"

    rows.sort(key=lambda x: (x["score"], x["volume"]), reverse=True)
    for i, row in enumerate(rows, 1):
        row["rank"] = i
    return rows, errors


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=20)
    ap.add_argument("--output-dir", default="scan_results")
    args = ap.parse_args()

    limit = max(1, min(args.limit, len(DEFAULT_SYMBOLS)))
    symbols = DEFAULT_SYMBOLS[:limit]
    rows, errors = scan(symbols)

    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    stage1_ok = sum(1 for r in rows if r.get("stage1_status") == "OK")
    payload = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "mode": "NSE_BASELINE_PLUS_STAGE1_FALSE_BREAKOUT",
        "requested": len(symbols),
        "successful_baseline": len(rows),
        "successful_stage1": stage1_ok,
        "errors": errors,
        "ranked": rows,
    }
    (out / "latest.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    pd.DataFrame(rows).to_csv(out / "latest.csv", index=False)

    print(f"BASELINE complete: {len(rows)}/{len(symbols)}")
    print(f"STAGE1 false-breakout complete: {stage1_ok}/{len(rows)}")
    for row in rows[:10]:
        print(
            f"#{row['rank']} {row['symbol']} score={row['score']} "
            f"base={row['baseline_score']} false={row['false_breakout_risk']} "
            f"stage1={row['stage1_status']}"
        )


if __name__ == "__main__":
    main()
