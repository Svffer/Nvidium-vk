package me.cortex.nvidium.mixin.minecraft.vk;

import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The command buffer a Mojang render pass records into (dynamic rendering is already begun on it). */
@Mixin(VulkanRenderPass.class)
public interface VulkanRenderPassAccessor {
    @Accessor("commandBuffer")
    VkCommandBuffer nvidium$getCommandBuffer();
}
