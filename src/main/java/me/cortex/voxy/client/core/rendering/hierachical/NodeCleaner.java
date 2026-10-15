package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTransferBackend;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.Objects;
import java.util.function.Consumer;

/** Selects old, removable geometry using the current visibility sorter and readback pipeline. */
public class NodeCleaner {
    static final int OUTPUT_COUNT = 256;
    private final NodeCleanerGpuPasses passes;
    final IGpuBuffer visibilityBuffer;
    private final IGpuBuffer outputBuffer;
    private final AsyncNodeManager nodeManager;
    private final int maxNodeCount;
    private final UploadStream uploads;
    private final DownloadStream downloads;
    private final Consumer<MemoryBuffer> removalConsumer;
    private final GpuTransferBackend transfers;
    private final boolean explicitGpu;
    int visibilityId;
    private long cleanupPasses;

    public NodeCleaner(AsyncNodeManager nodeManager) {
        this(nodeManager, null, nodeManager.maxNodeCount, UploadStream.getInstance(), DownloadStream.getInstance(),
                nodeManager::submitRemoveBatch);
    }

    public NodeCleaner(AsyncNodeManager nodeManager, RenderBackend backend, UploadStream uploads, DownloadStream downloads) {
        this(nodeManager, backend, nodeManager.maxNodeCount, uploads, downloads, nodeManager::submitRemoveBatch);
    }

    /** Standalone cleaner with the same shaders and data layout; consumer owns each returned CPU buffer. */
    public NodeCleaner(RenderBackend backend, int maxNodeCount, UploadStream uploads, DownloadStream downloads,
                       Consumer<MemoryBuffer> removalConsumer) {
        this(null, Objects.requireNonNull(backend), maxNodeCount, uploads, downloads, removalConsumer);
    }

    private NodeCleaner(AsyncNodeManager nodeManager, RenderBackend backend, int maxNodeCount,
                        UploadStream uploads, DownloadStream downloads, Consumer<MemoryBuffer> removalConsumer) {
        if (maxNodeCount < 512 || maxNodeCount % 512 != 0) {
            throw new IllegalArgumentException("Cleaner node capacity must be a positive multiple of 512");
        }
        this.nodeManager = nodeManager;
        this.maxNodeCount = maxNodeCount;
        this.uploads = Objects.requireNonNull(uploads);
        this.downloads = Objects.requireNonNull(downloads);
        this.removalConsumer = Objects.requireNonNull(removalConsumer);
        this.transfers = backend == null ? GlTransferBackend.INSTANCE : backend;
        this.explicitGpu = backend != null;
        this.visibilityBuffer = backend == null ? new GlBuffer(maxNodeCount * 4L) : backend.createBuffer(maxNodeCount * 4L);
        IGpuBuffer output = null;
        try {
            this.visibilityBuffer.fill(-1);
            output = backend == null ? new GlBuffer(OUTPUT_COUNT * 12) : backend.createBuffer(OUTPUT_COUNT * 12);
            this.passes = new NodeCleanerGpuPasses(backend, maxNodeCount);
        } catch (RuntimeException | Error failure) {
            if (output != null) output.free();
            this.visibilityBuffer.free();
            throw failure;
        }
        this.outputBuffer = output;
    }

    public void tick(IGpuBuffer nodeDataBuffer) {
        this.visibilityId++;
        if (this.nodeManager != null && this.shouldCleanGeometry()) {
            this.clean(nodeDataBuffer, this.nodeManager.getCurrentMaxNodeId());
        }
    }

    public void clean(IGpuBuffer nodeDataBuffer, int currentMaxNodeId) {
        nodeDataBuffer.assertNotFreed();
        if (currentMaxNodeId < 0 || currentMaxNodeId > this.maxNodeCount || nodeDataBuffer.size() < this.maxNodeCount * 16L) {
            throw new IllegalArgumentException("Cleaner node range exceeds buffer capacity");
        }
        if (currentMaxNodeId == 0) return;
        cleanupPasses++;
        if (!this.explicitGpu) this.outputBuffer.fill(this.maxNodeCount - 2);
        this.passes.clean(nodeDataBuffer, this.visibilityBuffer, this.outputBuffer,
                (currentMaxNodeId + 511) / 512, this.visibilityId);
        this.downloads.download(this.outputBuffer, 4L * OUTPUT_COUNT, 8L * OUTPUT_COUNT, buffer -> {
            MemoryBuffer owned = buffer.copy();
            try {
                this.removalConsumer.accept(owned);
            } catch (RuntimeException | Error failure) {
                if (!owned.isFreed()) owned.free();
                throw failure;
            }
        });
    }

    private boolean shouldCleanGeometry() {
        return shouldCleanGeometry(this.nodeManager.getGeometryCapacity(), this.nodeManager.getUsedGeometryCapacity());
    }

    public static boolean shouldCleanGeometry(long capacity, long used) {
        if (capacity <= 0 || used < 0 || used > capacity) throw new IllegalArgumentException("Invalid geometry usage");
        // The original 256 MB reserve assumes a multi-GB heap. On a 256 MiB
        // Metal heap it evicts after only 12 MiB, repeatedly collapsing visible
        // children back to their parents. Keep headroom without discarding most
        // of a small heap; retain the existing reserve for large heaps.
        return capacity - used < Math.min(256_000_000L, capacity / 4);
    }

    public void updateIds(IntOpenHashSet collection) {
        if (collection.isEmpty()) return;
        // Validate before publishing staging allocations or allowing GPU indexed writes.
        for (int encoded : collection) {
            if ((encoded & 0x7fffffff) >= this.maxNodeCount) throw new IllegalArgumentException("Visibility ID exceeds node capacity");
        }
        int count = collection.size();
        long offset = this.uploads.rawUploadAddress(Math.multiplyExact(count, 4));
        long ptr = this.uploads.getBaseAddress() + offset;
        for (int encoded : collection) {
            MemoryUtil.memPutInt(ptr, encoded);
            ptr += 4;
        }
        this.uploads.commit();
        this.passes.update(this.uploads, offset, count, this.visibilityId, this.visibilityBuffer);
    }

    public IGpuBuffer getVisibilityBuffer() { return this.visibilityBuffer; }
    public int getVisibilityId() { return this.visibilityId; }
    public long getCleanupPasses() { return cleanupPasses; }

    public void free() {
        this.uploads.commit();
        this.downloads.flushWaitClear();
        this.transfers.finish();
        this.passes.close();
        this.visibilityBuffer.free();
        this.outputBuffer.free();
    }
}
