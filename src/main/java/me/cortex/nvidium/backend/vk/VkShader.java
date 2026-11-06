package me.cortex.nvidium.backend.vk;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
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
 * construction, plus the {@code VkPipeline}s built from them on first use. A program with a COMPUTE stage
 * has one compute pipeline; TASK/MESH/FRAGMENT programs get one graphics pipeline per
 * {@link VkPipelineFactory.GraphicsState} they are drawn in (plan M5). Everything shares the factory's
 * pipeline layout.
 */
public final class VkShader extends TrackedObject implements IShader {
    private final VkContext ctx;
    private final VkPipelineFactory factory;
    private final Map<ShaderType, Long> modules;
    private final Map<VkPipelineFactory.GraphicsState, Long> graphicsPipelines = new HashMap<>();
    private long computePipeline;
    private final String name;

    private VkShader(VkContext ctx, VkPipelineFactory factory, String name, Map<ShaderType, Long> modules) {
        this.ctx = ctx;
        this.factory = factory;
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

    long graphicsPipeline(VkPipelineFactory.GraphicsState state) {
        assertNotFreed();
        Long pipeline = graphicsPipelines.get(state);
        if (pipeline == null) {
            pipeline = factory.createGraphics(this, state);
            graphicsPipelines.put(state, pipeline);
        }
        return pipeline;
    }

    long computePipeline() {
        assertNotFreed();
        if (computePipeline == 0) {
            computePipeline = factory.createCompute(this);
        }
        return computePipeline;
    }

    @Override
    public void delete() {
        super.free0();
        List<Long> pipelines = new ArrayList<>(graphicsPipelines.values());
        if (computePipeline != 0) {
            pipelines.add(computePipeline);
        }
        graphicsPipelines.clear();
        computePipeline = 0;
        Map<ShaderType, Long> toDestroy = new EnumMap<>(modules);
        ctx.encoder().queueForDestroy(() -> {
            for (long pipeline : pipelines) {
                VK12.vkDestroyPipeline(ctx.vkDevice(), pipeline, null);
            }
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
        private final VkPipelineFactory factory;
        private final VkSpirvCompiler compiler;
        private final IShaderProcessor processor;
        private final Map<ShaderType, String> sources = new EnumMap<>(ShaderType.class);
        private String name = "nvidium";

        Builder(VkContext ctx, VkPipelineFactory factory, VkSpirvCompiler compiler, IShaderProcessor processor) {
            this.ctx = ctx;
            this.factory = factory;
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
            return new VkShader(ctx, factory, name, modules);
        }
    }
}
