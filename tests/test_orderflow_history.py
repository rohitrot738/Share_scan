import json
import tempfile
import unittest
from pathlib import Path
from orderflow.decoder import decode_record, decode_file
from orderflow.history import replay

class DecoderTests(unittest.TestCase):
    def row(self):
        return {"symbol":"abc","timestamp":"2026-01-01T09:15:01+05:30","price":"100.05","quantity":"25","aggressor":"buy","trade_id":"T1"}

    def test_normalized_record(self):
        trade = decode_record(self.row())
        self.assertEqual(trade.symbol, "ABC")
        self.assertEqual(trade.quantity, 25)
        self.assertEqual(trade.aggressor, "buy")

    def test_rejects_missing_timezone(self):
        row = self.row(); row["timestamp"] = "2026-01-01T09:15:01"
        with self.assertRaisesRegex(ValueError, "timezone"): decode_record(row)

    def test_rejects_unknown_aggressor(self):
        row = self.row(); row["aggressor"] = "unknown"
        with self.assertRaisesRegex(ValueError, "explicitly"): decode_record(row)

    def test_replay_json(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "trades.json"
            p.write_text(json.dumps({"trades":[self.row(), {**self.row(),"trade_id":"T2","aggressor":"sell","quantity":"10"}]}))
            bars = replay(p, tick_size="0.05", bar_seconds=60)
            self.assertEqual(len(bars), 1)
            self.assertEqual(bars[0]["total_volume"], 35)
            self.assertEqual(bars[0]["delta"], 15)

    def test_rejects_unsupported_file(self):
        with self.assertRaisesRegex(ValueError, "supported formats"):
            decode_file("trades.xlsx")

if __name__ == "__main__": unittest.main()
