#!/usr/bin/env bash
# M0 benchmark matrix: OpenGL+Sodium / OpenGL+Nvidium / Vulkan+Sodium at several render distances.
# Usage: bench/run-matrix.sh [distances...]   (default: 16 24 32; vanilla max is 32)
# Results: run/bench-results.jsonl ; summary printed by bench/summary.py
set -euo pipefail
cd "$(dirname "$0")/.."
OPTS=run/options.txt
DISTANCES=("${@:-16 32 64}")
[ $# -eq 0 ] && DISTANCES=(16 24 32)
WARMUP=${WARMUP:-30}          # minimum warm-up; harness also waits for chunk count to settle
DURATION=${DURATION:-60}
POS=${POS:-29,110,-45,45,20}  # fixed camera: x,y,z,yaw,pitch (spawn of "New World")
MAXRD=0; for d in "${DISTANCES[@]}"; do (( d > MAXRD )) && MAXRD=$d; done

set_opt() { # key value
  if grep -q "^$1:" "$OPTS"; then sed -i "s|^$1:.*|$1:$2|" "$OPTS"; else echo "$1:$2" >> "$OPTS"; fi
}

run_one() { # label backend renderDistance [extra gradle props]
  local label=$1 backend=$2 rd=$3; shift 3
  set_opt preferredGraphicsBackend "\"$backend\""
  set_opt renderDistance "$rd"
  set_opt fullscreen false     # fullscreen on Wayland forces vsync (FIFO); use a floating 1600x900 window instead
  set_opt enableVsync false
  set_opt maxFps 260
  set_opt pauseOnLostFocus false          # window lives on another workspace: never open the (blurred) pause menu
  set_opt inactivityFpsLimit '"minimized"' # only throttle when actually minimized, never when merely unfocused
  echo "=== $label rd=$rd backend=$backend $*"
  local attempt before
  for attempt in 1 2 3; do
    before=$(wc -l < run/bench-results.jsonl 2>/dev/null || echo 0)
    bench/park-window.sh "${BENCH_WS:-5}" &
    local parker=$!
  ./gradlew runClient --no-daemon -q -PbenchLabel="$label" -PbenchWarmup="$WARMUP" -PbenchDuration="$DURATION" -PbenchPos="$POS" "$@" \
       > "run/logs/bench-$label-rd$rd.out" 2>&1 || echo "    (gradle exit $? — see run/logs/bench-$label-rd$rd.out)"
    wait "$parker" 2>/dev/null || true
    if [ "$(wc -l < run/bench-results.jsonl 2>/dev/null || echo 0)" -gt "$before" ]; then break; fi
    echo "    no result written (attempt $attempt) — retrying"
  done
  tail -1 run/bench-results.jsonl
}

# One-time pre-generation so later runs only load chunks from disk (results line is ignored by summary).
run_one pregen vulkan "$MAXRD" -PbenchDuration=3

for rd in "${DISTANCES[@]}"; do
  run_one gl+sodium  opengl "$rd" -PnvidiumDisable=true
  run_one gl+nvidium opengl "$rd"
  run_one vk+sodium  vulkan "$rd"
done
python3 -I bench/summary.py run/bench-results.jsonl
