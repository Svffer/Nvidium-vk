package me.cortex.nvidium.backend;

/**
 * A device-local buffer that has a stable 64-bit GPU virtual address. nvidium's shaders reach
 * all their data through such addresses (GL: {@code NV_shader_buffer_load} resident buffers,
 * Vulkan: {@code VK_KHR_buffer_device_address}).
 */
public interface IDeviceMappedBuffer extends IBuffer {
    long getDeviceAddress();
}
