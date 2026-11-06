package me.cortex.nvidium.backend;

/**
 * A pool of GPU timestamp queries. {@link #capture(int)} records a timestamp at the current point
 * of the command stream; {@link #download(TimingDataConsumer)} closes the current batch and
 * {@link #tick()} delivers finished batches, oldest first, in submission order.
 */
public interface ITimestampQuerySet {
    interface TimingDataConsumer {
        void accept(int[] metadata, long[] timingsNs);
    }

    void capture(int metadata);

    void download(TimingDataConsumer consumer);

    void tick();

    void free();
}
