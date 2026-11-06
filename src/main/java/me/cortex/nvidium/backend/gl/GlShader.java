package me.cortex.nvidium.backend.gl;

import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL43C.GL_COMPUTE_SHADER;
import static org.lwjgl.opengl.NVMeshShader.GL_MESH_SHADER_NV;
import static org.lwjgl.opengl.NVMeshShader.GL_TASK_SHADER_NV;

import java.util.HashMap;
import java.util.Map;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.IShaderBuilder;
import me.cortex.nvidium.backend.IShaderProcessor;
import me.cortex.nvidium.backend.ShaderType;
import org.lwjgl.opengl.GL20C;

public class GlShader extends GlObject implements IShader {
    private GlShader(int program) {
        super(program);
    }

    public void bind() {
        glUseProgram(id);
    }

    @Override
    public void delete() {
        super.free0();
        glDeleteProgram(id);
    }

    @Override
    public void free() {
        this.delete();
    }

    static int glType(ShaderType type) {
        return switch (type) {
            case VERTEX -> GL_VERTEX_SHADER;
            case FRAGMENT -> GL_FRAGMENT_SHADER;
            case COMPUTE -> GL_COMPUTE_SHADER;
            case MESH -> GL_MESH_SHADER_NV;
            case TASK -> GL_TASK_SHADER_NV;
        };
    }

    public static class Builder implements IShaderBuilder {
        private final Map<ShaderType, String> sources = new HashMap<>();
        private final IShaderProcessor processor;

        Builder(IShaderProcessor processor) {
            this.processor = processor;
        }

        @Override
        public Builder addSource(ShaderType type, String source) {
            sources.put(type, processor.process(type, source));
            return this;
        }

        @Override
        public GlShader compile() {
            int program = GL20C.glCreateProgram();
            int[] shaders = sources.entrySet().stream()
                    .mapToInt(a -> createShader(a.getKey(), a.getValue()))
                    .toArray();

            for (int i : shaders) {
                GL20C.glAttachShader(program, i);
            }
            GL20C.glLinkProgram(program);
            for (int i : shaders) {
                GL20C.glDetachShader(program, i);
                GL20C.glDeleteShader(i);
            }
            printProgramLinkLog(program);
            verifyProgramLinked(program);
            return new GlShader(program);
        }

        private static void printProgramLinkLog(int program) {
            String log = GL20C.glGetProgramInfoLog(program);

            if (!log.isEmpty()) {
                System.err.println(log);
            }
        }

        private static void verifyProgramLinked(int program) {
            int result = GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS);

            if (result != GL20C.GL_TRUE) {
                throw new RuntimeException("Shader program linking failed, see log for details");
            }
        }

        private static int createShader(ShaderType type, String src) {
            int shader = GL20C.glCreateShader(glType(type));
            GL20C.glShaderSource(shader, src);
            GL20C.glCompileShader(shader);
            String log = GL20C.glGetShaderInfoLog(shader);

            if (!log.isEmpty()) {
                System.err.println(log);
            }

            int result = GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS);

            if (result != GL20C.GL_TRUE) {
                GL20C.glDeleteShader(shader);

                throw new RuntimeException("Shader compilation failed, see log for details");
            }

            return shader;
        }
    }
}
