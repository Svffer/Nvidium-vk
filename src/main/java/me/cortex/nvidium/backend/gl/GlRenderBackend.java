package me.cortex.nvidium.backend.gl;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.backend.*;
import me.cortex.nvidium.backend.gl.buffers.GlClientMappedBuffer;
import me.cortex.nvidium.backend.gl.buffers.GlDeviceOnlyBuffer;
import me.cortex.nvidium.backend.gl.buffers.GlSparseBuffer;
import me.cortex.nvidium.mixin.minecraft.CommandEncoderAccessor;
import me.cortex.nvidium.mixin.minecraft.GlCommandEncoderAccessor;
import net.minecraft.util.Util;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;
import static org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.NVMeshShader.glDrawMeshTasksNV;
import static org.lwjgl.opengl.NVMeshShader.glMultiDrawMeshTasksIndirectNV;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.*;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX;

/**
 * The original NVIDIA-OpenGL implementation of nvidium: mesh shaders ({@code NV_mesh_shader}),
 * bindless multi-draw-indirect, unified-memory pointers ({@code NV_shader_buffer_load}) and the
 * representative fragment test. Requires an NVIDIA driver.
 */
public class GlRenderBackend implements IRenderBackend {
    private static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    private static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;
    private static final int GL_TEXTURE_BASE_LEVEL = 33084;
    private static final int GL_TEXTURE_MAX_LEVEL = 33085;

    /** 0: no debug, 1: show region occlusion boxes, 2: show section occlusion boxes. */
    private static final int DEBUG_RENDER_LEVEL = 0;
    private static final boolean DEBUG_WRITE_DEPTH = false;

    private final boolean supportsSparseBuffers;

    private GlRenderBackend(boolean supportsSparseBuffers) {
        this.supportsSparseBuffers = supportsSparseBuffers;
    }

    /**
     * Must be called on the render thread right after {@code GL.createCapabilities()}. Returns
     * {@code null} when the required extensions are missing.
     */
    public static GlRenderBackend tryCreate() {
        var cap = GL.getCapabilities();
        boolean supported = cap.GL_NV_mesh_shader &&
                cap.GL_NV_uniform_buffer_unified_memory &&
                cap.GL_NV_vertex_buffer_unified_memory &&
                cap.GL_NV_representative_fragment_test &&
                cap.GL_ARB_sparse_buffer &&
                cap.GL_NV_bindless_multi_draw_indirect;
        if (!supported) {
            return null;
        }
        boolean sparse = true;
        if (Util.getPlatform() == Util.OS.LINUX) {
            Nvidium.LOGGER.warn("Linux currently uses fallback terrain buffer due to driver inconsistencies, expect increase vram usage");
            sparse = false;
        }
        return new GlRenderBackend(sparse);
    }

    @Override
    public String name() {
        return "OpenGL (NVIDIA)";
    }

    @Override
    public boolean supportsSparseBuffers() {
        return supportsSparseBuffers;
    }

    // ---------------------------------------------------------------- resources

    @Override
    public IClientMappedBuffer createClientMappedBuffer(long size) {
        return new GlClientMappedBuffer(size);
    }

    @Override
    public IDeviceMappedBuffer createDeviceOnlyMappedBuffer(long size) {
        return new GlDeviceOnlyBuffer(size);
    }

    @Override
    public ISparseBuffer createSparseBuffer(long totalSize) {
        if (!supportsSparseBuffers) {
            throw new IllegalStateException();
        }
        return new GlSparseBuffer(totalSize);
    }

    @Override
    public IFence createFence() {
        return new GlFence();
    }

    @Override
    public IShaderBuilder createShader(IShaderProcessor processor) {
        return new GlShader.Builder(processor);
    }

    @Override
    public ITimestampQuerySet createTimestampQuerySet() {
        return new GlTimestampQuerySet();
    }

    // ---------------------------------------------------------------- buffer operations

    private static int id(IBuffer buffer) {
        return ((GlObject) buffer).getId();
    }

    @Override
    public void flushMapped(IClientMappedBuffer buffer, long offset, long size) {
        glFlushMappedNamedBufferRange(id(buffer), offset, size);
    }

    @Override
    public void copyBuffer(IBuffer src, IBuffer dst, long srcOffset, long dstOffset, long size) {
        glCopyNamedBufferSubData(id(src), id(dst), srcOffset, dstOffset, size);
    }

