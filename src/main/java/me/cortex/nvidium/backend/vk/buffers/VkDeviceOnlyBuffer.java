package me.cortex.nvidium.backend.vk.buffers;

import me.cortex.nvidium.backend.IDeviceMappedBuffer;
import me.cortex.nvidium.backend.vk.VkAllocator;

/** Device-local storage reached from shaders through its 64-bit device address. */
public class VkDeviceOnlyBuffer extends VkBuffer implements IDeviceMappedBuffer {
    public VkDeviceOnlyBuffer(VkAllocator.Allocation allocation, Releaser releaser) {
        super(allocation, releaser);
    }

    @Override
    public long getDeviceAddress() {
        return allocation.deviceAddress();
    }
}
