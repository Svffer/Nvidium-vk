package me.cortex.nvidium.backend.vk;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import me.cortex.nvidium.backend.ShaderType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

/**
 * GLSL → SPIR-V → {@code VkShaderModule} with LWJGL's shaderc. Mojang's {@code GlslCompiler} cannot be
 * reused: it hard-codes the vertex/fragment kinds and patches the SPIR-V afterwards
 * (the Vulkan frame-protocol notes). Target Vulkan 1.2 / SPIR-V 1.5, which {@code GL_EXT_mesh_shader}
 * requires (≥ 1.4).
 */
public final class VkSpirvCompiler implements AutoCloseable {
    private final long compiler = Shaderc.shaderc_compiler_initialize();
    private final long options = Shaderc.shaderc_compile_options_initialize();

    public VkSpirvCompiler(boolean debugInfo) {
        Shaderc.shaderc_compile_options_set_target_env(
                options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_5);
        Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
        if (debugInfo) {
            Shaderc.shaderc_compile_options_set_generate_debug_info(options);
        }
    }

    /** Compiles {@code source}; throws with shaderc's message on failure. The caller frees the result. */
    public ByteBuffer compile(String name, ShaderType type, String source) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long result = Shaderc.shaderc_compile_into_spv(
                    compiler,
                    stack.UTF8(source, false),
                    shadercKind(type),
                    stack.UTF8(name),
                    stack.UTF8("main"),
                    options);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result)
                        != Shaderc.shaderc_compilation_status_success) {
                    throw new IllegalStateException("nvidium: shader " + name + " failed to compile:\n"
                            + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
                copy.put(spirv).flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        }
    }

    public static long createShaderModule(VkDevice device, ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo info =
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            LongBuffer pModule = stack.callocLong(1);
            VkAllocator.check(VK12.vkCreateShaderModule(device, info, null, pModule), "vkCreateShaderModule");
            return pModule.get(0);
        }
    }

    public static int vkStage(ShaderType type) {
        return switch (type) {
            case VERTEX -> VK12.VK_SHADER_STAGE_VERTEX_BIT;
            case FRAGMENT -> VK12.VK_SHADER_STAGE_FRAGMENT_BIT;
            case COMPUTE -> VK12.VK_SHADER_STAGE_COMPUTE_BIT;
            case MESH -> EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
            case TASK -> EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
        };
    }

    private static int shadercKind(ShaderType type) {
        return switch (type) {
            case VERTEX -> Shaderc.shaderc_vertex_shader;
            case FRAGMENT -> Shaderc.shaderc_fragment_shader;
            case COMPUTE -> Shaderc.shaderc_compute_shader;
            case MESH -> Shaderc.shaderc_mesh_shader;
            case TASK -> Shaderc.shaderc_task_shader;
        };
    }

    @Override
    public void close() {
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);
    }
}
