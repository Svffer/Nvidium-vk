package me.cortex.nvidium.renderers;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.util.GPUTiming;

public abstract class Phase {
    protected final IRenderBackend backend;
    GPUTiming timing;

    protected Phase(IRenderBackend backend) {
        this.backend = backend;
        this.timing = new GPUTiming(backend);
    }

    public void delete() {
        timing.free();
    }

    public GPUTiming getTiming() {
        return timing;
    }
}
