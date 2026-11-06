package me.cortex.nvidium.backend.gl;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import me.cortex.nvidium.backend.ITimestampQuerySet;
import me.cortex.nvidium.backend.TrackedObject;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11C.GL_TRUE;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL33C.*;

/** GL timer queries ({@code GL_TIMESTAMP}) pooled and recycled across frames. */
final class GlTimestampQuerySet extends TrackedObject implements ITimestampQuerySet {
    private record InflightRequest(int[] queries, int[] meta, TimingDataConsumer callback) {
        private boolean callbackIfReady(IntArrayFIFOQueue queryPool) {
            boolean ready = glGetQueryObjecti(this.queries[this.queries.length - 1], GL_QUERY_RESULT_AVAILABLE) == GL_TRUE;
            if (!ready) {
                return false;
            }
            long[] results = new long[this.queries.length];
            for (int i = 0; i < this.queries.length; i++) {
                results[i] = glGetQueryObjecti64(this.queries[i], GL_QUERY_RESULT);
                queryPool.enqueue(this.queries[i]);
            }
            this.callback.accept(this.meta, results);
            return true;
        }
    }

    private final IntArrayFIFOQueue POOL = new IntArrayFIFOQueue();
    private final ObjectArrayFIFOQueue<InflightRequest> INFLIGHT = new ObjectArrayFIFOQueue<>();

    private final int[] queries = new int[64];
    private final int[] metadata = new int[64];
    private int index;

    @Override
    public void capture(int metadata) {
        if (this.index > this.metadata.length) {
            throw new IllegalStateException();
        }
        int slot = this.index++;
        this.metadata[slot] = metadata;
        int query = this.getQuery();
        glQueryCounter(query, GL_TIMESTAMP);
        this.queries[slot] = query;
    }

    @Override
    public void download(TimingDataConsumer consumer) {
        var queries = Arrays.copyOf(this.queries, this.index);
        var metadata = Arrays.copyOf(this.metadata, this.index);
        this.index = 0;
        this.INFLIGHT.enqueue(new InflightRequest(queries, metadata, consumer));
    }

    @Override
    public void tick() {
        while (!INFLIGHT.isEmpty()) {
            if (INFLIGHT.first().callbackIfReady(POOL)) {
                INFLIGHT.dequeue();
            } else {
                break;
            }
        }
    }

    private int getQuery() {
        if (POOL.isEmpty()) {
            return glGenQueries();
        } else {
            return POOL.dequeueInt();
        }
    }

    @Override
    public void free() {
        super.free0();
        while (!POOL.isEmpty()) {
            glDeleteQueries(POOL.dequeueInt());
        }
        while (!INFLIGHT.isEmpty()) {
            glDeleteQueries(INFLIGHT.dequeue().queries);
        }
    }
}
