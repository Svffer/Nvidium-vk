package me.cortex.nvidium.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.nvidium.Nvidium;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * Dev-only, fully automatic frame-time benchmark (plan milestone M0).
 *
 * Enabled with {@code -Dnvidium.bench=true}. Expects the game to be launched with
 * {@code --quickPlaySingleplayer <world>} so it lands directly in a world.
 *
 * Sequence: wait for the level → put the player in spectator at a fixed position/angle
 * (re-applied every second during warm-up so mouse drift cannot change the view) →
 * warm-up → measure frame times → append a JSON line to {@code bench-results.jsonl}
 * in the game directory → exit.
 *
 * Properties:
 *   nvidium.bench.label     free text identifying the configuration
 *   nvidium.bench.warmup    minimum seconds before measuring (default 45); measuring also
 *                           waits until the loaded-chunk count has been stable for 5 s
 *   nvidium.bench.maxWarmup upper bound for the warm-up in seconds (default 600)
 *   nvidium.bench.duration  seconds to measure (default 60)
 *   nvidium.bench.pos       "x,y,z,yaw,pitch" — default: spawn XZ, y=110, yaw 45, pitch 20
 */
public final class BenchHarness {
    private static final long NS = 1_000_000_000L;

    private enum State { WAIT_LEVEL, WARMUP, MEASURE, DONE }

    private State state = State.WAIT_LEVEL;
    private long stateStartNs;
    private long lastTeleportNs;
    private long lastFrameNs;

    private final String label = System.getProperty("nvidium.bench.label", "unlabeled");
    private final long warmupNs = Long.getLong("nvidium.bench.warmup", 45) * NS;
    private final long maxWarmupNs = Long.getLong("nvidium.bench.maxWarmup", 600) * NS;
    private final long durationNs = Long.getLong("nvidium.bench.duration", 60) * NS;
    private static final long STABLE_NS = 5 * NS;
    private int lastChunkCount = -1;
    private long chunkCountChangedNs;
    private String position; // "x y z yaw pitch", resolved on first level tick
    private long warmupTookNs;

    private long[] frameNs = new long[1 << 16];
    private int frameCount;

    public static void install() {
        var h = new BenchHarness();
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, h::onTick);
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, h::onFrame);
        Nvidium.LOGGER.info("[bench] harness installed, label='{}' warmup={}s duration={}s",
                h.label, h.warmupNs / NS, h.durationNs / NS);
    }

    private void onTick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        long now = System.nanoTime();
        switch (state) {
            case WAIT_LEVEL -> {
                if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
                if (position == null) {
                    position = System.getProperty("nvidium.bench.pos", null);
                    if (position != null) {
                        position = position.replace(',', ' ');
                    } else {
                        var p = mc.player.blockPosition();
                        position = p.getX() + " 110 " + p.getZ() + " 45 20";
                    }
                }
                runCommands(mc.getSingleplayerServer(),
                        "gamemode spectator @a",
                        "time set noon",
                        "weather clear",
                        "gamerule doDaylightCycle false",
                        "gamerule doWeatherCycle false",
                        "tp @a " + position);
                lastTeleportNs = now;
                enter(State.WARMUP, now);
                Nvidium.LOGGER.info("[bench] level ready, position '{}', warming up {}s", position, warmupNs / NS);
            }
            case WARMUP -> {
                if (now - lastTeleportNs > NS) {
                    runCommands(mc.getSingleplayerServer(), "tp @a " + position);
                    lastTeleportNs = now;
                }
                int chunks = mc.level.getChunkSource().getLoadedChunksCount();
                if (chunks != lastChunkCount) {
                    lastChunkCount = chunks;
                    chunkCountChangedNs = now;
                }
                boolean minElapsed = now - stateStartNs >= warmupNs;
                boolean stable = now - chunkCountChangedNs >= STABLE_NS;
                boolean capped = now - stateStartNs >= maxWarmupNs;
                if ((minElapsed && stable) || capped) {
                    frameCount = 0;
                    lastFrameNs = 0;
                    warmupTookNs = now - stateStartNs;
                    enter(State.MEASURE, now);
                    Nvidium.LOGGER.info("[bench] measuring {}s (warm-up took {}s, {} chunks loaded{}; focused={} paused={})",
                            durationNs / NS, warmupTookNs / NS, chunks, capped ? ", HIT MAX WARMUP" : "",
                            mc.isWindowActive(), mc.isPaused());
                }
            }
            case MEASURE -> {
                if (now - stateStartNs >= durationNs) {
                    enter(State.DONE, now);
                    report(mc);
                    mc.stop();
                }
            }
            case DONE -> {}
        }
    }

    private void onFrame(RenderFrameEvent.Post e) {
        if (state != State.MEASURE) return;
        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            if (frameCount == frameNs.length) frameNs = Arrays.copyOf(frameNs, frameNs.length * 2);
            frameNs[frameCount++] = now - lastFrameNs;
        }
        lastFrameNs = now;
    }

    private void enter(State s, long now) {
        state = s;
        stateStartNs = now;
    }

    private static void runCommands(MinecraftServer server, String... commands) {
        server.execute(() -> {
            var source = server.createCommandSourceStack().withSuppressedOutput();
            for (var c : commands) server.getCommands().performPrefixedCommand(source, c);
        });
    }

    private void report(Minecraft mc) {
        long[] t = Arrays.copyOf(frameNs, frameCount);
        Arrays.sort(t);
        double total = 0;
        for (long v : t) total += v;
        double avgMs = total / t.length / 1e6;
        double avgFps = t.length / (total / NS);
        double p50 = pct(t, 0.50), p99 = pct(t, 0.99), p999 = pct(t, 0.999);
        double mean = total / t.length, var = 0;
        for (long v : t) var += (v - mean) * (v - mean);
        double stddevMs = Math.sqrt(var / t.length) / 1e6;

        var info = RenderSystem.getDevice().getDeviceInfo();
        String backend = mc.options.preferredGraphicsBackend().get().toString();
        int rd = mc.options.getEffectiveRenderDistance();

        String json = String.format(Locale.ROOT,
                "{\"label\":\"%s\",\"backend\":\"%s\",\"nvidium\":%b,\"renderDistance\":%d," +
                "\"frames\":%d,\"seconds\":%.1f,\"avgFps\":%.1f,\"avgMs\":%.3f,\"p50Ms\":%.3f," +
                "\"p99Ms\":%.3f,\"p999Ms\":%.3f,\"low1pctFps\":%.1f,\"stddevMs\":%.3f," +
                "\"gpu\":\"%s\",\"driver\":\"%s\",\"width\":%d,\"height\":%d," +
                "\"warmupS\":%d,\"chunks\":%d,\"focused\":%b,\"paused\":%b}",
                label, backend, Nvidium.IS_ENABLED, rd,
                t.length, total / NS, avgFps, avgMs, p50 / 1e6,
                p99 / 1e6, p999 / 1e6, 1e9 / p99, stddevMs,
                info.name().replace('"', '\''), info.driverInfo().replace('"', '\''),
                mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                warmupTookNs / NS, lastChunkCount,
                mc.isWindowActive(), mc.isPaused());

        Nvidium.LOGGER.info("[bench] RESULT {}", json);
        try {
            Path out = mc.gameDirectory.toPath().resolve("bench-results.jsonl");
            Files.writeString(out, json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Nvidium.LOGGER.error("[bench] failed to write results", ex);
        }
    }

    private static double pct(long[] sorted, double q) {
        int i = (int) Math.min(sorted.length - 1, Math.round(q * (sorted.length - 1)));
        return sorted[i];
    }
}
