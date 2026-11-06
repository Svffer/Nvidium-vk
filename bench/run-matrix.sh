#!/usr/bin/env bash
# Benchmark matrix: OpenGL+Sodium / OpenGL+Nvidium / Vulkan+Sodium / Vulkan+Nvidium at several render distances.
# Usage: bench/run-matrix.sh [distances...]   (default: 16 24 32; vanilla max is 32)
# Results: run/bench-results.jsonl ; summary printed by bench/summary.py
set -euo pipefail
cd "$(dirname "$0")/.."
DISTANCES=("${@:-16 32 64}")
[ $# -eq 0 ] && DISTANCES=(16 24 32)
WARMUP=${WARMUP:-30}          # minimum warm-up; harness also waits for chunk count to settle
DURATION=${DURATION:-60}
POS=${POS:-29,110,-45,45,20}  # fixed camera: x,y,z,yaw,pitch (spawn of "New World")
MAXRD=0; for d in "${DISTANCES[@]}"; do (( d > MAXRD )) && MAXRD=$d; done

run_one() { # label backend renderDistance [extra gradle props]
  WARMUP=$WARMUP DURATION=$DURATION POS=$POS bench/run-one.sh "$@"
}

# One-time pre-generation so later runs only load chunks from disk (results line is ignored by summary).
run_one pregen vulkan "$MAXRD" -PbenchDuration=3

for rd in "${DISTANCES[@]}"; do
  run_one gl+sodium  opengl "$rd" -PnvidiumDisable=true
  run_one gl+nvidium opengl "$rd"
  run_one vk+sodium  vulkan "$rd"
  run_one vk+nvidium vulkan "$rd"
done
python3 -I bench/summary.py run/bench-results.jsonl
