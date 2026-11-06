package me.cortex.nvidium.util;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import me.cortex.nvidium.backend.IBuffer;
import me.cortex.nvidium.backend.IClientMappedBuffer;
import me.cortex.nvidium.backend.IRenderBackend;

// Download stream from gpu to cpu
public class DownloadTaskStream {
    public interface IDownloadFinishedCallback {
        void accept(long addr);
    }

    private record Download(long addr, int size, IDownloadFinishedCallback callback) {}

    private final SegmentedManager allocator = new SegmentedManager();
    private final IRenderBackend backend;
    private final IClientMappedBuffer buffer;

    private int cidx;
    private final ObjectList<Download>[] allocations;

    public DownloadTaskStream(IRenderBackend backend, int frames, long size) {
        this.backend = backend;
        allocator.setLimit(size);
        buffer = backend.createClientMappedBuffer(size);
        TickableManager.register(this);
        allocations = new ObjectList[frames];
        for (int i = 0; i < frames; i++) {
            allocations[i] = new ObjectArrayList<>();
        }
    }

    public void download(IBuffer source, long offset, int size, IDownloadFinishedCallback callback) {
        long addr = allocator.alloc(size);
        backend.copyBuffer(source, buffer, offset, addr, size);
        allocations[cidx].add(new Download(addr, size, callback));
    }

    void tick() {
        cidx = (cidx + 1) % allocations.length;
        for (var download : allocations[cidx]) {
            backend.invalidateMapped(buffer, download.addr, download.size);
            download.callback.accept(download.addr + buffer.clientAddress());
            allocator.free(download.addr);
        }
        allocations[cidx].clear();
    }

    public void delete() {
        TickableManager.remove(this);
        buffer.delete();
    }
}
