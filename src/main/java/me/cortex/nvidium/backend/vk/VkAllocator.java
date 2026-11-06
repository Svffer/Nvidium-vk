package me.cortex.nvidium.backend.vk;

import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

/**
 * nvidium's own VMA allocator on Mojang's {@code VkDevice}. Mojang's allocator is created without
 * {@code VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT} (docs/VULKAN_FRAME_PROTOCOL.md §1.3), and all of
 * nvidium's device buffers are reached through 64-bit device addresses, so a second allocator is the
 * least invasive option. Lives as long as the Vulkan render backend.
 */
public final class VkAllocator implements AutoCloseable {
    /** Usage of every device buffer nvidium creates: shader pointers, copies, fills, indirect commands. */
    public static final int DEVICE_BUFFER_USAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
            | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;

    /** Staging buffers only move bytes. */
    public static final int STAGING_BUFFER_USAGE =
            VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    /** A buffer plus its allocation; {@code mappedAddress} is 0 for device-local buffers. */
    public record Allocation(long vkBuffer, long vmaAllocation, long size, long deviceAddress, long mappedAddress) {}

    private final VkDevice device;
    private final long allocator;
    private final VkPhysicalDeviceMemoryProperties memoryProperties;

    public VkAllocator(VkDevice device) {
        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack)
                    .set(device.getPhysicalDevice().getInstance(), device);
            VmaAllocatorCreateInfo createInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                    .instance(device.getPhysicalDevice().getInstance())
                    .vulkanApiVersion(VK12.VK_API_VERSION_1_2)
                    .device(device)
                    .physicalDevice(device.getPhysicalDevice())
                    .pVulkanFunctions(functions);
            PointerBuffer pointer = stack.callocPointer(1);
            check(Vma.vmaCreateAllocator(createInfo, pointer), "vmaCreateAllocator");
            this.allocator = pointer.get(0);
        }
        this.memoryProperties = VkPhysicalDeviceMemoryProperties.calloc();
        VK12.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memoryProperties);
    }

    /** Device-local buffer with a device address. */
    public Allocation createDeviceBuffer(long size) {
        return create(size, DEVICE_BUFFER_USAGE, 0, true);
    }

    /**
     * Host-visible buffer, persistently mapped. {@code forDownload} selects random host access (readback),
     * otherwise sequential write (upload staging).
     */
    public Allocation createHostBuffer(long size, boolean forDownload) {
        int flags = Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                | (forDownload
                        ? Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                        : Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        return create(size, STAGING_BUFFER_USAGE, flags, false);
    }

    private Allocation create(long size, int usage, int allocFlags, boolean deviceAddress) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(allocFlags);
            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAllocation = stack.callocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(allocator, bufferInfo, allocInfo, pBuffer, pAllocation, info), "vmaCreateBuffer");
            long vkBuffer = pBuffer.get(0);
            long address = 0;
            if (deviceAddress) {
                VkBufferDeviceAddressInfo addressInfo =
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(vkBuffer);
                address = VK12.vkGetBufferDeviceAddress(device, addressInfo);
                if (address == 0) {
                    throw new IllegalStateException("vkGetBufferDeviceAddress returned 0");
                }
            }
            return new Allocation(vkBuffer, pAllocation.get(0), size, address, info.pMappedData());
        }
    }

    /** Must only be called once the GPU is done with the buffer (see {@code queueForDestroy}). */
    public void destroy(Allocation allocation) {
        Vma.vmaDestroyBuffer(allocator, allocation.vkBuffer(), allocation.vmaAllocation());
    }

    /** Makes host writes in a mapped range visible to the device (no-op on coherent memory). */
    public void flush(Allocation allocation, long offset, long size) {
        Vma.vmaFlushAllocation(allocator, allocation.vmaAllocation(), offset, size);
    }

    /** Makes device writes visible to the host before reading a mapped range (no-op on coherent memory). */
    public void invalidate(Allocation allocation, long offset, long size) {
        Vma.vmaInvalidateAllocation(allocator, allocation.vmaAllocation(), offset, size);
    }

    /** Free device-local memory in MiB according to VMA's budget estimate across device-local heaps. */
    public long availableDeviceLocalMB() {
        int heapCount = memoryProperties.memoryHeapCount();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaBudget.Buffer budgets = VmaBudget.calloc(VK12.VK_MAX_MEMORY_HEAPS, stack);
            Vma.vmaGetHeapBudgets(allocator, budgets);
            long free = 0;
            for (int i = 0; i < heapCount; i++) {
                if ((memoryProperties.memoryHeaps(i).flags() & VK12.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    free += Math.max(0, budgets.get(i).budget() - budgets.get(i).usage());
                }
            }
            return free / (1024 * 1024);
        }
    }

    @Override
    public void close() {
        Vma.vmaDestroyAllocator(allocator);
        memoryProperties.free();
    }

    static void check(int result, String what) {
        if (result < 0) {
            throw new IllegalStateException("nvidium Vulkan: " + what + " failed with VkResult " + result);
        }
    }

    /** Helper for the few places that write raw bytes into a mapped allocation. */
    static void memSet(Allocation allocation, long offset, long size, int value) {
        MemoryUtil.memSet(allocation.mappedAddress() + offset, value, size);
    }
}
