package me.cortex.nvidium.renderers;

import static me.cortex.nvidium.backend.ShaderType.FRAGMENT;
import static me.cortex.nvidium.backend.ShaderType.MESH;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.NvidiumShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;

public class RegionRasterizer extends Phase {
    private final IShader shader;

    public RegionRasterizer(IRenderBackend backend) {
        super(backend);
        this.shader = backend.createShader()
                .addSource(MESH, ShaderLoader.parse(backend.shaderSource(NvidiumShader.REGION_RASTER_MESH)))
                .addSource(FRAGMENT, ShaderLoader.parse(backend.shaderSource(NvidiumShader.REGION_RASTER_FRAG)))
                .compile();
    }

    public void raster(int regionCount) {
        timing.marker();
        backend.drawMeshTasks(shader, 0, (regionCount + 3) / 4);
        timing.marker();
        timing.tick();
    }

    public void delete() {
        super.delete();
        shader.delete();
    }
}
