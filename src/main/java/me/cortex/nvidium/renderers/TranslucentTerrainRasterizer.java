package me.cortex.nvidium.renderers;

import static me.cortex.nvidium.backend.ShaderType.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.cortex.nvidium.backend.IDeviceMappedBuffer;
import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

public class TranslucentTerrainRasterizer extends Phase {
    private final IShader shader;

    public TranslucentTerrainRasterizer(IRenderBackend backend) {
        super(backend);
        this.shader = backend.createShader()
                .addSource(
                        TASK,
                        ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/task.glsl")))
                .addSource(
                        MESH,
                        ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/translucent/mesh.glsl")))
                .addSource(
                        FRAGMENT,
                        ShaderLoader.parse(
                                Identifier.fromNamespaceAndPath("nvidium", "terrain/frag.frag"),
                                ShaderDefines.builder().define("TRANSLUCENT_PASS")))
                .compile();
    }

    // Translucency is rendered in a very cursed and incorrect way
    // it hijacks the unassigned indirect command dispatch and uses that to dispatch the translucent chunks as well
    public void raster(
            TerrainRenderPass pass, int regionCount, IDeviceMappedBuffer commandBuffer, GpuSampler terrainSampler) {
        GpuTextureView blockTexture = pass.getAtlas();
        GpuTextureView lightTexture = Minecraft.getInstance().gameRenderer.lightmap();

        backend.bindTexture(0, blockTexture, terrainSampler);
        backend.bindTexture(1, lightTexture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

        timing.marker();
        backend.drawMeshTasksIndirect(shader, commandBuffer, 0, regionCount);
        timing.marker();
        timing.tick();

        backend.unbindTexture(0);
        backend.unbindTexture(1);
    }

    public void delete() {
        super.delete();
        shader.delete();
    }
}
