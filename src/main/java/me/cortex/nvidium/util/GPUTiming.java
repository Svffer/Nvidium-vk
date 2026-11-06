package me.cortex.nvidium.util;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.ITimestampQuerySet;

public class GPUTiming {
    private int index = 0;
    private int count = 0;
    private long total = 0;
    private final long[] samples = new long[100];
    private final ITimestampQuerySet timingSet;

    public GPUTiming(IRenderBackend backend) {
        this.timingSet = backend.createTimestampQuerySet();
    }

    public void marker() {
        this.timingSet.capture(0);
    }

    private void addSample(long timeNs) {
        total -= samples[index];
        samples[index] = timeNs;
        total += timeNs;

        index = (index + 1) % samples.length;
        if (count < samples.length) {
            count++;
        }
    }

    public double getAverageMs() {
        return count == 0 ? 0 : (total / count) / 1_000_000.0;
    }

    public void tick() {
        this.timingSet.download((meta, data) -> {
            long current = data[0];
            for (int i = 1; i < meta.length; i++) {
                long next = data[i];
                long delta = next - current;
                this.addSample(delta);
                current = next;
            }
        });
        this.timingSet.tick();
    }

    public void free() {
        this.timingSet.free();
    }
}
