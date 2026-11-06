package me.cortex.nvidium.backend.vk.buffers;

import me.cortex.nvidium.backend.IBuffer;
import me.cortex.nvidium.backend.TrackedObject;
import me.cortex.nvidium.backend.vk.VkAllocator;

/** Common part of nvidium's Vulkan buffers: a VMA allocation that is freed through the backend's queue. */
public abstract class VkBuffer extends TrackedObject implements IBuffer {
    protected final VkAllocator.Allocation allocation;
    private final Releaser releaser;

    /** Defers {@link VkAllocator#destroy} until the GPU no longer uses the buffer. */
    public interface Releaser {
        void release(VkAllocator.Allocation allocation);
    }

    protected VkBuffer(VkAllocator.Allocation allocation, Releaser releaser) {
        this.allocation = allocation;
        this.releaser = releaser;
    }

    public long vkBuffer() {
        return allocation.vkBuffer();
    }

    public VkAllocator.Allocation allocation() {
        return allocation;
    }

    @Override
    public long getSize() {
        return allocation.size();
    }

    @Override
    public void delete() {
        super.free0();
        releaser.release(allocation);
    }

    @Override
    public void free() {
        delete();
    }
}
