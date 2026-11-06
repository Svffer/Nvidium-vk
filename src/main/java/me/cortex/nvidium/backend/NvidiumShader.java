package me.cortex.nvidium.backend;

import net.minecraft.resources.Identifier;

/**
 * The shader sources of the renderer, with the resource path of each backend's version (relative to
 * {@code assets/nvidium/shaders/}). The GL files are NVIDIA GLSL (NV_mesh_shader, bindless, pointers);
 * the {@code vk/} files are the GL_EXT_mesh_shader / buffer_reference2 translation (plan M4).
 */
public enum NvidiumShader {
    TERRAIN_TASK("terrain/task.glsl", "vk/terrain/task.task", ShaderType.TASK),
    TERRAIN_MESH("terrain/mesh.glsl", "vk/terrain/mesh.mesh", ShaderType.MESH),
    TERRAIN_FRAG("terrain/frag.frag", "vk/terrain/frag.frag", ShaderType.FRAGMENT),
    TEMPORAL_TASK("terrain/temporal_task.glsl", "vk/terrain/temporal_task.task", ShaderType.TASK),
    TRANSLUCENT_TASK("terrain/translucent/task.glsl", "vk/terrain/translucent/task.task", ShaderType.TASK),
    TRANSLUCENT_MESH("terrain/translucent/mesh.glsl", "vk/terrain/translucent/mesh.mesh", ShaderType.MESH),
    REGION_RASTER_MESH("occlusion/region_raster/mesh.glsl", "vk/occlusion/region_raster.mesh", ShaderType.MESH),
    REGION_RASTER_FRAG(
            "occlusion/region_raster/fragment.frag", "vk/occlusion/region_visibility.frag", ShaderType.FRAGMENT),
    SECTION_RASTER_TASK("occlusion/section_raster/task.glsl", "vk/occlusion/section_raster.task", ShaderType.TASK),
    SECTION_RASTER_MESH("occlusion/section_raster/mesh.glsl", "vk/occlusion/section_raster.mesh", ShaderType.MESH),
    SECTION_RASTER_FRAG(
            "occlusion/section_raster/fragment.glsl", "vk/occlusion/section_visibility.frag", ShaderType.FRAGMENT),
    REGION_QUERY_MESH("occlusion/queries/region/mesh.glsl", "vk/occlusion/queries_region.mesh", ShaderType.MESH),
    REGION_QUERY_FRAG(
            "occlusion/queries/region/fragment.frag", "vk/occlusion/region_visibility.frag", ShaderType.FRAGMENT),
    COMMAND_BUFFER_BUILDER(
            "occlusion/command_buffer/command_buffer_builder.comp",
            "vk/occlusion/command_buffer_builder.comp",
            ShaderType.COMPUTE),
    REGION_SECTION_SORTER(
            "sorting/region_section_sorter.comp", "vk/sorting/region_section_sorter.comp", ShaderType.COMPUTE);

    public final String glPath;
    public final String vkPath;
    public final ShaderType stage;

    NvidiumShader(String glPath, String vkPath, ShaderType stage) {
        this.glPath = glPath;
        this.vkPath = vkPath;
        this.stage = stage;
    }

    public Identifier gl() {
        return Identifier.fromNamespaceAndPath("nvidium", glPath);
    }

    public Identifier vk() {
        return Identifier.fromNamespaceAndPath("nvidium", vkPath);
    }
}
