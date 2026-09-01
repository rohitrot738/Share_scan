@@
 def analyse_symbol(inst: Instrument, frames: Dict[str, pd.DataFrame], stage1: dict) -> dict:
-    if "1d" not in frames:
-        raise ValueError("1d frame unavailable")
-    if len(frames) < 2:
-        raise ValueError("not enough Ghost timeframes")
+    # Graceful handling when required frames are missing: return an annotated result
+    # instead of raising exceptions so the full market scan can continue.
+    if "1d" not in frames:
+        return {**stage1, "symbol": inst.symbol, "exchange": inst.exchange, "ghost_status": "MISSING_FRAMES", "error": "1d frame unavailable"}
+    if len(frames) < 2:
+        return {**stage1, "symbol": inst.symbol, "exchange": inst.exchange, "ghost_status": "MISSING_FRAMES", "error": "not enough Ghost timeframes"}
@@
     for i, row in enumerate(rows, 1):
         row["rank"] = i
     return rows, shortlist_rows, errors, feed_errors
