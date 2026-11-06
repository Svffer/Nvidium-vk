package me.cortex.nvidium.renderers;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.resources.Identifier;

import static me.cortex.nvidium.backend.ShaderType.COMPUTE;

public class CmdBufferBuilder extends Phase {
    private final IShader shader;

    public CmdBufferBuilder(IRenderBackend backend) {
        super(backend);
        this.shader = backend.createShader()
                .addSource(COMPUTE, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "occlusion/command_buffer/command_buffer_builder.comp")))
                .compile();
    }

    public void dispatch(int regionCount) {
        timing.marker();
        backend.dispatchCompute(shader, regionCount, 1, 1);
        timing.marker();
        timing.tick();
    }

    @Override
    public void delete() {
        super.delete();
        shader.delete();
    }
}
