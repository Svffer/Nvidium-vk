package me.cortex.nvidium.backend.vk;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import me.cortex.nvidium.backend.ITimestampQuerySet;
import me.cortex.nvidium.backend.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * GPU timestamps on Vulkan. One batch ({@link #capture} … {@link #download}) lives in one small
 * {@code VK_QUERY_TYPE_TIMESTAMP} pool; the pools form a ring. A pool is host-reset as a whole
 * ({@code hostQueryReset} is a feature Mojang requires) only when it is taken for a new batch, and it is only
 * taken once its previous batch has been read back and the submit that wrote it has completed, so no
 * command buffer that references the pool is still pending (the validation layer checks that per pool, not
 * per query range). Results are read without blocking through {@code VK_QUERY_RESULT_WITH_AVAILABILITY_BIT};
 * ticks are converted to nanoseconds with the device's {@code timestampPeriod}. If every pool of the ring is
 * still in flight, captures are dropped, which only shortens the batch the consumer sees.
 */
final class VkTimestampQuerySet extends TrackedObject implements ITimestampQuerySet {
    private static final int POOLS = 8;
    private static final int QUERIES_PER_POOL = 64;

    private record Batch(int pool, int[] metadata, long submitIndex, TimingDataConsumer consumer) {}

    private final VkContext ctx;
    private final long[] pools = new long[POOLS];
    private final boolean[] poolBusy = new boolean[POOLS];
    private final double nsPerTick;

    private int current = -1; // pool of the open batch, -1 until the first capture
    private int count; // queries written in the open batch
    private final int[] metadata = new int[QUERIES_PER_POOL];
    private final ArrayDeque<Batch> inflight = new ArrayDeque<>();

    VkTimestampQuerySet(VkContext ctx, double nsPerTick) {
        this.ctx = ctx;
        this.nsPerTick = nsPerTick;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queryType(VK12.VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(QUERIES_PER_POOL);
            LongBuffer pPool = stack.callocLong(1);
            for (int i = 0; i < POOLS; i++) {
                VkAllocator.check(VK12.vkCreateQueryPool(ctx.vkDevice(), info, null, pPool), "vkCreateQueryPool");
                pools[i] = pPool.get(0);
                VK12.vkResetQueryPool(ctx.vkDevice(), pools[i], 0, QUERIES_PER_POOL);
            }
        }
    }

    /** Takes a free pool for a new batch and resets it; returns false if the whole ring is in flight. */
    private boolean openBatch() {
        for (int i = 0; i < POOLS; i++) {
            if (!poolBusy[i]) {
                poolBusy[i] = true;
                current = i;
                count = 0;
                VK12.vkResetQueryPool(ctx.vkDevice(), pools[i], 0, QUERIES_PER_POOL);
                return true;
            }
        }
        return false;
    }

    @Override
    public void capture(int meta) {
        assertNotFreed();
        if (current < 0 && !openBatch()) {
            return; // ring exhausted; dropped
        }
        if (count == QUERIES_PER_POOL) {
            return; // batch full; dropped
        }
        VkCommandBuffer cb = ctx.commandBuffer();
        KHRSynchronization2.vkCmdWriteTimestamp2KHR(
                cb, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pools[current], count);
        metadata[count] = meta;
        count++;
    }

    @Override
    public void download(TimingDataConsumer consumer) {
        assertNotFreed();
        if (current < 0) {
            consumer.accept(new int[0], new long[0]);
            return;
        }
        inflight.addLast(new Batch(current, Arrays.copyOf(metadata, count), ctx.currentSubmitIndex(), consumer));
        current = -1;
        count = 0;
    }

    @Override
    public void tick() {
        assertNotFreed();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            while (!inflight.isEmpty()) {
                Batch batch = inflight.peekFirst();
                int n = batch.metadata().length;
                long pool = pools[batch.pool()];
                LongBuffer values = stack.callocLong(2 * Math.max(n, 1)); // (value, availability) pairs
                if (n != 0) {
                    VkAllocator.check(
                            VK12.vkGetQueryPoolResults(
                                    ctx.vkDevice(),
                                    pool,
                                    0,
                                    n,
                                    values,
                                    16,
                                    VK12.VK_QUERY_RESULT_64_BIT | VK12.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT),
                            "vkGetQueryPoolResults");
                    if (values.get(2 * (n - 1) + 1) == 0) {
                        break; // last timestamp of the oldest batch not written yet
                    }
                }
                // The pool is reused only when the submit that wrote it is known to be complete.
                if (ctx.completedSubmitIndex() < batch.submitIndex()) {
                    break;
                }
                long[] ns = new long[n];
                for (int i = 0; i < n; i++) {
                    ns[i] = (long) (values.get(2 * i) * nsPerTick);
                }
                inflight.removeFirst();
                poolBusy[batch.pool()] = false;
                batch.consumer().accept(batch.metadata(), ns);
            }
        }
    }

    @Override
    public void free() {
        super.free0();
        inflight.clear();
        long[] toDestroy = pools.clone();
        ctx.encoder().queueForDestroy(() -> {
            for (long pool : toDestroy) {
                VK12.vkDestroyQueryPool(ctx.vkDevice(), pool, null);
            }
        });
    }
}