    @Override
    public void clearBuffer(IBuffer buffer, long offset, long size) {
        nglClearNamedBufferSubData(id(buffer), GL_R8UI, offset, size, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
    }

    @Override
    public void barrier(int flags) {
        int bits = 0;
        if ((flags & Barrier.SHADER_STORAGE) != 0) bits |= GL_SHADER_STORAGE_BARRIER_BIT;
        if ((flags & Barrier.COMMAND) != 0) bits |= GL_COMMAND_BARRIER_BIT;
        if ((flags & Barrier.FRAMEBUFFER) != 0) bits |= GL_FRAMEBUFFER_BARRIER_BIT;
        if ((flags & Barrier.CLIENT_MAPPED_BUFFER) != 0) bits |= GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;
        if ((flags & Barrier.BUFFER_UPDATE) != 0) bits |= GL_BUFFER_UPDATE_BARRIER_BIT;
        glMemoryBarrier(bits);
    }

    @Override
    public void finish() {
        glFinish();
    }

    @Override
    public long getAvailableDeviceMemoryMB() {
        return glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) / 1024;
    }

    // ---------------------------------------------------------------- frame integration

    @Override
    public void beginLayer(RenderPass pass, boolean translucent) {
        // Invalidate lastProgram to prevent B3D caching bad program
        ((GlCommandEncoderAccessor)
                ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder())
                        .nvidium$getCommandEncoderBackend())
                .nvidium$setLastProgram(null);

        GlStateManager._disableScissorTest();
        GlStateManager._enableCull();
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL33C.GL_GEQUAL); // reverse-Z
        GlStateManager._colorMask(0, 15);
        GlStateManager._depthMask(true);

        if (translucent) {
            GlStateManager._enableBlend(0);
            GlStateManager._blendFuncSeparate(
                    GL33C.GL_SRC_ALPHA,
                    GL33C.GL_ONE_MINUS_SRC_ALPHA,
                    GL33C.GL_ONE,
                    GL33C.GL_ONE_MINUS_SRC_ALPHA
            );
        } else {
            GlStateManager._disableBlend(0);
        }
    }

    @Override
    public void endLayer(RenderPass pass, boolean translucent) {
        if (translucent) {
            GlStateManager._disableBlend(0);
        }
    }

    @Override
    public void beginScene(IDeviceMappedBuffer sceneUniform, int sceneSize) {
        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Bind the uniform, it doesnt get wiped between shader changes
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), sceneSize);
        glEnable(GL_DEPTH_TEST);
    }

    @Override
    public void endScene() {
        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        glDepthFunc(GL_GEQUAL);
    }

    @Override
    public void beginOcclusionRaster() {
        //NOTE: For GL_REPRESENTATIVE_FRAGMENT_TEST_NV to work, depth testing must be disabled, or depthMask = false
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_GEQUAL);
        glDepthMask(false);
        if (DEBUG_RENDER_LEVEL != 0 && DEBUG_WRITE_DEPTH) {
            glDepthMask(true);
        }
        if (DEBUG_RENDER_LEVEL == 0) {
            glColorMask(false, false, false, false);
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        }
    }

    @Override
    public void endOcclusionRaster() {
        glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        glDepthMask(true);
        glColorMask(true, true, true, true);
    }

    // ---------------------------------------------------------------- textures

    @Override
    public void bindTexture(int slot, GpuTextureView texView, GpuSampler sampler) {
        GlTexture tex = (GlTexture) texView.texture();
        GlStateManager._activeTexture(GL33C.GL_TEXTURE0 + slot);
        GlStateManager._bindTexture(tex.glId());
        GlStateManager._texParameter(GL33C.GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, texView.baseMipLevel());
        GlStateManager._texParameter(GL33C.GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, texView.baseMipLevel() + texView.mipLevels() - 1);
        GL33C.glBindSampler(slot, ((GlSampler) sampler).getId());
    }

    @Override
    public void unbindTexture(int slot) {
        GL33C.glBindSampler(slot, 0);
    }

    // ---------------------------------------------------------------- draw / dispatch

    @Override
    public void drawMeshTasks(IShader shader, int first, int count) {
        ((GlShader) shader).bind();
        glDrawMeshTasksNV(first, count);
    }

    @Override
    public void drawMeshTasksIndirect(IShader shader, IDeviceMappedBuffer commands, long offset, int drawCount) {
        ((GlShader) shader).bind();
        glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, commands.getDeviceAddress() + offset, drawCount * 8L);
        glMultiDrawMeshTasksIndirectNV(0, drawCount, 0);
    }

    @Override
    public void dispatchCompute(IShader shader, int x, int y, int z) {
        ((GlShader) shader).bind();
        glDispatchCompute(x, y, z);
    }
}
