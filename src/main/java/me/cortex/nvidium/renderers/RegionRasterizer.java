package me.cortex.nvidium.renderers;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.resources.Identifier;

import static me.cortex.nvidium.backend.ShaderType.FRAGMENT;
import static me.cortex.nvidium.backend.ShaderType.MESH;

public class RegionRasterizer extends Phase {
    private final IShader shader;

    public RegionRasterizer(IRenderBackend backend) {
        super(backend);
        this.shader = backend.createShader()
                .addSource(MESH, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "occlusion/region_raster/mesh.glsl")))
                .addSource(FRAGMENT, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "occlusion/region_raster/fragment.frag")))
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
