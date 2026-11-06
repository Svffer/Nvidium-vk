package me.cortex.nvidium.mixin.minecraft.vk;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the per-frame state of Mojang's single {@code VulkanCommandEncoder}
 * (docs/VULKAN_FRAME_PROTOCOL.md §2, §8.1).
 */
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
    /**
     * The open "main" command buffer of the current submit, registered in the submission. Opens one lazily
     * if needed, which is why the field {@code currentCommandBuffer} itself is not exposed: it may be null.
     */
    @Invoker("commandBuffer")
    VkCommandBuffer nvidium$commandBuffer();

    /** Non-null while a Mojang render pass is open; nvidium must not record outside-pass commands then. */
    @Accessor("currentRenderPass")
    @Nullable
    VulkanRenderPass nvidium$getCurrentRenderPass();

    /** Index of the submit being recorded; {@code % MAX_SUBMITS_IN_FLIGHT} selects per-frame ring slots. */
    @Accessor("currentSubmitIndex")
    long nvidium$getCurrentSubmitIndex();

    /** Highest submit index known to have completed on the GPU. */
    @Accessor("completedSubmitIndex")
    long nvidium$getCompletedSubmitIndex();
}
