package me.cortex.nvidium.backend.vk;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import me.cortex.nvidium.backend.ShaderType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineRepresentativeFragmentTestStateCreateInfoNV;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;

/**
 * Pipeline layout and pipeline creation for nvidium's Vulkan shaders (plan M5).
 *
 * <p>All programs share one pipeline layout with a single push-descriptor set, fixed by the M4 shaders:
 * binding 0 block atlas, 1 lightmap (combined image samplers), 2 scene UBO. Descriptors are pushed before
 * every draw and dispatch, because Mojang binds its own, incompatible layouts in between. There are no push
 * constants: every buffer is reached through the 64-bit addresses in the scene block.
 *
 * <p>Graphics pipelines are mesh pipelines (task optional + mesh + fragment) with the fixed function state the
 * GL backend sets through {@code GlStateManager}: reverse-Z depth test, back-face culling with Mojang's
 * clockwise front face (Mojang renders with GL conventions into a y-flipped image and flips at present),
 * one colour attachment. {@link GraphicsState} enumerates the variants; attachment formats come from the live
 * render target, so the same program can draw into any target Sodium hands over.
 */
final class VkPipelineFactory implements AutoCloseable {
    static final int BINDING_ATLAS = 0;
    static final int BINDING_LIGHTMAP = 1;
    static final int BINDING_SCENE = 2;

    private static final int ALL_STAGES = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT
            | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT
            | VK12.VK_SHADER_STAGE_FRAGMENT_BIT
            | VK12.VK_SHADER_STAGE_COMPUTE_BIT;

    /**
     * Fixed-function variant of a graphics pipeline.
     *
     * @param colourFormat {@code VkFormat} of the colour attachment
     * @param depthFormat {@code VkFormat} of the depth attachment (stencil aspect, if any, is not attached)
     * @param blend translucent layer: {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}, alpha {@code ONE / ONE_MINUS_SRC_ALPHA}
     * @param occlusion occlusion raster: depth test without depth or colour writes, representative fragment
     *     test when the device has it
     */
    record GraphicsState(int colourFormat, int depthFormat, boolean blend, boolean occlusion) {}

    private final VkContext ctx;
    private final VkDeviceCapabilities capabilities;
    private final long descriptorSetLayout;
    private final long pipelineLayout;

