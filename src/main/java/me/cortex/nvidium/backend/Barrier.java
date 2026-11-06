package me.cortex.nvidium.backend;

/** Backend-neutral memory barrier bits for {@link IRenderBackend#barrier(int)}. */
public final class Barrier {
    private Barrier() {}

    /** Shader storage / global memory writes (SSBO, buffer_reference stores) → any later shader read. */
    public static final int SHADER_STORAGE = 1;
    /** Shader writes → indirect command / draw parameter reads. */
    public static final int COMMAND = 1 << 1;
    /** Framebuffer attachment writes → later framebuffer reads (depth used by the occlusion raster). */
    public static final int FRAMEBUFFER = 1 << 2;
    /** Host writes to a persistently mapped buffer → GPU reads. */
    public static final int CLIENT_MAPPED_BUFFER = 1 << 3;
    /** Buffer copy / clear writes → any later read. */
    public static final int BUFFER_UPDATE = 1 << 4;
}
