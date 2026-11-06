package me.cortex.nvidium.renderers;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.resources.Identifier;

import static me.cortex.nvidium.backend.ShaderType.*;

public class SectionRasterizer extends Phase {
    private final IShader shader;

    public SectionRasterizer(IRenderBackend backend) {
        super(backend);
        this.shader = backend.createShader()
                .addSource(TASK, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "occlusion/section_raster/task.glsl")))
                .addSource(MESH, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "occlusion/section_raster/mesh.glsl")))
                .addSource(FRAGMENT, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "occlusion/section_raster/fragment.glsl"))).compile();
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
