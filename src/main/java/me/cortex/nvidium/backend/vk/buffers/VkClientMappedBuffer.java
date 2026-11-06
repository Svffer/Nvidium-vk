package me.cortex.nvidium.backend.vk.buffers;

import me.cortex.nvidium.backend.IClientMappedBuffer;
import me.cortex.nvidium.backend.vk.VkAllocator;

/** Host-visible, persistently mapped staging buffer (upload and download streams). */
public class VkClientMappedBuffer extends VkBuffer implements IClientMappedBuffer {
    public VkClientMappedBuffer(VkAllocator.Allocation allocation, Releaser releaser) {
        super(allocation, releaser);
        if (allocation.mappedAddress() == 0) {
            throw new IllegalStateException("host buffer is not mapped");
        }
    }

    @Override
    public long clientAddress() {
        return allocation.mappedAddress();
    }
}
