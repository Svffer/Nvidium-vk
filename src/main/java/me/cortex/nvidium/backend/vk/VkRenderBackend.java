package me.cortex.nvidium.backend.vk;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.backend.Barrier;
import me.cortex.nvidium.backend.IBuffer;
import me.cortex.nvidium.backend.IClientMappedBuffer;
import me.cortex.nvidium.backend.IDeviceMappedBuffer;
import me.cortex.nvidium.backend.IFence;
import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.IShaderBuilder;
import me.cortex.nvidium.backend.IShaderProcessor;
import me.cortex.nvidium.backend.ISparseBuffer;
import me.cortex.nvidium.backend.ITimestampQuerySet;
import me.cortex.nvidium.backend.vk.buffers.VkBuffer;
import me.cortex.nvidium.backend.vk.buffers.VkClientMappedBuffer;
import me.cortex.nvidium.backend.vk.buffers.VkDeviceOnlyBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/**
 * nvidium on Mojang's Vulkan backend. M3 (docs/VULKAN_PLAN.md): memory and streaming — buffers with device
 * addresses from an own VMA allocator, copies / fills / barriers on the main command buffer, fences on the
 * submit index. Shaders, textures, draws and the frame hooks arrive with M4 and M5 and throw until then.
 */
public final class VkRenderBackend implements IRenderBackend, AutoCloseable {
    // VK_EXT_mesh_shader sync2 stage bits (LWJGL exposes them on EXTMeshShader as well).
    private static final long STAGE_TASK_SHADER = 0x80000L;
    private static final long STAGE_MESH_SHADER = 0x100000L;
    private static final long STAGE_ALL_SHADERS = VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
            | VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
            | STAGE_TASK_SHADER
            | STAGE_MESH_SHADER;

    private final VkContext ctx;
    private final VkAllocator allocator;
    private final VkDeviceCapabilities capabilities;
    private final VkSpirvCompiler compiler;

    public VkRenderBackend(VkContext ctx, VkDeviceCapabilities capabilities) {
        this.ctx = ctx;
        this.capabilities = capabilities;
        this.allocator = new VkAllocator(ctx.vkDevice());
        this.compiler = new VkSpirvCompiler(Nvidium.IS_DEBUG);
    }

    public VkContext context() {
        return ctx;
    }

    public VkAllocator allocator() {
        return allocator;
    }

    public VkDeviceCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public String name() {
        return "Vulkan";
    }

    @Override
    public boolean supportsSparseBuffers() {
        return false; // VMA arena with reallocation instead (plan §2); the GL Linux path already runs that way
    }

    // ---------------------------------------------------------------- resources

    /** Buffers are destroyed one Mojang submit after {@code delete()}, when the GPU is done with them. */
    private void release(VkAllocator.Allocation allocation) {
        ctx.encoder().queueForDestroy(() -> allocator.destroy(allocation));
    }

    @Override
    public IClientMappedBuffer createClientMappedBuffer(long size) {
        return new VkClientMappedBuffer(allocator.createHostBuffer(size, true), this::release);
    }

    @Override
    public IDeviceMappedBuffer createDeviceOnlyMappedBuffer(long size) {
        return new VkDeviceOnlyBuffer(allocator.createDeviceBuffer(size), this::release);
    }

    @Override
    public ISparseBuffer createSparseBuffer(long totalSize) {
        throw new UnsupportedOperationException("sparse buffers are not used on the Vulkan backend");
    }

    @Override
    public IFence createFence() {
        return new VkFence(ctx.encoder().createFence());
    }

    @Override
    public IShaderBuilder createShader(IShaderProcessor processor) {
        return new VkShader.Builder(ctx, compiler, processor);
    }

    @Override
    public ITimestampQuerySet createTimestampQuerySet() {
        throw new UnsupportedOperationException("M5: timestamp queries");
    }

    // ---------------------------------------------------------------- buffer operations

    private static VkBuffer vk(IBuffer buffer) {
        return (VkBuffer) buffer;
    }

    @Override
    public void flushMapped(IClientMappedBuffer buffer, long offset, long size) {
        allocator.flush(vk(buffer).allocation(), offset, size);
    }

    @Override
    public void invalidateMapped(IClientMappedBuffer buffer, long offset, long size) {
        allocator.invalidate(vk(buffer).allocation(), offset, size);
    }

