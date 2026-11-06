package me.cortex.nvidium.renderers;

import static me.cortex.nvidium.backend.ShaderType.COMPUTE;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.NvidiumShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;

public class SortRegionSectionPhase extends Phase {
    private final IShader shader;

    public SortRegionSectionPhase(IRenderBackend backend) {
        super(backend);
        this.shader = backend.createShader()
                .addSource(COMPUTE, ShaderLoader.parse(backend.shaderSource(NvidiumShader.REGION_SECTION_SORTER)))
                .compile();
    }

    public void dispatch(int sortingRegionCount) {
        timing.marker();
        backend.dispatchCompute(shader, sortingRegionCount, 1, 1);
        timing.marker();
        timing.tick();
    }

    public void delete() {
        super.delete();
        shader.delete();
    }
}
