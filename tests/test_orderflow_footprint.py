import unittest
from datetime import datetime, timezone
from decimal import Decimal
from orderflow.footprint import Trade, FootprintBuilder
from orderflow.signals import analyze_footprint
from orderflow.nse_feed import NSEFeedAdapter
class FootprintTests(unittest.TestCase):
 def setUp(self): self.ts=datetime(2026,1,1,9,15,1,tzinfo=timezone.utc)
 def test_aggregation_delta_and_poc(self):
  ts=self.ts; trades=[Trade("ABC",ts,Decimal("10.00"),100,"buy","1"),Trade("ABC",ts,Decimal("10.00"),40,"sell","2"),Trade("ABC",ts,Decimal("10.05"),80,"buy","3")]; bar=FootprintBuilder("0.05").build(trades)[0]
  self.assertEqual(bar.ask_volume,180); self.assertEqual(bar.bid_volume,40); self.assertEqual(bar.delta,140); self.assertEqual(bar.poc,Decimal("10.00"))
 def test_duplicate_trade_id_ignored(self):
  t=Trade("ABC",self.ts,Decimal("10"),5,"buy","same"); self.assertEqual(FootprintBuilder("0.05").build([t,t])[0].total_volume,5)
 def test_tick_normalization(self): self.assertEqual(FootprintBuilder("0.05").normalize_price(Decimal("10.024")),Decimal("10.00"))
 def test_invalid_trade_rejected(self):
  with self.assertRaises(ValueError): Trade("ABC",self.ts,Decimal("10"),0,"buy")
 def test_no_fake_live_feed(self): self.assertIn("DATA UNAVAILABLE",NSEFeedAdapter().status().message)
 def test_signal_is_descriptive(self):
  t=Trade("ABC",self.ts,Decimal("10"),50,"buy"); result=analyze_footprint(FootprintBuilder("0.05").build([t])[0]); self.assertEqual(result["bias"],"BUY_AGGRESSION"); self.assertEqual(result["signal_status"],"DESCRIPTIVE_ONLY")
if __name__=="__main__": unittest.main()
