package me.cortex.nvidium.backend.gl.buffers;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCreateBuffers;
import static org.lwjgl.opengl.ARBDirectStateAccess.glNamedBufferStorage;
import static org.lwjgl.opengl.GL15C.GL_READ_WRITE;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.NVShaderBufferLoad.*;

import me.cortex.nvidium.backend.IDeviceMappedBuffer;
import me.cortex.nvidium.backend.gl.GlObject;

/** Immutable device-local storage made resident via {@code NV_shader_buffer_load}. */
public class GlDeviceOnlyBuffer extends GlObject implements IDeviceMappedBuffer {
    public final long size;
    public final long addr;

    public GlDeviceOnlyBuffer(long size) {
        super(glCreateBuffers());
        this.size = size;
        glNamedBufferStorage(id, size, 0);
        long[] holder = new long[1];
        glGetNamedBufferParameterui64vNV(id, GL_BUFFER_GPU_ADDRESS_NV, holder);
        glMakeNamedBufferResidentNV(id, GL_READ_WRITE);
        addr = holder[0];
        if (addr == 0) {
            throw new IllegalStateException();
        }
    }

    @Override
    public void delete() {
        super.free0();
        glMakeNamedBufferNonResidentNV(id);
        glDeleteBuffers(id);
    }

    @Override
    public long getDeviceAddress() {
        return addr;
    }

    @Override
    public void free() {
        this.delete();
    }

    @Override
    public long getSize() {
        return this.size;
    }
}
