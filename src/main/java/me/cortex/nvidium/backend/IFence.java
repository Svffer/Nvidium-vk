package me.cortex.nvidium.backend;

/** A GPU completion marker inserted into the command stream; {@link #signaled()} polls without blocking. */
public interface IFence {
    boolean signaled();

    void free();
}
