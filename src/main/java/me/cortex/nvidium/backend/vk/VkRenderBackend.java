package me.cortex.nvidium.backend.vk;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import java.util.ArrayDeque;
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
import me.cortex.nvidium.backend.NvidiumShader;
import me.cortex.nvidium.backend.vk.buffers.VkBuffer;
import me.cortex.nvidium.backend.vk.buffers.VkClientMappedBuffer;
import me.cortex.nvidium.backend.vk.buffers.VkDeviceOnlyBuffer;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * nvidium on Mojang's Vulkan backend (docs/VULKAN_PLAN.md M3–M5). Memory: buffers with device addresses from
 * an own VMA allocator; fences on the submit index. Frame: everything is recorded on Mojang's main command
 * buffer between its render passes. Inside a layer ({@link #beginLayer}/{@link #endLayer}) nvidium opens its
 * own dynamic-rendering instance lazily for draws and closes it again before any copy, fill, barrier or
 * dispatch, so the GL call order of {@code RenderPipeline} (uploads, draw, compute, occlusion raster, compute,
 * draw) maps onto a few rendering instances per frame. Pipelines come from {@link VkPipelineFactory};
 * descriptors are pushed before every draw and dispatch.
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
    private final VkPipelineFactory pipelines;
    private final double timestampPeriodNs;

    // --- layer state (valid between beginLayer and endLayer) ---
    private boolean layerOpen;
    private boolean layerTranslucent;
    private boolean rendering; // nvidium's own dynamic rendering instance is open
    private long layerColourView;
    private long layerDepthView;
    private int layerColourFormat;
    private int layerDepthFormat;
    private int layerWidth;
    private int layerHeight;
    private boolean occlusionRaster;

    // --- descriptor state ---
    private VkBuffer sceneBuffer;
    private int sceneSize;
    private final long[] textureViews = new long[2];
    private final long[] textureSamplers = new long[2];

    public VkRenderBackend(VkContext ctx, VkDeviceCapabilities capabilities) {
        this.ctx = ctx;
        this.capabilities = capabilities;
        this.allocator = new VkAllocator(ctx.vkDevice());
        this.compiler = new VkSpirvCompiler(Nvidium.IS_DEBUG);
        this.pipelines = new VkPipelineFactory(ctx, capabilities);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(ctx.vkDevice().getPhysicalDevice(), props);
            this.timestampPeriodNs = props.limits().timestampPeriod();
        }
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

    // --- deferred release --------------------------------------------------------------------------
    // A deleted buffer may still be read by the submit being recorded and by the one in flight, so it is
    // freed once the submit it was deleted in has completed (what Mojang's destruction queue does too). The
    // queue is nvidium's own so that it can be drained early: Sodium recreates the world renderer in the
    // same tick it destroys the old one, and the new terrain arena (most of the VRAM) only fits once the
    // old one is gone.

    private record PendingRelease(VkAllocator.Allocation allocation, long submitIndex) {}

    private final ArrayDeque<PendingRelease> pendingReleases = new ArrayDeque<>();

    /** Submit index of the last nvidium command recorded; tells whether the current submit references anything of ours. */
    private long lastRecordedSubmit = -1;

    private void release(VkAllocator.Allocation allocation) {
        pollReleases();
        pendingReleases.addLast(new PendingRelease(allocation, ctx.currentSubmitIndex()));
    }

    /** Frees what belongs to completed submits. */
    private void pollReleases() {
        long completed = ctx.completedSubmitIndex();
        while (!pendingReleases.isEmpty() && pendingReleases.peekFirst().submitIndex() <= completed) {
            allocator.destroy(pendingReleases.removeFirst().allocation());
        }
    }

    /**
     * Waits for the GPU and frees everything that no pending command buffer can reference: buffers deleted
     * in earlier submits, and all of them if nvidium has not recorded into the current submit yet.
     */
    private void drainReleases() {
        ctx.device().graphicsQueue().waitIdle();
        long current = ctx.currentSubmitIndex();
        boolean currentUntouched = lastRecordedSubmit < current;
        var it = pendingReleases.iterator();
        while (it.hasNext()) {
            PendingRelease p = it.next();
            if (currentUntouched || p.submitIndex() < current) {
                allocator.destroy(p.allocation());
                it.remove();
            }
        }
    }

    private long pendingDeviceLocalBytes() {
        long bytes = 0;
        for (PendingRelease p : pendingReleases) {
            if (p.allocation().deviceAddress() != 0) {
                bytes += p.allocation().size();
            }
        }
        return bytes;
    }

    @Override
    public IClientMappedBuffer createClientMappedBuffer(long size) {
        pollReleases();
        return new VkClientMappedBuffer(allocator.createHostBuffer(size, true), this::release);
    }

    @Override
    public IDeviceMappedBuffer createDeviceOnlyMappedBuffer(long size) {
        pollReleases();
        VkAllocator.Allocation allocation = allocator.tryCreateDeviceBuffer(size);
        if (allocation == null && !pendingReleases.isEmpty()) {
            Nvidium.LOGGER.info(
                    "nvidium Vulkan: {} MiB buffer does not fit next to {} MiB awaiting release; draining",
                    size >> 20,
                    pendingDeviceLocalBytes() >> 20);
            drainReleases();
            allocation = allocator.tryCreateDeviceBuffer(size);
        }
        if (allocation == null) {
            Nvidium.LOGGER.warn(
                    "nvidium Vulkan: {} MiB buffer does not fit in device-local memory; "
                            + "falling back to host memory, expect slow terrain draws",
                    size >> 20);
            allocation = allocator.createDeviceBufferAnywhere(size);
        }
        return new VkDeviceOnlyBuffer(allocation, this::release);
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
        return new VkShader.Builder(ctx, pipelines, compiler, processor);
    }

    @Override
    public ITimestampQuerySet createTimestampQuerySet() {
        return new VkTimestampQuerySet(ctx, timestampPeriodNs);
    }

    @Override
    public Identifier shaderSource(NvidiumShader shader) {
        return shader.vk();
    }

    @Override
    public int indirectCommandStride() {
        return 16; // VkDrawMeshTasksIndirectCommandEXT {x, y, z} + base section id (read via gl_DrawID)
    }

    // ---------------------------------------------------------------- command buffer access

    private static VkBuffer vk(IBuffer buffer) {
        return (VkBuffer) buffer;
    }

    /** The main command buffer with nvidium's rendering instance closed: for copies, fills, barriers, dispatches. */
    private VkCommandBuffer outsideRendering() {
        VkCommandBuffer cb = ctx.commandBuffer();
        lastRecordedSubmit = ctx.currentSubmitIndex();
        if (rendering) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(cb);
            rendering = false;
        }
        return cb;
    }

    /** The main command buffer with nvidium's rendering instance open on the layer's attachments: for draws. */
    private VkCommandBuffer insideRendering() {
        VkCommandBuffer cb = ctx.commandBuffer();
        lastRecordedSubmit = ctx.currentSubmitIndex();
        if (rendering) {
            return cb;
        }
        if (!layerOpen) {
            throw new IllegalStateException("nvidium: draw outside beginLayer/endLayer");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Same conventions as Mojang's passes: attachments stay in GENERAL, LOAD/STORE, one layer.
            VkRenderingAttachmentInfo.Buffer colour = VkRenderingAttachmentInfo.calloc(1, stack)
                    .sType$Default()
                    .imageView(layerColourView)
                    .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK12.VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack)
                    .sType$Default()
                    .imageView(layerDepthView)
                    .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK12.VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo info = VkRenderingInfo.calloc(stack)
                    .sType$Default()
                    .layerCount(1)
                    .viewMask(0)
                    .pColorAttachments(colour)
                    .pDepthAttachment(depth);
            info.renderArea().extent().set(layerWidth, layerHeight);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, info);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .width(layerWidth)
                    .height(layerHeight)
                    .minDepth(0.0f)
                    .maxDepth(1.0f);
            VK12.vkCmdSetViewport(cb, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.extent().set(layerWidth, layerHeight);
            VK12.vkCmdSetScissor(cb, 0, scissor);
        }
        rendering = true;
        return cb;
    }

    // ---------------------------------------------------------------- buffer operations

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
            VK12.vkCmdCopyBuffer(outsideRendering(), vk(src).vkBuffer(), vk(dst).vkBuffer(), region);
        }
    }

    @Override
    public void clearBuffer(IBuffer buffer, long offset, long size) {
        // vkCmdFillBuffer needs 4-byte alignment; nvidium's clears are byte-granular (e.g. 255 bytes at
        // a 256-byte-aligned offset), so round outwards within the buffer.
        long start = offset & ~3L;
        long end = Math.min(buffer.getSize(), (offset + size + 3) & ~3L);
        VK12.vkCmdFillBuffer(outsideRendering(), vk(buffer).vkBuffer(), start, end - start, 0);
    }

    @Override
    public void barrier(int flags) {
        if (flags == Barrier.FRAMEBUFFER && rendering) {
            // Depth written by earlier draws of the same rendering instance is seen by later depth tests in
            // primitive order; a pipeline barrier is neither needed nor allowed inside dynamic rendering.
            return;
        }
        long srcStage = VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        long srcAccess = VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
        long dstStage = 0;
        long dstAccess = 0;
        if ((flags & Barrier.SHADER_STORAGE) != 0) {
            // Also transfers: the region visibility tracker copies shader-written bytes to the download
            // stream right after this barrier (GL orders the copy implicitly).
            dstStage |= STAGE_ALL_SHADERS | VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
            dstAccess |= VK13.VK_ACCESS_2_SHADER_READ_BIT
                    | VK13.VK_ACCESS_2_SHADER_WRITE_BIT
                    | VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
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
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(outsideRendering(), dep);
        }
    }

    @Override
    public void finish() {
        ctx.device().graphicsQueue().waitIdle();
    }

    /** VMA's budget plus what awaits release: the new terrain arena is sized before the old one is freed. */
    @Override
    public long getAvailableDeviceMemoryMB() {
        pollReleases();
        return allocator.availableDeviceLocalMB() + (pendingDeviceLocalBytes() >> 20);
    }

    // ---------------------------------------------------------------- frame integration

    @Override
    public void beginLayer(GpuTextureView colour, GpuTextureView depth, boolean translucent) {
        if (layerOpen) {
            throw new IllegalStateException("nvidium: beginLayer while a layer is open");
        }
        VulkanGpuTextureView colourView = (VulkanGpuTextureView) colour;
        VulkanGpuTextureView depthView = (VulkanGpuTextureView) depth;
        layerColourView = colourView.vkImageView();
        layerDepthView = depthView.vkImageView();
        layerColourFormat = VulkanConst.toVk(colourView.texture().getFormat());
        layerDepthFormat = VulkanConst.toVk(depthView.texture().getFormat());
        layerWidth = colour.getWidth(0);
        layerHeight = colour.getHeight(0);
        layerTranslucent = translucent;
        occlusionRaster = false;
        layerOpen = true;
    }

    @Override
    public void endLayer(boolean translucent) {
        if (!layerOpen) {
            throw new IllegalStateException("nvidium: endLayer without beginLayer");
        }
        VkCommandBuffer cb = outsideRendering();
        // What Mojang emits after each of its passes: everything nvidium wrote is visible to all later work.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(cb, stack);
        }
        layerOpen = false;
    }

    @Override
    public void beginScene(IDeviceMappedBuffer sceneUniform, int size) {
        sceneBuffer = vk(sceneUniform);
        sceneSize = size;
    }

    @Override
    public void endScene() {
        sceneBuffer = null;
    }

    @Override
    public void beginOcclusionRaster() {
        occlusionRaster = true;
    }

    @Override
    public void endOcclusionRaster() {
        occlusionRaster = false;
    }

    // ---------------------------------------------------------------- textures

    @Override
    public void bindTexture(int slot, GpuTextureView texture, GpuSampler sampler) {
        textureViews[slot] = ((VulkanGpuTextureView) texture).vkImageView();
        textureSamplers[slot] = ((VulkanGpuSampler) sampler).vkSampler();
    }

    @Override
    public void unbindTexture(int slot) {
        textureViews[slot] = 0;
        textureSamplers[slot] = 0;
    }

    /**
     * Pushes the scene UBO and, for graphics, the bound textures. Done before every draw/dispatch: Mojang's
     * pipelines use incompatible layouts, so pushed state does not survive its draws between nvidium's.
     */
    private void pushDescriptors(VkCommandBuffer cb, int bindPoint, boolean withTextures) {
        if (sceneBuffer == null) {
            throw new IllegalStateException("nvidium: draw/dispatch outside beginScene/endScene");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int n = 1;
            if (withTextures) {
                for (long view : textureViews) {
                    if (view != 0) n++;
                }
            }
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(n, stack);
            int w = 0;
            VkDescriptorBufferInfo.Buffer scene = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(sceneBuffer.vkBuffer())
                    .offset(0)
                    .range(sceneSize);
            writes.get(w++)
                    .sType$Default()
                    .dstBinding(VkPipelineFactory.BINDING_SCENE)
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(scene);
            if (withTextures) {
                for (int slot = 0; slot < textureViews.length; slot++) {
                    if (textureViews[slot] == 0) continue;
                    VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack)
                            .sampler(textureSamplers[slot])
                            .imageView(textureViews[slot])
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL); // Mojang keeps every texture in GENERAL
                    writes.get(w++)
                            .sType$Default()
                            .dstBinding(slot)
                            .dstArrayElement(0)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(image);
                }
            }
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, bindPoint, pipelines.pipelineLayout(), 0, writes);
        }
    }

    // ---------------------------------------------------------------- draw / dispatch

    private VkCommandBuffer bindGraphics(IShader shader) {
        VkCommandBuffer cb = insideRendering();
        var state = new VkPipelineFactory.GraphicsState(
                layerColourFormat, layerDepthFormat, layerTranslucent && !occlusionRaster, occlusionRaster);
        VK12.vkCmdBindPipeline(cb, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, ((VkShader) shader).graphicsPipeline(state));
        pushDescriptors(cb, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, true);
        return cb;
    }

    @Override
    public void drawMeshTasks(IShader shader, int first, int count) {
        if (first != 0) {
            throw new IllegalArgumentException("EXT mesh shading has no first-task offset");
        }
        EXTMeshShader.vkCmdDrawMeshTasksEXT(bindGraphics(shader), count, 1, 1);
    }

    @Override
    public void drawMeshTasksIndirect(IShader shader, IDeviceMappedBuffer commands, long offset, int drawCount) {
        EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(
                bindGraphics(shader), vk(commands).vkBuffer(), offset, drawCount, indirectCommandStride());
    }

    @Override
    public void dispatchCompute(IShader shader, int x, int y, int z) {
        VkCommandBuffer cb = outsideRendering();
        VK12.vkCmdBindPipeline(cb, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, ((VkShader) shader).computePipeline());
        pushDescriptors(cb, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, false);
        VK12.vkCmdDispatch(cb, x, y, z);
    }

    /**
     * Destroys the allocator after everything queued before it: deletions go through
     * {@code queueForDestroy} (one submit later), so the allocator is closed through the same FIFO queue.
     */
    @Override
    public void close() {
        compiler.close();
        pipelines.close();
        ctx.encoder().queueForDestroy(() -> {
            for (PendingRelease p : pendingReleases) {
                allocator.destroy(p.allocation());
            }
            pendingReleases.clear();
            allocator.close();
        });
    }
}
