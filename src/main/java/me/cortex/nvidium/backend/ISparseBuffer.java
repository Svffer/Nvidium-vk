package me.cortex.nvidium.backend;

/**
 * A large virtually-reserved device buffer whose physical pages are committed on demand.
 * Only used when {@link IRenderBackend#supportsSparseBuffers()} is true.
 */
public interface ISparseBuffer extends IDeviceMappedBuffer {
    long pageSize();

    void ensureAllocated(long addr, long size);

    void deallocate(long addr, long size);

    int getPagesCommitted();
}