    @Override
    public void copyBuffer(IBuffer src, IBuffer dst, long srcOffset, long dstOffset, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                    .srcOffset(srcOffset)
                    .dstOffset(dstOffset)
                    .size(size);
            VK12.vkCmdCopyBuffer(ctx.commandBuffer(), vk(src).vkBuffer(), vk(dst).vkBuffer(), region);
        }
    }

    @Override
    public void clearBuffer(IBuffer buffer, long offset, long size) {
        // vkCmdFillBuffer needs 4-byte alignment; nvidium's clears are byte-granular (e.g. 255 bytes at
        // a 256-byte-aligned offset), so round outwards within the buffer.
        long start = offset & ~3L;
        long end = Math.min(buffer.getSize(), (offset + size + 3) & ~3L);
        VK12.vkCmdFillBuffer(ctx.commandBuffer(), vk(buffer).vkBuffer(), start, end - start, 0);
    }

    @Override
    public void barrier(int flags) {
        long srcStage = VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        long srcAccess = VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
        long dstStage = 0;
        long dstAccess = 0;
        if ((flags & Barrier.SHADER_STORAGE) != 0) {
            dstStage |= STAGE_ALL_SHADERS;
            dstAccess |= VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT;
        }
        if ((flags & Barrier.COMMAND) != 0) {
            dstStage |= VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
            dstAccess |= VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
        }
        if ((flags & Barrier.FRAMEBUFFER) != 0) {
            dstStage |= VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                    | VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT
                    | VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
            dstAccess |= VK13.VK_ACCESS_2_MEMORY_READ_BIT | VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
        }
        if ((flags & Barrier.CLIENT_MAPPED_BUFFER) != 0) {
            srcStage |= VK13.VK_PIPELINE_STAGE_2_HOST_BIT;
            srcAccess |= VK13.VK_ACCESS_2_HOST_WRITE_BIT;
            dstStage |= VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
            dstAccess |= VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
        }
        if ((flags & Barrier.BUFFER_UPDATE) != 0) {
            dstStage |= STAGE_ALL_SHADERS
                    | VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT
                    | VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
            dstAccess |= VK13.VK_ACCESS_2_MEMORY_READ_BIT | VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
        }
        if (dstStage == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer mem = VkMemoryBarrier2.calloc(1, stack)
                    .sType$Default()
                    .srcStageMask(srcStage)
                    .srcAccessMask(srcAccess)
                    .dstStageMask(dstStage)
                    .dstAccessMask(dstAccess);
            VkDependencyInfo dep =
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(mem);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(ctx.commandBuffer(), dep);
        }
    }

    @Override
    public void finish() {
        ctx.device().graphicsQueue().waitIdle();
    }

    @Override
    public long getAvailableDeviceMemoryMB() {
        return allocator.availableDeviceLocalMB();
    }

    // ---------------------------------------------------------------- frame integration (M5)

    @Override
    public void beginLayer(RenderPass pass, boolean translucent) {
        throw new UnsupportedOperationException("M5: frame integration");
    }

    @Override
    public void endLayer(RenderPass pass, boolean translucent) {
        throw new UnsupportedOperationException("M5: frame integration");
    }

    @Override
    public void beginScene(IDeviceMappedBuffer sceneUniform, int sceneSize) {
        throw new UnsupportedOperationException("M5: frame integration");
    }

    @Override
    public void endScene() {
        throw new UnsupportedOperationException("M5: frame integration");
    }

    @Override
    public void beginOcclusionRaster() {
        throw new UnsupportedOperationException("M5: pipelines");
    }

    @Override
    public void endOcclusionRaster() {
        throw new UnsupportedOperationException("M5: pipelines");
    }

    @Override
    public void bindTexture(int slot, GpuTextureView texture, GpuSampler sampler) {
        throw new UnsupportedOperationException("M5: descriptors");
    }

    @Override
    public void unbindTexture(int slot) {
        throw new UnsupportedOperationException("M5: descriptors");
    }

    @Override
    public void drawMeshTasks(IShader shader, int first, int count) {
        throw new UnsupportedOperationException("M5: draws");
    }

    @Override
    public void drawMeshTasksIndirect(IShader shader, IDeviceMappedBuffer commands, long offset, int drawCount) {
        throw new UnsupportedOperationException("M5: draws");
    }

    @Override
    public void dispatchCompute(IShader shader, int x, int y, int z) {
        throw new UnsupportedOperationException("M5: dispatch");
    }

    /**
     * Destroys the allocator after everything queued before it: deletions go through
     * {@code queueForDestroy} (one submit later), so the allocator is closed through the same FIFO queue.
     */
    @Override
    public void close() {
        compiler.close();
        ctx.encoder().queueForDestroy(allocator::close);
    }
}
