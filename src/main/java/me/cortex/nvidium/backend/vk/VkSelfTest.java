package me.cortex.nvidium.backend.vk;

import com.mojang.blaze3d.systems.GpuDevice;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.backend.Barrier;
import me.cortex.nvidium.backend.IClientMappedBuffer;
import me.cortex.nvidium.backend.IDeviceMappedBuffer;
import me.cortex.nvidium.backend.ShaderType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

/**
 * M3 exit criterion (docs/VULKAN_PLAN.md): a GPU round trip in the dev client. Enabled with
 * {@code -Dnvidium.vkSelfTest=true} (Gradle: {@code -PvkSelfTest}); runs once right after Mojang created
 * the Vulkan device, before the first frame.
 *
 * <ol>
 *   <li>fill a persistently mapped staging buffer with a pattern, flush;
 *   <li>copy it into device buffer A ({@code copyBuffer}), fill device buffer C with zeros
 *       ({@code clearBuffer});
 *   <li>compute shader reads A and C through {@code buffer_reference} device addresses passed as push
 *       constants and writes {@code A[i] ^ KEY + C[i]} into device buffer B;
 *   <li>copy B into a readback buffer, {@code encoder.submit()}, wait on a {@link VkFence};
 *   <li>compare with the CPU result.
 * </ol>
 *
 * This exercises exactly the pieces M4/M5 build on: own VMA allocator with device addresses, shaderc →
 * SPIR-V → pipeline, barriers and copies on Mojang's main command buffer, completion via the submit index,
 * deferred destruction.
 */
public final class VkSelfTest {
    private VkSelfTest() {}

    private static final int COUNT = 1 << 18; // uints, 1 MiB
    private static final int KEY = 0x5A5A_5A5A;

    private static final String SHADER = """
            #version 460
            #extension GL_EXT_buffer_reference2 : require
            #extension GL_EXT_scalar_block_layout : require
            #extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
            layout(local_size_x = 64) in;
            layout(buffer_reference, scalar, buffer_reference_align = 4) buffer UintPtr { uint data[]; };
            layout(push_constant, scalar) uniform Push {
                UintPtr src;
                UintPtr zeros;
                UintPtr dst;
                uint count;
                uint key;
            } pc;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= pc.count) return;
                pc.dst.data[i] = (pc.src.data[i] ^ pc.key) + pc.zeros.data[i];
            }
            """;

    public static void run(GpuDevice gpuDevice, VkDeviceCapabilities caps) {
        VkContext ctx = VkContext.from(gpuDevice);
        if (ctx == null) {
            Nvidium.LOGGER.error("[vk self-test] not a Vulkan device");
            return;
        }
        long t0 = System.nanoTime();
        try (VkRenderBackend backend = new VkRenderBackend(ctx, caps)) {
            boolean ok = roundTrip(backend);
            // Buffers, pipeline and the allocator itself are destroyed through the encoder's destruction
            // queue as the first frames are submitted.
            Nvidium.LOGGER.info(
                    "[vk self-test] {} in {} ms", ok ? "PASSED" : "FAILED", (System.nanoTime() - t0) / 1_000_000);
        } catch (RuntimeException e) {
            Nvidium.LOGGER.error("[vk self-test] FAILED with exception", e);
        }
    }

    private static boolean roundTrip(VkRenderBackend backend) {
        VkContext ctx = backend.context();
        long bytes = COUNT * 4L;

        IClientMappedBuffer upload = backend.createClientMappedBuffer(bytes);
        IClientMappedBuffer download = backend.createClientMappedBuffer(bytes);
        IDeviceMappedBuffer a = backend.createDeviceOnlyMappedBuffer(bytes);
        IDeviceMappedBuffer b = backend.createDeviceOnlyMappedBuffer(bytes);
        IDeviceMappedBuffer zeros = backend.createDeviceOnlyMappedBuffer(bytes);

        // 1. pattern → staging
        long src = upload.clientAddress();
        for (int i = 0; i < COUNT; i++) {
            MemoryUtil.memPutInt(src + i * 4L, pattern(i));
        }
        backend.flushMapped(upload, 0, bytes);
        backend.barrier(Barrier.CLIENT_MAPPED_BUFFER);

        // 2. staging → A, C := 0
        backend.copyBuffer(upload, a, 0, 0, bytes);
        backend.clearBuffer(zeros, 0, bytes);
        backend.barrier(Barrier.BUFFER_UPDATE);

        // 3. compute: B = (A ^ KEY) + C
        ComputeProgram program = ComputeProgram.create(ctx, "nvidium-self-test.comp", SHADER, 5 * 8);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.calloc(5 * 8);
            push.putLong(0, a.getDeviceAddress());
            push.putLong(8, zeros.getDeviceAddress());
            push.putLong(16, b.getDeviceAddress());
            push.putInt(24, COUNT);
            push.putInt(28, KEY);
            program.dispatch(ctx.commandBuffer(), push, (COUNT + 63) / 64, 1, 1);
        }
        backend.barrier(Barrier.SHADER_STORAGE | Barrier.BUFFER_UPDATE);

