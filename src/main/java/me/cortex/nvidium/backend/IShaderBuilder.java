package me.cortex.nvidium.backend;

public interface IShaderBuilder {
    IShaderBuilder addSource(ShaderType type, String source);

    /** Optional label for compile errors and GPU debug tooling; backends may ignore it. */
    default IShaderBuilder named(String name) {
        return this;
    }

    IShader compile();
}
