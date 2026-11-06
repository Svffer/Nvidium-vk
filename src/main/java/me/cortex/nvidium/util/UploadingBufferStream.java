package me.cortex.nvidium.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.nvidium.backend.Barrier;
import me.cortex.nvidium.backend.IBuffer;
import me.cortex.nvidium.backend.IClientMappedBuffer;
import me.cortex.nvidium.backend.IFence;
import me.cortex.nvidium.backend.IRenderBackend;

import java.util.ArrayDeque;
import java.util.Deque;

import static me.cortex.nvidium.util.SegmentedManager.SIZE_LIMIT;

/**
 * Host → device streaming through one persistently mapped staging buffer. Allocations are
 * recycled once the fence inserted at the end of their frame has signaled.
 */
public class UploadingBufferStream {
    private final IRenderBackend backend;
    private final SegmentedManager allocationArena = new SegmentedManager();
    private final IClientMappedBuffer uploadBuffer;

    private final Deque<UploadFrame> frames = new ArrayDeque<>();
    private final LongArrayList thisFrameAllocations = new LongArrayList();
    private final Deque<UploadData> uploadList = new ArrayDeque<>();
    private final LongArrayList flushList = new LongArrayList();

    public UploadingBufferStream(IRenderBackend backend, long size) {
        this.backend = backend;
        this.allocationArena.setLimit(size);
        this.uploadBuffer = backend.createClientMappedBuffer(size);
        TickableManager.register(this);
    }

    private long caddr = -1;
    private long offset = 0;
    public long upload(IBuffer buffer, long destOffset, long size) {
        if (size > Integer.MAX_VALUE || size == 0 || size < 0) {
            throw new IllegalArgumentException();
        }
        if (destOffset < 0) {
            throw new IllegalStateException();
        }
        if (destOffset+size > buffer.getSize()) {
            throw new IllegalStateException();
        }

        long addr;
        if (this.caddr == -1 || !this.allocationArena.expand(this.caddr, (int) size)) {
            this.caddr = this.allocationArena.alloc((int) size);
            //If the upload stream is full, flush it and empty it
            if (this.caddr == SIZE_LIMIT) {
                this.commit();
                int attempts = 10;
                while (--attempts != 0 && this.caddr == SIZE_LIMIT) {
                    this.backend.finish();
                    this.tick();
                    this.caddr = this.allocationArena.alloc((int) size);
                }
                if (this.caddr == SIZE_LIMIT) {
                    throw new IllegalStateException("Could not allocate memory segment big enough for upload even after force flush");
                }
            }
            this.flushList.add(this.caddr);
            this.offset = size;
            addr = this.caddr;
        } else {//Could expand the allocation so just update it
            addr = this.caddr + this.offset;
            this.offset += size;
        }

        if (this.caddr + size > this.uploadBuffer.getSize()) {
            throw new IllegalStateException();
        }

        this.uploadList.add(new UploadData(buffer, addr, destOffset, size));

        return this.uploadBuffer.clientAddress() + addr;
    }


    public void commit() {
        //First flush all the allocations and enqueue them to be freed
        {
            for (long alloc : flushList) {
                this.backend.flushMapped(this.uploadBuffer, alloc, this.allocationArena.getSize(alloc));
                this.thisFrameAllocations.add(alloc);
            }
            this.flushList.clear();
        }
        this.backend.barrier(Barrier.CLIENT_MAPPED_BUFFER);
        //Execute all the copies
        for (var entry : this.uploadList) {
            this.backend.copyBuffer(this.uploadBuffer, entry.target, entry.uploadOffset, entry.targetOffset, entry.size);
        }
        this.uploadList.clear();

        this.backend.barrier(Barrier.BUFFER_UPDATE);

        this.caddr = -1;
        this.offset = 0;
    }

    public void tick() {
        this.commit();
        if (!this.thisFrameAllocations.isEmpty()) {
            this.frames.add(new UploadFrame(this.backend.createFence(), new LongArrayList(this.thisFrameAllocations)));
            this.thisFrameAllocations.clear();
        }

        while (!this.frames.isEmpty()) {
            //Since the ordering of frames is the ordering of the gl commands if we encounter an unsignaled fence
            // all the other fences should also be unsignaled
            if (!this.frames.peek().fence.signaled()) {
                break;
            }
            //Release all the allocations from the frame
            var frame = this.frames.pop();
            frame.allocations.forEach(allocationArena::free);
            frame.fence.free();
        }
    }

    public void delete() {
        TickableManager.remove(this);
        this.uploadBuffer.delete();
        this.frames.forEach(frame->frame.fence.free());
    }

    private record UploadFrame(IFence fence, LongArrayList allocations) {}
    private record UploadData(IBuffer target, long uploadOffset, long targetOffset, long size) {}

}
