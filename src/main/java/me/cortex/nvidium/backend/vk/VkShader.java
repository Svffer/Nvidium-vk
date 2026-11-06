package me.cortex.nvidium.backend.vk;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.IShaderBuilder;
import me.cortex.nvidium.backend.IShaderProcessor;
import me.cortex.nvidium.backend.ShaderType;
import me.cortex.nvidium.backend.TrackedObject;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;

/**
 * nvidium's Vulkan "program": one {@code VkShaderModule} per stage, compiled from GLSL with shaderc at
 * construction. The {@code VkPipeline}s that use these modules depend on render state (depth, blend,
 * attachments) and are built by the backend when a draw or dispatch first needs them (plan M5); a
 * program with a COMPUTE stage is a compute pipeline, TASK/MESH/FRAGMENT form a graphics pipeline.
 */
public final class VkShader extends TrackedObject implements IShader {
    private final VkContext ctx;
    private final Map<ShaderType, Long> modules;
    private final String name;

    private VkShader(VkContext ctx, String name, Map<ShaderType, Long> modules) {
        this.ctx = ctx;
        this.name = name;
        this.modules = modules;
    }

    public long module(ShaderType type) {
        Long m = modules.get(type);
        if (m == null) {
            throw new IllegalStateException("shader " + name + " has no " + type + " stage");
        }
        return m;
    }

    public boolean hasStage(ShaderType type) {
        return modules.containsKey(type);
    }

    public boolean isCompute() {
        return modules.containsKey(ShaderType.COMPUTE);
    }

    public String name() {
        return name;
    }

    @Override
    public void delete() {
        super.free0();
        Map<ShaderType, Long> toDestroy = new EnumMap<>(modules);
        ctx.encoder().queueForDestroy(() -> {
            for (long module : toDestroy.values()) {
                VK12.vkDestroyShaderModule(ctx.vkDevice(), module, null);
            }
        });
    }

    @Override
    public void free() {
        delete();
    }

    static final class Builder implements IShaderBuilder {
        private final VkContext ctx;
        private final VkSpirvCompiler compiler;
        private final IShaderProcessor processor;
        private final Map<ShaderType, String> sources = new EnumMap<>(ShaderType.class);
        private String name = "nvidium";

        Builder(VkContext ctx, VkSpirvCompiler compiler, IShaderProcessor processor) {
            this.ctx = ctx;
            this.compiler = compiler;
            this.processor = processor;
        }

        /** Optional label used in compile errors and debug tooling. */
        public Builder named(String name) {
            this.name = name;
            return this;
        }

        @Override
        public Builder addSource(ShaderType type, String source) {
            sources.put(type, processor.process(type, source));
            return this;
        }

        @Override
        public VkShader compile() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("shader " + name + " has no stages");
            }
            Map<ShaderType, Long> modules = new EnumMap<>(ShaderType.class);
            try {
                for (Map.Entry<ShaderType, String> e : sources.entrySet()) {
                    ByteBuffer spirv = compiler.compile(
                            name + "." + e.getKey().name().toLowerCase(java.util.Locale.ROOT),
                            e.getKey(),
                            e.getValue());
                    try {
                        modules.put(e.getKey(), VkSpirvCompiler.createShaderModule(ctx.vkDevice(), spirv));
                    } finally {
                        MemoryUtil.memFree(spirv);
                    }
                }
            } catch (RuntimeException ex) {
                for (long module : modules.values()) {
                    VK12.vkDestroyShaderModule(ctx.vkDevice(), module, null);
                }
                throw ex;
            }
            return new VkShader(ctx, name, modules);
        }
    }
}
