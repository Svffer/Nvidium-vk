package me.cortex.nvidium.backend;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * Everything in nvidium that touches the GPU goes through this interface. There is exactly one
 * live implementation per game session, chosen from the type of Mojang's {@code GpuDevice}
 * backend ({@link me.cortex.nvidium.backend.gl.GlRenderBackend} for OpenGL). The renderer,
 * managers and streams are written against this interface only.
 *
 * <p>Threading: all methods must be called on the render thread.
 */
public interface IRenderBackend {
    /** Human-readable name used in logs and the debug overlay. */
    String name();

    /** Whether {@link #createSparseBuffer(long)} is usable on this backend/driver. */
    boolean supportsSparseBuffers();

    // ---------------------------------------------------------------- resources

    IClientMappedBuffer createClientMappedBuffer(long size);

    IDeviceMappedBuffer createDeviceOnlyMappedBuffer(long size);

    ISparseBuffer createSparseBuffer(long totalSize);

    IFence createFence();

    IShaderBuilder createShader(IShaderProcessor processor);

    default IShaderBuilder createShader() {
        return createShader((type, source) -> source);
    }

    ITimestampQuerySet createTimestampQuerySet();

    // ---------------------------------------------------------------- buffer operations

    /** Makes host writes to a mapped range visible to the GPU. */
    void flushMapped(IClientMappedBuffer buffer, long offset, long size);

    void copyBuffer(IBuffer src, IBuffer dst, long srcOffset, long dstOffset, long size);

    /** Fills {@code size} bytes at {@code offset} with zeros. */
    void clearBuffer(IBuffer buffer, long offset, long size);

    /** Memory barrier; {@code flags} is a combination of {@link Barrier} bits. */
    void barrier(int flags);

    /** Blocks until all previously submitted GPU work has completed. */
    void finish();

    /** Currently available device memory in MiB, or -1 if the backend cannot tell. */
    long getAvailableDeviceMemoryMB();

    // ---------------------------------------------------------------- frame integration

    /**
     * Called from the Sodium {@code renderLayer} hook right after Mojang's render pass for the
     * terrain target has been opened. Sets up the fixed-function state nvidium expects
     * (reverse-Z depth test, culling, blending for the translucent layer).
     */
    void beginLayer(RenderPass pass, boolean translucent);

    /** Counterpart of {@link #beginLayer}; called before the Mojang render pass is closed. */
    void endLayer(RenderPass pass, boolean translucent);

    /**
     * Binds the scene uniform for the following nvidium draws and dispatches. Must be paired
     * with {@link #endScene()}.
     */
    void beginScene(IDeviceMappedBuffer sceneUniform, int sceneSize);

    void endScene();

    /**
     * Switches to the state used by the GPU occlusion rasterizers: depth test on, no depth or
     * colour writes, representative fragment test where available.
     */
    void beginOcclusionRaster();

    void endOcclusionRaster();

    // ---------------------------------------------------------------- textures

    void bindTexture(int slot, GpuTextureView texture, GpuSampler sampler);

    void unbindTexture(int slot);

    // ---------------------------------------------------------------- draw / dispatch

    /** Launches {@code count} task (or mesh, if the program has no task stage) work groups. */
    void drawMeshTasks(IShader shader, int first, int count);

    /**
     * Executes {@code drawCount} tightly packed 8-byte {@code (count, first)} mesh-task commands
     * read from {@code commands} starting at {@code offset}.
     */
    void drawMeshTasksIndirect(IShader shader, IDeviceMappedBuffer commands, long offset, int drawCount);

    void dispatchCompute(IShader shader, int x, int y, int z);
}