        // 4. B → readback, submit, wait
        backend.copyBuffer(b, download, 0, 0, bytes);
        VkFence fence = (VkFence) backend.createFence();
        long submitIndex = ctx.currentSubmitIndex();
        ctx.encoder().submit();
        boolean done = fence.await(5_000);
        fence.free();
        if (!done) {
            Nvidium.LOGGER.error("[vk self-test] submit {} did not complete within 5 s", submitIndex);
        }
        backend.invalidateMapped(download, 0, bytes);

        // 5. verify
        int mismatches = 0;
        long dst = download.clientAddress();
        for (int i = 0; i < COUNT; i++) {
            int expected = pattern(i) ^ KEY;
            int got = MemoryUtil.memGetInt(dst + i * 4L);
            if (got != expected) {
                if (mismatches < 4) {
                    Nvidium.LOGGER.error(
                            "[vk self-test] mismatch at {}: expected {} got {}",
                            i,
                            Integer.toHexString(expected),
                            Integer.toHexString(got));
                }
                mismatches++;
            }
        }
        Nvidium.LOGGER.info(
                "[vk self-test] {} uints round-tripped, {} mismatches; submit {} completed={} (completed index {})",
                COUNT,
                mismatches,
                submitIndex,
                done,
                ctx.completedSubmitIndex());
        Nvidium.LOGGER.info("[vk self-test] free device-local memory: {} MiB", backend.getAvailableDeviceMemoryMB());

        program.destroy(ctx);
        upload.delete();
        download.delete();
        a.delete();
        b.delete();
        zeros.delete();
        return done && mismatches == 0;
    }

    private static int pattern(int i) {
        return i * 0x9E37_79B9 + 0x1234_5678;
    }

    /** A compute pipeline with one push-constant range; the minimum needed here, generalised in M4/M5. */
    private record ComputeProgram(long module, long layout, long pipeline, int pushSize) {
        static ComputeProgram create(VkContext ctx, String name, String glsl, int pushSize) {
            ByteBuffer spirv;
            try (VkSpirvCompiler compiler = new VkSpirvCompiler(true)) {
                spirv = compiler.compile(name, ShaderType.COMPUTE, glsl);
            }
            long module;
            try {
                module = VkSpirvCompiler.createShaderModule(ctx.vkDevice(), spirv);
            } finally {
                MemoryUtil.memFree(spirv);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0)
                        .size(pushSize);
                VkPipelineLayoutCreateInfo layoutInfo =
                        VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(range);
                LongBuffer pLayout = stack.callocLong(1);
                VkAllocator.check(
                        VK12.vkCreatePipelineLayout(ctx.vkDevice(), layoutInfo, null, pLayout),
                        "vkCreatePipelineLayout");
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default()
                        .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(module)
                        .pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                        .sType$Default()
                        .stage(stage)
                        .layout(pLayout.get(0));
                LongBuffer pPipeline = stack.callocLong(1);
                VkAllocator.check(
                        VK12.vkCreateComputePipelines(ctx.vkDevice(), 0, pipelineInfo, null, pPipeline),
                        "vkCreateComputePipelines");
                return new ComputeProgram(module, pLayout.get(0), pPipeline.get(0), pushSize);
            }
        }

        void dispatch(org.lwjgl.vulkan.VkCommandBuffer cb, ByteBuffer push, int x, int y, int z) {
            VK12.vkCmdBindPipeline(cb, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK12.vkCmdPushConstants(cb, layout, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK12.vkCmdDispatch(cb, x, y, z);
        }

        void destroy(VkContext ctx) {
            ctx.encoder().queueForDestroy(() -> {
                VK12.vkDestroyPipeline(ctx.vkDevice(), pipeline, null);
                VK12.vkDestroyPipelineLayout(ctx.vkDevice(), layout, null);
                VK12.vkDestroyShaderModule(ctx.vkDevice(), module, null);
            });
        }
    }
}
