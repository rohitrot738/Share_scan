"""Each signal's track record: the same rules run on every session of the past year, and the price 10 sessions
after each time a signal showed, against all shares' average move over the same 10 sessions."""
from __future__ import annotations

from datetime import date

from bigmoney import rules
from bigmoney.store import history

HORIZON = 10
MIN_CASES = 30
SCORE_MARK = 60
KEYS = ("acc", "churn", "deal_buy", "deal_sell", "promoter_buy", "promoter_sell", "score60")
# Signals that stay on for weeks (the deals' 30 sessions, the promoters' 90 days, a score that holds) count once, on
# the session they first show; accumulation and churn are one session's signals and count every time.
LASTING = ("deal_buy", "deal_sell", "promoter_buy", "promoter_sell", "score60")


def signals_at(store: dict, insider: dict[str, list], symbol: str, i: int) -> tuple[list[str], int, list[float]]:
    """The share's flags and score at session index `i`, from what was known that evening."""
    days = history(store, symbol, i)
    r = rules.ratios(days)
    accumulated, churned = rules.accumulation(days), rules.churn(days)
    sessions = store["dates"][max(0, i - rules.DEAL_SESSIONS + 1):i + 1]
    deal = rules.deal_net(store["deals"].get(symbol, []), sessions)
    promoter = rules.promoter_net(insider.get(symbol, []), date.fromisoformat(store["dates"][i]))
    volumes = [d.volume for d in days[:-1] if d.volume is not None]
    avg_volume = sum(volumes) / len(volumes) if len(volumes) >= rules.MIN_SESSIONS else None
    total, parts = rules.score(r, deal, promoter, churned, avg_volume)
    return rules.flags(accumulated, churned, deal, promoter), total, parts


def track_record(store: dict, insider: dict[str, list], horizon: int = HORIZON,
                 min_cases: int = MIN_CASES) -> dict[str, dict]:
    """{key: {"n", "avg", "base", "up"}}: how many times each signal showed, the average move 10 sessions later in
    %, all shares' average move over the same sessions, and the % of times the price was higher. The figures are
    None under `min_cases` cases."""
    cases: dict[str, list[tuple[float, float]]] = {k: [] for k in KEYS}
    stocks = store["stocks"]
    on_before: dict[str, set[str]] = {}  # each share's lasting signals the session before
    for i in range(rules.AVG_SESSIONS, len(store["dates"]) - horizon):
        moves = {}
        for symbol, v in stocks.items():
            now, later = v["c"][i], v["c"][i + horizon]
            if now and later:
                moves[symbol] = (later / now - 1) * 100
        if not moves:
            continue
        base = sum(moves.values()) / len(moves)
        for symbol, move in moves.items():
            found, total, _ = signals_at(store, insider, symbol, i)
            if total >= SCORE_MARK:
                found = found + ["score60"]
            before = on_before.get(symbol, set())
            on_before[symbol] = {k for k in found if k in LASTING}
            for key in found:
                if key in LASTING and key in before:
                    continue
                cases[key].append((move, base))
    out = {}
    for key, got in cases.items():
        n = len(got)
        if n < min_cases or n == 0:
            out[key] = {"n": n, "avg": None, "base": None, "up": None}
        else:
            out[key] = {"n": n, "avg": round(sum(m for m, _ in got) / n, 2), "base": round(sum(b for _, b in got) / n, 2),
                        "up": round(100 * sum(1 for m, _ in got if m > 0) / n, 2)}
    return out
