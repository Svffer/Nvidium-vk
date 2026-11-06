package me.cortex.nvidium.renderers;

import static me.cortex.nvidium.backend.ShaderType.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.cortex.nvidium.backend.IDeviceMappedBuffer;
import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.NvidiumShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.Minecraft;

public class PrimaryTerrainRasterizer extends Phase {
    private final IShader shader;

    public PrimaryTerrainRasterizer(IRenderBackend backend) {
        super(backend);
        this.shader = backend.createShader()
                .addSource(TASK, ShaderLoader.parse(backend.shaderSource(NvidiumShader.TERRAIN_TASK)))
                .addSource(MESH, ShaderLoader.parse(backend.shaderSource(NvidiumShader.TERRAIN_MESH)))
                .addSource(FRAGMENT, ShaderLoader.parse(backend.shaderSource(NvidiumShader.TERRAIN_FRAG)))
                .compile();
    }

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
    }

    public void delete() {
        super.delete();
        shader.delete();
    }
}