    VkPipelineFactory(VkContext ctx, VkDeviceCapabilities capabilities) {
        this.ctx = ctx;
        this.capabilities = capabilities;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            bindings.get(BINDING_ATLAS)
                    .binding(BINDING_ATLAS)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(ALL_STAGES);
            bindings.get(BINDING_LIGHTMAP)
                    .binding(BINDING_LIGHTMAP)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(ALL_STAGES);
            bindings.get(BINDING_SCENE)
                    .binding(BINDING_SCENE)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(ALL_STAGES);
            VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(bindings);
            LongBuffer pSetLayout = stack.callocLong(1);
            VkAllocator.check(
                    VK12.vkCreateDescriptorSetLayout(ctx.vkDevice(), setInfo, null, pSetLayout),
                    "vkCreateDescriptorSetLayout");
            this.descriptorSetLayout = pSetLayout.get(0);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pLayout = stack.callocLong(1);
            VkAllocator.check(
                    VK12.vkCreatePipelineLayout(ctx.vkDevice(), layoutInfo, null, pLayout), "vkCreatePipelineLayout");
            this.pipelineLayout = pLayout.get(0);
        }
    }

    long pipelineLayout() {
        return pipelineLayout;
    }

    private static VkPipelineShaderStageCreateInfo.Buffer stages(
            MemoryStack stack, VkShader shader, List<ShaderType> types) {
        List<ShaderType> present = new ArrayList<>();
        for (ShaderType t : types) {
            if (shader.hasStage(t)) {
                present.add(t);
            }
        }
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(present.size(), stack);
        for (int i = 0; i < present.size(); i++) {
            stages.get(i)
                    .sType$Default()
                    .stage(VkSpirvCompiler.vkStage(present.get(i)))
                    .module(shader.module(present.get(i)))
                    .pName(stack.UTF8("main"));
        }
        return stages;
    }

    /** Mesh pipeline for a TASK/MESH/FRAGMENT program in the given fixed-function state. */
    long createGraphics(VkShader shader, GraphicsState state) {
        if (!shader.hasStage(ShaderType.MESH) || !shader.hasStage(ShaderType.FRAGMENT)) {
            throw new IllegalStateException("shader " + shader.name() + " is not a mesh+fragment program");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages =
                    stages(stack, shader, List.of(ShaderType.TASK, ShaderType.MESH, ShaderType.FRAGMENT));

            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1)
                    .scissorCount(1);
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .polygonMode(VK12.VK_POLYGON_MODE_FILL)
                    .cullMode(VK12.VK_CULL_MODE_BACK_BIT)
                    .frontFace(VK12.VK_FRONT_FACE_CLOCKWISE)
                    .lineWidth(1.0f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK12.VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(true)
                    .depthWriteEnable(!state.occlusion())
                    .depthCompareOp(VK12.VK_COMPARE_OP_GREATER_OR_EQUAL); // reverse-Z, as the GL backend

            VkPipelineColorBlendAttachmentState.Buffer attachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            attachment.colorWriteMask(
                    state.occlusion()
                            ? 0
                            : VK12.VK_COLOR_COMPONENT_R_BIT
                                    | VK12.VK_COLOR_COMPONENT_G_BIT
                                    | VK12.VK_COLOR_COMPONENT_B_BIT
                                    | VK12.VK_COLOR_COMPONENT_A_BIT);
            if (state.blend()) {
                attachment
                        .blendEnable(true)
                        .srcColorBlendFactor(VK12.VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK12.VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK12.VK_BLEND_OP_ADD);
            }
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachments(attachment);

            // Like Mojang: only viewport and scissor are dynamic; nvidium sets both when it begins rendering.
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(VK12.VK_DYNAMIC_STATE_VIEWPORT, VK12.VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .pColorAttachmentFormats(stack.ints(state.colourFormat()))
                    .depthAttachmentFormat(state.depthFormat());
            long pNext = rendering.address();
            if (state.occlusion() && capabilities.representativeFragmentTest()) {
                // One fragment per primitive is enough: the fragment shader only sets a visibility byte.
                VkPipelineRepresentativeFragmentTestStateCreateInfoNV representative =
                        VkPipelineRepresentativeFragmentTestStateCreateInfoNV.calloc(stack)
                                .sType$Default()
                                .representativeFragmentTestEnable(true)
                                .pNext(pNext);
                pNext = representative.address();
            }

            // Mesh pipelines have no vertex input or input assembly state.
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pNext(pNext)
                    .pStages(stages)
                    .pViewportState(viewport)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depth)
                    .pColorBlendState(blend)
                    .pDynamicState(dynamic)
                    .layout(pipelineLayout);
            LongBuffer pPipeline = stack.callocLong(1);
            VkAllocator.check(
                    VK12.vkCreateGraphicsPipelines(ctx.vkDevice(), 0, info, null, pPipeline),
                    "vkCreateGraphicsPipelines(" + shader.name() + ", " + state + ")");
            return pPipeline.get(0);
        }
    }

    /** Compute pipeline for a COMPUTE program. */
    long createCompute(VkShader shader) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = stages(stack, shader, List.of(ShaderType.COMPUTE));
            if (stages.remaining() != 1) {
                throw new IllegalStateException("shader " + shader.name() + " is not a compute program");
            }
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .stage(stages.get(0))
                    .layout(pipelineLayout);
            LongBuffer pPipeline = stack.callocLong(1);
            VkAllocator.check(
                    VK12.vkCreateComputePipelines(ctx.vkDevice(), 0, info, null, pPipeline),
                    "vkCreateComputePipelines(" + shader.name() + ")");
            return pPipeline.get(0);
        }
    }

    /** Pipelines made by this factory must be destroyed first; they are queued the same way, so FIFO order holds. */
    @Override
    public void close() {
        ctx.encoder().queueForDestroy(() -> {
            VK12.vkDestroyPipelineLayout(ctx.vkDevice(), pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(ctx.vkDevice(), descriptorSetLayout, null);
        });
    }
}
