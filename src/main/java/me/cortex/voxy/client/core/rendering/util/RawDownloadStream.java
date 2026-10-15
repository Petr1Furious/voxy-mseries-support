package me.cortex.voxy.client.core.rendering.util;


import me.cortex.voxy.client.core.gl.GlTransferBackend;
import me.cortex.voxy.client.core.gpu.GpuTransferBackend;
import me.cortex.voxy.client.core.gpu.IGpuFence;
import me.cortex.voxy.client.core.gpu.IGpuPersistentBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

import static org.lwjgl.opengl.ARBMapBufferRange.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;

//Special download stream which allows access to the download buffer directly
public class RawDownloadStream implements AutoCloseable {
    private final GpuTransferBackend backend;
    private final int alignment;
    //NOTE: after the callback returns the pointer is no longer valid for client use
    public interface IDownloadCompletedCallback{void accept(long ptr);}
    private record DownloadFragment(int allocation, IDownloadCompletedCallback callback){}
    private record DownloadFrame(IGpuFence fence, DownloadFragment[] fragments) {}

    private final IGpuPersistentBuffer downloadBuffer;
    private final AllocationArena allocationArena = new AllocationArena();
    private final ArrayList<DownloadFragment> frameFragments = new ArrayList<>();
    private final Deque<DownloadFrame> frames = new ArrayDeque<>();

    public RawDownloadStream(int size) {
        this(GlTransferBackend.INSTANCE, size);
    }

    public RawDownloadStream(GpuTransferBackend backend, int size) {
        this.backend = backend;
        this.alignment = Math.max(16, backend.getBufferOffsetAlignment());
        this.downloadBuffer = backend.createPersistentBuffer(size, GL_MAP_READ_BIT|GL_MAP_COHERENT_BIT).name("RawDownloadStream");
        this.allocationArena.setLimit(size);
    }

    public int download(int size, IDownloadCompletedCallback callback) {
        if (size <= 0 || callback == null) throw new IllegalArgumentException("Raw download requires a positive size and callback");
        long aligned = UploadStream.alignUp((long) size, this.alignment);
        if (aligned > this.downloadBuffer.size()) throw new IllegalArgumentException("Raw download exceeds staging capacity");
        size = (int) aligned;
        int allocation = (int) this.allocationArena.alloc(size);
        if (allocation == AllocationArena.SIZE_LIMIT) {
            Logger.warn("Raw download stream full, preemptively committing, this could cause bad things to happen");
            //Hit the download limit, attempt to free
            this.backend.finish();
            this.tick();
            allocation = (int) this.allocationArena.alloc(size);
            if (allocation == AllocationArena.SIZE_LIMIT) {
                throw new IllegalStateException("Unable free enough memory for raw download stream");
            }
        }
        this.frameFragments.add(new DownloadFragment(allocation, callback));
        return allocation;
    }

    //Creates a new "frame" for previously allocated downloads and enqueues a fence
    // also invalidates all previous download pointers from this instance
    public void submit() {
        if (!this.frameFragments.isEmpty()) {
            var fragments = this.frameFragments.toArray(new DownloadFragment[0]);
            this.frameFragments.clear();
            this.frames.add(new DownloadFrame(this.backend.createFence(), fragments));
            this.backend.submit();
        }
    }

    public void tick() {
        this.submit();

        while (!this.frames.isEmpty()) {
            //If the first element is not signaled, none of the others will be signaled so break
            if (!this.frames.peek().fence.signaled()) {
                break;
            }
            var frame = this.frames.poll();
            try {
                for (var fragment : frame.fragments) {
                    fragment.callback.accept(this.downloadBuffer.addr() + fragment.allocation);
                }
            } finally {
                for (var fragment : frame.fragments) this.allocationArena.free(fragment.allocation);
                frame.fence.free();
            }
        }
    }

    public int getBufferId() {
        if (this.backend != GlTransferBackend.INSTANCE) throw new IllegalStateException("Metal readback must use GPU buffer bindings");
        return this.downloadBuffer.id();
    }

    public IGpuPersistentBuffer getBuffer() { return this.downloadBuffer; }

    @Override public void close() { this.free(); }

    public void free() {
        this.backend.finish();
        this.tick();
        IGpuFence fence = this.backend.createFence();
        this.backend.finish();
        while (!fence.signaled()) {
            this.backend.finish();
        }
        fence.free();
        this.tick();
        if (this.frames.size() != 0) {
            throw new IllegalStateException();
        }
        this.downloadBuffer.free();
    }
}
