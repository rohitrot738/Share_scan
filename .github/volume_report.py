"""Temporary: share of the final daily volume done by each sample time, median across stocks."""
import json, statistics, sys
rows = [json.loads(l) for l in open(sys.argv[1])]
final = {r["sym"]: r["bar"] for r in rows if r["label"] == sys.argv[2] and r["bar"]}
labels = []
for r in rows:
    if r["label"] not in labels: labels.append(r["label"])
for label in labels:
    shares = sorted(r["bar"] / final[r["sym"]] for r in rows
                    if r["label"] == label and r["bar"] and final.get(r["sym"]))
    metas = sorted(r["meta"] / final[r["sym"]] for r in rows
                   if r["label"] == label and r["meta"] and final.get(r["sym"]))
    if shares:
        q = lambda s, p: s[min(len(s) - 1, int(p * len(s)))]
        print(f"{label}: n {len(shares)} bar median {statistics.median(shares):.3f} p25 {q(shares, .25):.3f} p75 {q(shares, .75):.3f}"
              f" | meta median {statistics.median(metas) if metas else float('nan'):.3f}")
