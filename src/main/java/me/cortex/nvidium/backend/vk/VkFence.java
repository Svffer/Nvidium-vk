package me.cortex.nvidium.backend.vk;

import com.mojang.blaze3d.buffers.GpuFence;
import me.cortex.nvidium.backend.IFence;
import me.cortex.nvidium.backend.TrackedObject;

/**
 * Mojang's Vulkan backend has no {@code VkFence}s: completion is tracked by one timeline semaphore whose
 * value is the submit index (docs/VULKAN_FRAME_PROTOCOL.md §2.3). {@code encoder.createFence()} captures
 * the index of the submit being recorded, and {@code awaitCompletion(0)} polls it without blocking.
 */
public final class VkFence extends TrackedObject implements IFence {
    private final GpuFence fence;
    private boolean signaled;

    VkFence(GpuFence fence) {
        this.fence = fence;
    }

    @Override
    public boolean signaled() {
        assertNotFreed();
        if (!signaled) {
            signaled = fence.awaitCompletion(0);
        }
        return signaled;
    }

    /** Blocks until the submit this fence belongs to has completed, or the timeout passes. */
    public boolean await(long timeoutMs) {
        assertNotFreed();
        if (!signaled) {
            signaled = fence.awaitCompletion(timeoutMs);
        }
        return signaled;
    }

    @Override
    public void free() {
        super.free0();
        fence.close();
    }
}
