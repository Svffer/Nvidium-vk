package me.cortex.nvidium.mixin.minecraft;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code RenderSystem.getDevice()} wraps the real backend ({@code GlDevice} or {@code VulkanDevice}). */
@Mixin(GpuDevice.class)
public interface GpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend nvidium$getBackend();
}
