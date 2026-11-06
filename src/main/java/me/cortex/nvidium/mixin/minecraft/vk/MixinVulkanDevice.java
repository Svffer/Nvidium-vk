package me.cortex.nvidium.mixin.minecraft.vk;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.nvidium.backend.vk.VkDeviceBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * nvidium's Vulkan objects (allocator, pipeline layout, buffers awaiting release) must go before
 * {@code vkDestroyDevice}. {@code VulkanDevice.close()} first destroys the command encoder, which drains its
 * destruction queue, so queuing the backend's teardown at the head of {@code close()} is enough.
 */
@Mixin(VulkanDevice.class)
public class MixinVulkanDevice {
    @Inject(method = "close", at = @At("HEAD"))
    private void nvidium$onDeviceClosing(CallbackInfo ci) {
        VkDeviceBootstrap.onDeviceClosing();
    }
}
