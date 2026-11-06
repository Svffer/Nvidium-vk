package me.cortex.nvidium.backend;

public interface IShaderProcessor {
    String process(ShaderType type, String source);
}
