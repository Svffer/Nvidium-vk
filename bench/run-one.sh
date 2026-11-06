#!/usr/bin/env bash
# Runs a single benchmark configuration and appends one line to run/bench-results.jsonl.
# Usage: bench/run-one.sh <label> <opengl|vulkan> <renderDistance> [extra gradle props...]
#   e.g. bench/run-one.sh gl+nvidium opengl 32
#        bench/run-one.sh gl+sodium  opengl 32 -PnvidiumDisable=true
# Env: WARMUP (default 30), DURATION (60), POS (camera "x,y,z,yaw,pitch"), BENCH_WS (Hyprland workspace, 5)
set -euo pipefail
cd "$(dirname "$0")/.."
OPTS=run/options.txt
WARMUP=${WARMUP:-30}
DURATION=${DURATION:-60}
POS=${POS:-29,110,-45,45,20}
label=$1 backend=$2 rd=$3; shift 3

set_opt() { # key value
  if grep -q "^$1:" "$OPTS"; then sed -i "s|^$1:.*|$1:$2|" "$OPTS"; else echo "$1:$2" >> "$OPTS"; fi
}
set_opt preferredGraphicsBackend "\"$backend\""
set_opt renderDistance "$rd"
set_opt fullscreen false     # fullscreen on Wayland forces vsync (FIFO); use a floating 1600x900 window instead
set_opt enableVsync false
set_opt maxFps 260
set_opt pauseOnLostFocus false          # window lives on another workspace: never open the (blurred) pause menu
set_opt inactivityFpsLimit '"minimized"' # only throttle when actually minimized, never when merely unfocused

mkdir -p run/logs
echo "=== $label rd=$rd backend=$backend $*"
for attempt in 1 2 3; do
  before=$(wc -l < run/bench-results.jsonl 2>/dev/null || echo 0)
  bench/park-window.sh "${BENCH_WS:-5}" &
  parker=$!
  ./gradlew runClient --no-daemon -q -PbenchLabel="$label" -PbenchWarmup="$WARMUP" -PbenchDuration="$DURATION" -PbenchPos="$POS" "$@" \
     > "run/logs/bench-$label-rd$rd.out" 2>&1 || echo "    (gradle exit $? — see run/logs/bench-$label-rd$rd.out)"
  wait "$parker" 2>/dev/null || true
  if [ "$(wc -l < run/bench-results.jsonl 2>/dev/null || echo 0)" -gt "$before" ]; then break; fi
  echo "    no result written (attempt $attempt) — retrying"
done
tail -1 run/bench-results.jsonl
