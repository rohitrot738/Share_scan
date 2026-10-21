"""Reads a Screener.in company page: quarterly results, yearly balance sheet and cash flows, quarterly shareholding
and the headline ratios. Standard library only, so the cache builder runs without pandas."""
from __future__ import annotations

import re
from datetime import date
from html.parser import HTMLParser

MONTHS = {m: i for i, m in enumerate(("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"), 1)}


class _Tables(HTMLParser):
    """Collects, per section id, every table as (header cells, rows of cells) and the page's title and ratios."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.section = None
        self.div_ids: list[str | None] = []
        self.tables: dict[str, list[tuple[list[str], list[list[str]], str | None]]] = {}
        self.table = None
        self.row = None
        self.cell = None
        self.in_head = False
        self.title = ""
        self.in_h1 = False
        self.in_ratios = False
        self.ratio_name = None
        self.ratio_value = None
        self.ratios: dict[str, str] = {}
        self.span_class = []
        self._pending = None

    def _shp_div(self):
        return next((d for d in reversed(self.div_ids) if d in ("quarterly-shp", "yearly-shp")), None)

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "section" and a.get("id"):
            self.section = a["id"]
        elif tag == "div":
            self.div_ids.append(a.get("id"))
        elif tag == "h1":
            self.in_h1 = True
        elif tag == "ul" and a.get("id") == "top-ratios":
            self.in_ratios = True
        elif tag == "span" and self.in_ratios:
            cls = a.get("class", "")
            self.span_class.append(cls)
            if "name" in cls.split():
                self.ratio_name = ""
            elif "value" in cls.split():
                self.ratio_value = ""
        elif tag == "table" and self.section:
            self.table = ([], [], self._shp_div())
        elif tag == "thead":
            self.in_head = True
        elif tag == "tr" and self.table is not None:
            self.row = []
        elif tag in ("td", "th") and self.row is not None:
            self.cell = ""

    def handle_endtag(self, tag):
        if tag == "section":
            self.section = None
        elif tag == "div" and self.div_ids:
            self.div_ids.pop()
        elif tag == "h1":
            self.in_h1 = False
        elif tag == "ul" and self.in_ratios:
            self.in_ratios = False
        elif tag == "span" and self.in_ratios and self.span_class:
            cls = self.span_class.pop().split()
            if "name" in cls and self.ratio_name is not None:
                self._pending = _clean(self.ratio_name)
                self.ratio_name = None
            elif "value" in cls and self.ratio_value is not None:
                name = self._pending
                if name:
                    self.ratios[name] = _clean(self.ratio_value)
                self.ratio_value = None
        elif tag in ("td", "th") and self.cell is not None and self.row is not None:
            self.row.append(_clean(self.cell))
            self.cell = None
        elif tag == "tr" and self.row is not None and self.table is not None:
            if self.in_head:
                self.table[0].extend(self.row)
            else:
                self.table[1].append(self.row)
            self.row = None
        elif tag == "thead":
            self.in_head = False
        elif tag == "table" and self.table is not None:
            self.tables.setdefault(self.section, []).append(self.table)
            self.table = None

    def handle_data(self, data):
        if self.cell is not None:
            self.cell += data
        if self.in_h1:
            self.title += data
        if self.ratio_name is not None:
            self.ratio_name += data
        if self.ratio_value is not None:
            self.ratio_value += data


def _clean(s: str) -> str:
    return re.sub(r"\s+", " ", s.replace("\xa0", " ")).replace(" +", "").strip()


def number(s) -> float | None:
    """'2,31,886' -> 231886.0, '50.33%' -> 50.33, '' -> None."""
    if s is None:
        return None
    t = str(s).replace(",", "").replace("%", "").replace("₹", "").replace("Cr.", "").strip()
    m = re.search(r"-?\d+(?:\.\d+)?", t)
    return float(m.group(0)) if m else None


def period_end(label: str) -> date | None:
    """'Sep 2023' -> 2023-09-30; 'Mar 2024' -> 2024-03-31; anything else (TTM, '') -> None."""
    m = re.match(r"([A-Z][a-z]{2})\s+(\d{4})$", label.strip())
    if not m or m.group(1) not in MONTHS:
        return None
    y, mo = int(m.group(2)), MONTHS[m.group(1)]
    nxt = date(y + (mo == 12), mo % 12 + 1, 1)
    return date.fromordinal(nxt.toordinal() - 1)


def _rows(table) -> tuple[list[str], dict[str, list[str]]]:
    head, body, _ = table
    rows = {}
    for r in body:
        if r and r[0] and r[0] not in rows:
            rows[r[0]] = r[1:]
    return head[1:], rows


def _pick(rows: dict[str, list[str]], *names: str) -> list[str] | None:
    for n in names:
        if n in rows:
            return rows[n]
    return None


def _series(periods, rows, mapping):
    """One dict per dated period with the mapped rows' numbers; undated columns (TTM) are dropped."""
    out = []
    for i, label in enumerate(periods):
        end = period_end(label)
        if end is None:
            continue
        rec = {"period": end.isoformat()}
        for key, names in mapping.items():
            vals = _pick(rows, *names)
            rec[key] = number(vals[i]) if vals and i < len(vals) else None
        out.append(rec)
    return out


