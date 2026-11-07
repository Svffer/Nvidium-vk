#!/usr/bin/env bash
# Heavy-scene matrix: vanilla rd 32 + Distant Horizons LODs, DH world-gen OFF during measurement.
# Usage: bench/run-dh-matrix.sh [lodRadius...]   (default: 128 256)
# Pre-generate LODs first: bench/run-dh-pregen.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OPTS=run/options.txt
DH=run/config/DistantHorizons.toml
RADII=("$@"); [ $# -eq 0 ] && RADII=(128 256)
WARMUP=${WARMUP:-40}
DURATION=${DURATION:-60}
POS=${POS:-29,110,-45,45,20}
RD=${RD:-32}

set_opt() { if grep -q "^$1:" "$OPTS"; then sed -i "s|^$1:.*|$1:$2|" "$OPTS"; else echo "$1:$2" >> "$OPTS"; fi; }
set_dh()  { sed -i "s/^\(\s*\)$1 = .*/\1$1 = $2/" "$DH"; grep -qE "^\s*$1 = $2\$" "$DH" || { echo "DH key $1 not set"; exit 1; }; }

run_one() { # label backend lodRadius [extra gradle props]
  local label=$1 backend=$2 lod=$3; shift 3
  set_opt preferredGraphicsBackend "\"$backend\""
  set_opt renderDistance "$RD"
  set_opt fullscreen false
  set_opt enableVsync false
  set_opt maxFps 260
  set_opt pauseOnLostFocus false
  set_opt inactivityFpsLimit '"minimized"'
  set_dh lodChunkRenderDistanceRadius "$lod"
  set_dh chunkGeneratorMode '"PRE_EXISTING_ONLY"'   # no LOD generation while measuring
  set_dh showGenerationProgress '"DISABLED"'
  echo "=== $label rd=$RD lod=$lod backend=$backend $*"
  local attempt before
  for attempt in 1 2 3; do
    before=$(wc -l < run/bench-results.jsonl 2>/dev/null || echo 0)
    if [ -x bench/park-window.sh ]; then bench/park-window.sh "${BENCH_WS:-5}" & else sleep 0 & fi  # optional local helper
    local parker=$!
  ./gradlew runClient --no-daemon -q -PbenchLabel="$label" -PbenchWarmup="$WARMUP" -PbenchDuration="$DURATION" -PbenchPos="$POS" "$@" \
       > "run/logs/bench-$label-lod$lod.out" 2>&1 || echo "    (gradle exit $? — see run/logs/bench-$label-lod$lod.out)"
    wait "$parker" 2>/dev/null || true
    if [ "$(wc -l < run/bench-results.jsonl 2>/dev/null || echo 0)" -gt "$before" ]; then break; fi
    echo "    no result written (attempt $attempt) — retrying"
  done
  tail -1 run/bench-results.jsonl
}

for lod in "${RADII[@]}"; do
  run_one "dh$lod+gl+sodium"  opengl "$lod" -PnvidiumDisable=true
  run_one "dh$lod+gl+nvidium" opengl "$lod"
  run_one "dh$lod+vk+sodium"  vulkan "$lod"
  run_one "dh$lod+vk+nvidium" vulkan "$lod"
done
python3 -I bench/summary.py run/bench-results.jsonl
