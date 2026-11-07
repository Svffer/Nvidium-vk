package me.cortex.nvidium.backend.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.ShaderType;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

/**
 * Shader compile check: compiles every Vulkan entry shader through the real runtime path
 * (resource manager → {@link ShaderLoader} with the configured defines → shaderc → {@code VkShaderModule}).
 * Enabled with {@code -Dnvidium.vkShaderTest=true} (Gradle {@code -PvkShaderTest}); runs once, on the first
 * terrain render of a world, because the resource manager is not available at device creation.
 */
public final class VkShaderTest {
    private VkShaderTest() {}

    private static boolean done;

    /** Entry shaders of the Vulkan renderer; stage from the file extension. The pairs form programs. */
    private static final List<String[]> PROGRAMS = List.of(
            new String[] {"terrain", "vk/terrain/task.task", "vk/terrain/mesh.mesh", "vk/terrain/frag.frag"},
            new String[] {"temporal", "vk/terrain/temporal_task.task", "vk/terrain/mesh.mesh", "vk/terrain/frag.frag"},
            new String[] {
                "translucent",
                "vk/terrain/translucent/task.task",
                "vk/terrain/translucent/mesh.mesh",
                "vk/terrain/frag.frag"
            },
            new String[] {"region_raster", "vk/occlusion/region_raster.mesh", "vk/occlusion/region_visibility.frag"},
            new String[] {
                "section_raster",
                "vk/occlusion/section_raster.task",
                "vk/occlusion/section_raster.mesh",
                "vk/occlusion/section_visibility.frag"
            },
            new String[] {"region_query", "vk/occlusion/queries_region.mesh", "vk/occlusion/region_visibility.frag"},
            new String[] {"command_buffer_builder", "vk/occlusion/command_buffer_builder.comp"},
            new String[] {"region_section_sorter", "vk/sorting/region_section_sorter.comp"});

    public static void runOnce() {
        if (done || !Boolean.getBoolean("nvidium.vkShaderTest")) {
            return;
        }
        done = true;
        VkContext ctx = VkContext.from(RenderSystem.getDevice());
        VkDeviceCapabilities caps = VkDeviceBootstrap.capabilities();
        if (ctx == null || caps == null) {
            Nvidium.LOGGER.warn("[vk shader-test] skipped: not on the Vulkan backend");
            return;
        }
        long t0 = System.nanoTime();
        int failed = 0;
        try (VkRenderBackend backend = new VkRenderBackend(ctx, caps)) {
            for (String[] program : PROGRAMS) {
                String name = program[0];
                try {
                    var builder = (VkShader.Builder) backend.createShader();
                    builder.named(name);
                    for (int i = 1; i < program.length; i++) {
                        String path = program[i];
                        ShaderType type = stageOf(path);
                        ShaderDefines.Builder defines = ShaderDefines.builder();
                        if (name.equals("translucent") && type == ShaderType.FRAGMENT) {
                            defines.define("TRANSLUCENT_PASS");
                        }
                        builder.addSource(
                                type, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", path), defines));
                    }
                    IShader shader = builder.compile();
                    shader.delete();
                    Nvidium.LOGGER.info("[vk shader-test] ok   {}", name);
                } catch (RuntimeException e) {
                    failed++;
                    Nvidium.LOGGER.error("[vk shader-test] FAIL {}: {}", name, e.getMessage());
                }
            }
        }
        Nvidium.LOGGER.info(
                "[vk shader-test] {}: {}/{} programs compiled in {} ms",
                failed == 0 ? "PASSED" : "FAILED",
                PROGRAMS.size() - failed,
                PROGRAMS.size(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    private static ShaderType stageOf(String path) {
        if (path.endsWith(".task")) return ShaderType.TASK;
        if (path.endsWith(".mesh")) return ShaderType.MESH;
        if (path.endsWith(".frag")) return ShaderType.FRAGMENT;
        if (path.endsWith(".comp")) return ShaderType.COMPUTE;
        throw new IllegalArgumentException(path);
    }
}
