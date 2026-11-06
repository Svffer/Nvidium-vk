package me.cortex.nvidium.backend.gl;

import me.cortex.nvidium.backend.IResource;
import me.cortex.nvidium.backend.TrackedObject;

public abstract class GlObject extends TrackedObject implements IResource {
    protected final int id;

    protected GlObject(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
