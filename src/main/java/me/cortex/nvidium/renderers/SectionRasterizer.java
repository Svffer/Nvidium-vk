package me.cortex.nvidium.renderers;

import static me.cortex.nvidium.backend.ShaderType.*;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.NvidiumShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;

public class SectionRasterizer extends Phase {
    private final IShader shader;

    public SectionRasterizer(IRenderBackend backend) {
        super(backend);
        this.shader = backend.createShader()
                .addSource(TASK, ShaderLoader.parse(backend.shaderSource(NvidiumShader.SECTION_RASTER_TASK)))
                .addSource(MESH, ShaderLoader.parse(backend.shaderSource(NvidiumShader.SECTION_RASTER_MESH)))
                .addSource(FRAGMENT, ShaderLoader.parse(backend.shaderSource(NvidiumShader.SECTION_RASTER_FRAG)))
                .compile();
    }

    public void raster(int regionCount) {
        timing.marker();
        backend.drawMeshTasks(shader, 0, regionCount);
        timing.marker();
        timing.tick();
    }

    public void delete() {
        super.delete();
        shader.delete();
    }
}
