package me.cortex.nvidium.backend.vk;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.nvidium.mixin.minecraft.GpuDeviceAccessor;
import me.cortex.nvidium.mixin.minecraft.vk.VulkanCommandEncoderAccessor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/**
 * The handles nvidium needs from Mojang's Vulkan backend (the Vulkan frame-protocol notes). One per
 * {@code GpuDevice}; cheap to construct, holds no resources of its own.
 */
public final class VkContext {
    private final VulkanDevice device;
    private final VulkanCommandEncoder encoder;

    private VkContext(VulkanDevice device) {
        this.device = device;
        this.encoder = device.createCommandEncoder(); // the single encoder instance
    }

    /** @return the context, or {@code null} if {@code gpuDevice} is not backed by Vulkan */
    public static VkContext from(GpuDevice gpuDevice) {
        if (((GpuDeviceAccessor) gpuDevice).nvidium$getBackend() instanceof VulkanDevice vk) {
            return new VkContext(vk);
        }
        return null;
    }

    public VulkanDevice device() {
        return device;
    }

    public VkDevice vkDevice() {
        return device.vkDevice();
    }

    public VulkanCommandEncoder encoder() {
        return encoder;
    }

    private VulkanCommandEncoderAccessor encoderAccess() {
        return (VulkanCommandEncoderAccessor) (Object) encoder;
    }

    /**
     * The open main command buffer of the submit being recorded, outside any Mojang render pass. Copies,
     * barriers, compute dispatches and nvidium's own passes go here.
     */
    public VkCommandBuffer commandBuffer() {
        if (encoderAccess().nvidium$getCurrentRenderPass() != null) {
            throw new IllegalStateException(
                    "nvidium: outside-pass command recorded while a Mojang render pass is open");
        }
        return encoderAccess().nvidium$commandBuffer();
    }

    /** Debug name for a Vulkan object (RenderDoc, validation messages); a no-op unless Mojang's debug labels are on. */
    public void name(int objectType, long handle, String label) {
        device.instance().debug().setObjectName(vkDevice(), objectType, handle, label);
    }

    /** Index of the submit being recorded (Mojang increments it once per frame in {@code submit()}). */
    public long currentSubmitIndex() {
        return encoderAccess().nvidium$getCurrentSubmitIndex();
    }

    public long completedSubmitIndex() {
        return encoderAccess().nvidium$getCompletedSubmitIndex();
    }
}
