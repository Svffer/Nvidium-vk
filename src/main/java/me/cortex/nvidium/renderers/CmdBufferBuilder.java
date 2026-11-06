package me.cortex.nvidium.renderers;

import static me.cortex.nvidium.backend.ShaderType.COMPUTE;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.NvidiumShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;

public class CmdBufferBuilder extends Phase {
    private final IShader shader;

    public CmdBufferBuilder(IRenderBackend backend) {
        super(backend);
        this.shader = backend.createShader()
                .addSource(COMPUTE, ShaderLoader.parse(backend.shaderSource(NvidiumShader.COMMAND_BUFFER_BUILDER)))
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
