package me.cortex.nvidium.backend;

public interface IShaderBuilder {
    IShaderBuilder addSource(ShaderType type, String source);
    IShader compile();
}
