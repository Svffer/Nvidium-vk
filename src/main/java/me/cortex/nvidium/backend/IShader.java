package me.cortex.nvidium.backend;

/**
 * A compiled, linked GPU program (GL: program object; Vulkan: pipeline). Shaders are never bound
 * directly: they are passed to the draw / dispatch methods of {@link IRenderBackend}.
 */
public interface IShader extends IResource {}
