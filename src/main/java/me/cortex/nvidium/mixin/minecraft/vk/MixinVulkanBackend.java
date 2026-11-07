package me.cortex.nvidium.mixin.minecraft.vk;

import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import java.util.Collection;
import java.util.Set;
import me.cortex.nvidium.backend.vk.VkDeviceBootstrap;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Device bootstrap. The private static {@code createDevice(Collection, VulkanPhysicalDevice, Set)}
 * receives the mutable extension and feature sets that go straight into {@code VkDeviceCreateInfo}
 * (the Vulkan frame-protocol notes), so adding to them at its head is all that is needed.
 */
@Mixin(VulkanBackend.class)
public class MixinVulkanBackend {
    @Inject(
            method =
                    "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD"))
    private static void nvidium$extendDeviceCreation(
            Collection<String> deviceExtensions,
            VulkanPhysicalDevice physicalDevice,
            Set<VulkanFeature> features,
            CallbackInfoReturnable<VkDevice> cir) {
        VkDeviceBootstrap.extendDeviceCreation(physicalDevice, deviceExtensions, features);
    }

    @Inject(
            method =
                    "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
            at = @At("RETURN"))
    private void nvidium$onDeviceCreated(
            long window,
            ShaderSource defaultShaderSource,
            GpuDebugOptions debugOptions,
            Runnable criticalShaderLoader,
            CallbackInfoReturnable<GpuDevice> cir) {
        VkDeviceBootstrap.onDeviceCreated(cir.getReturnValue());
    }
}
