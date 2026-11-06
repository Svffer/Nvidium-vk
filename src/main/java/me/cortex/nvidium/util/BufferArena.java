package me.cortex.nvidium.util;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.backend.Barrier;
import me.cortex.nvidium.backend.IDeviceMappedBuffer;
import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.ISparseBuffer;

/**
 * The terrain geometry arena. With sparse buffers (GL on Windows) it is one huge virtual buffer whose pages
 * are committed on demand. Without them (GL on Linux, Vulkan) it is a plain device buffer that starts small
 * and grows by reallocation and copy up to the configured memory limit, so that the renderer never claims
 * most of the VRAM up front (Sodium recreates the world renderer right after creating it, and on Vulkan the
 * old arena is only freed a submit later).
 */
// TODO: make it not remove and immediately deallocate the sparse pages, wait until the end of a frame to deallocate
// since committing pages is not cheap
public class BufferArena {
    /** First capacity of a growable arena (bytes), unless the memory limit is smaller. */
    private static final long INITIAL_CAPACITY = 512L << 20;

    SegmentedManager segments = new SegmentedManager();
    private final IRenderBackend backend;
    private final UploadingBufferStream uploadStream;
    private IDeviceMappedBuffer buffer;
    private long totalQuads;
    private final int vertexFormatSize;

    private final long memory_size;
    /** Bytes of the current device buffer (growable arena only). */
    private long capacity;

    public BufferArena(IRenderBackend backend, long memory, int vertexFormatSize, UploadingBufferStream uploadStream) {
        this.backend = backend;
        this.uploadStream = uploadStream;
        this.vertexFormatSize = vertexFormatSize;
        this.memory_size = memory;
        if (backend.supportsSparseBuffers()) {
            buffer = backend.createSparseBuffer(80000000000L); // Create a 80gb buffer
        } else {
            capacity = Math.min(memory, INITIAL_CAPACITY);
            buffer = backend.createDeviceOnlyMappedBuffer(capacity);
            this.segments.setLimit(capacity / (4L * this.vertexFormatSize));
        }
        // Reserve index 0
        this.allocQuads(1);
    }

    /** The current device buffer; a growable arena replaces it, so callers must not cache it across frames. */
    public IDeviceMappedBuffer buffer() {
        return buffer;
    }

    /**
     * Grows the arena so that {@code quadCount} more quads fit: pending uploads are committed (they target the
     * old buffer), the contents are copied into a buffer of at least double the size and the old one is deleted.
     * Returns false when the memory limit is reached.
     */
    private boolean grow(int quadCount) {
        long quadBytes = 4L * vertexFormatSize;
        long needed = (segments.getSize() + quadCount) * quadBytes;
        long next = Math.min(memory_size, Math.max(capacity * 2, needed));
        if (next <= capacity || needed > next) {
            return false;
        }
        uploadStream.commit();
        IDeviceMappedBuffer grown = backend.createDeviceOnlyMappedBuffer(next);
        backend.copyBuffer(buffer, grown, 0, 0, capacity);
        backend.barrier(Barrier.BUFFER_UPDATE);
        buffer.delete();
        Nvidium.LOGGER.info("Terrain arena grown from {} MiB to {} MiB", capacity >> 20, next >> 20);
        buffer = grown;
        capacity = next;
        segments.setLimit(capacity / quadBytes);
        return true;
    }

    public int allocQuads(int quadCount) {
        totalQuads += quadCount;
        int addr = (int) segments.alloc(quadCount);
        if (addr == SegmentedManager.SIZE_LIMIT && !(buffer instanceof ISparseBuffer) && grow(quadCount)) {
            addr = (int) segments.alloc(quadCount);
        }
        if (addr == SegmentedManager.SIZE_LIMIT) {
            totalQuads -= quadCount;
            return addr;
        }
        if (buffer instanceof ISparseBuffer psab) {
            psab.ensureAllocated(
                    Integer.toUnsignedLong(addr) * 4L * vertexFormatSize, quadCount * 4L * vertexFormatSize);
        }
        return addr;
    }

    public void free(int addr) {
        int count = segments.free(addr);
        totalQuads -= count;
        if (buffer instanceof ISparseBuffer psab) {
            psab.deallocate(Integer.toUnsignedLong(addr) * 4L * vertexFormatSize, count * 4L * vertexFormatSize);
        }
    }

    public long upload(UploadingBufferStream stream, int addr) {
        return stream.upload(
                buffer,
                Integer.toUnsignedLong(addr) * 4L * vertexFormatSize,
                (int) segments.getSize(addr) * 4 * vertexFormatSize);
    }

    public void delete() {
        buffer.delete();
    }

    public int getAllocatedMB() {
        if (buffer instanceof ISparseBuffer psab) {
            return (int) ((psab.getPagesCommitted() * psab.pageSize()) / (1024 * 1024));
        } else {
            return (int) (capacity / (1024 * 1024));
        }
    }

    public int getUsedMB() {
        return (int) ((totalQuads * vertexFormatSize * 4) / (1024 * 1024));
    }

    public long getMemoryUsed() {
        if (buffer instanceof ISparseBuffer psab) {
            return (psab.getPagesCommitted() * psab.pageSize());
        } else {
            return capacity;
        }
    }

    public float getFragmentation() {
        long expected = totalQuads * vertexFormatSize * 4;
        return (float) ((double) expected / getMemoryUsed());
    }

    public boolean canReuse(int addr, int quads) {
        return this.segments.getSize(addr) == quads;
    }
}
