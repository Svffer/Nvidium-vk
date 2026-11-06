"""Summarise run/bench-results.jsonl as a table: last result per (render distance, label) wins."""

import collections
import json
import sys

with open(sys.argv[1]) as f:
    rows = [json.loads(line) for line in f if line.strip()]
rows = [r for r in rows if not any(x in r["label"] for x in ("pregen", "smoke"))]

by = collections.OrderedDict()
for r in rows:  # last result per (rd,label) wins
    by[(r["renderDistance"], r["label"])] = r

print(
    f"{'rd':>3} {'config':<12} {'avgFps':>7} {'1%low':>7} {'p50ms':>7} {'p99ms':>7} "
    f"{'stdMs':>6} {'frames':>6} {'chunks':>6} {'warm':>4}"
)
for (rd, label), r in sorted(by.items()):
    print(
        f"{rd:>3} {label:<12} {r['avgFps']:>7.1f} {r['low1pctFps']:>7.1f} {r['p50Ms']:>7.2f} {r['p99Ms']:>7.2f} "
        f"{r['stddevMs']:>6.2f} {r['frames']:>6} {r.get('chunks', 0):>6} {r.get('warmupS', 0):>4}"
    )