def parse(html: str) -> dict:
    """Everything the 360CR cache keeps from one page; lists are oldest first and empty when the page lacks them."""
    p = _Tables()
    p.feed(html)

    def first_table(section, div=None):
        for t in p.tables.get(section, []):
            if div is None or t[2] == div:
                return t
        return None

    out = {"name": _clean(p.title), "quarters": [], "annual": [], "balance": [], "cashflow": [], "shareholding": [], "ratios": {}}
    q = first_table("quarters")
    if q:
        periods, rows = _rows(q)
        out["quarters"] = _series(periods, rows, {
            "revenue": ("Sales", "Revenue"),
            "ebitda": ("Operating Profit", "Financing Profit"),
            "net_profit": ("Net Profit",),
            "eps": ("EPS in Rs",),
        })
    pl = first_table("profit-loss")
    if pl:
        periods, rows = _rows(pl)
        out["annual"] = _series(periods, rows, {"revenue": ("Sales", "Revenue"), "net_profit": ("Net Profit",), "eps": ("EPS in Rs",)})
    bs = first_table("balance-sheet")
    if bs:
        periods, rows = _rows(bs)
        out["balance"] = [
            {"period": r["period"], "total_debt": r["borrowings"],
             "equity": (r["capital"] or 0) + (r["reserves"] or 0) if r["capital"] is not None or r["reserves"] is not None else None}
            for r in _series(periods, rows, {"capital": ("Equity Capital",), "reserves": ("Reserves",), "borrowings": ("Borrowings", "Borrowing")})
        ]
    cf = first_table("cash-flow")
    if cf:
        periods, rows = _rows(cf)
        out["cashflow"] = _series(periods, rows, {"operating_cash_flow": ("Cash from Operating Activity",),
                                                   "free_cash_flow": ("Free Cash Flow",)})
    sh = first_table("shareholding", "quarterly-shp") or first_table("shareholding")
    if sh:
        periods, rows = _rows(sh)
        out["shareholding"] = _series(periods, rows, {"promoter": ("Promoters",), "fii_fpi": ("FIIs",), "dii": ("DIIs",),
                                                       "government": ("Government",), "public": ("Public",)})
    r = p.ratios
    out["ratios"] = {
        "market_cap_cr": number(r.get("Market Cap")),
        "price": number(r.get("Current Price")),
        "pe": number(r.get("Stock P/E")),
        "book_value": number(r.get("Book Value")),
        "dividend_yield": number(r.get("Dividend Yield")),
        "roce": number(r.get("ROCE")),
        "roe": number(r.get("ROE")),
    }
    # The annual net profit next to each year's cash flow lets 360CR compare like with like.
    profit = {a["period"]: a["net_profit"] for a in out["annual"]}
    for c in out["cashflow"]:
        c["net_profit"] = profit.get(c["period"])
    return out
