package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.UnsafeUtil;

import java.util.Arrays;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
public final class StreamedBoundStore implements IBoundStore {
    private static final int INIT_MAX_CHUNK_COUNT = 1<<12;
    private IGpuBuffer chunkPosBuffer; // Stored as ivec2
    private final RenderBackend backend;
    private final UploadStream uploads;
    private int count;//NOTE: count here is in INTS, not LONGS (i.e. 1 pos is 2 ints)
    private boolean didChange = false;
    private int[] visibleSections = new int[INIT_MAX_CHUNK_COUNT*2];
    public StreamedBoundStore() {
        this.backend = null;
        this.uploads = UploadStream.getInstance();
        this.chunkPosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT * 8L);
    }

    public StreamedBoundStore(RenderBackend backend, UploadStream uploads) {
        this.backend = java.util.Objects.requireNonNull(backend);
        this.uploads = java.util.Objects.requireNonNull(uploads);
        this.chunkPosBuffer = backend.createBuffer(INIT_MAX_CHUNK_COUNT * 8L);
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    @Override
    public void preRender(Viewport<?> viewport) {
        if (this.count == 0 || !this.didChange) return;
        if (this.count*4L>this.chunkPosBuffer.size()) {
            long size = ((long) Math.ceil(this.count * 1.25)) * 4;
            IGpuBuffer replacement = this.backend == null ? new GlBuffer(size) : this.backend.createBuffer(size);
            if (this.backend != null) this.backend.finish();
            this.chunkPosBuffer.free();
            this.chunkPosBuffer = replacement;
        }
        long addr = this.uploads.upload(this.chunkPosBuffer, 0, this.count*4);
        UnsafeUtil.memcpy(this.visibleSections, this.count, addr);
        this.uploads.commit();
        this.didChange = false;
    }

    public void reset() {
        this.count = 0;
        this.didChange = true;
    }

    public void put(long pos) {
        pos = IBoundStore.transformBeforeStore(pos);
        this.visibleSections[this.count++] = (int)(pos&0xFFFFFFFFL);
        this.visibleSections[this.count++] = (int)((pos>>>32)&0xFFFFFFFFL);
        if (this.count >= this.visibleSections.length-2) {
            this.visibleSections = Arrays.copyOf(this.visibleSections, (int) (this.visibleSections.length*1.25));
        }
    }

    public void free() {
        if (this.backend != null) this.backend.finish();
        this.chunkPosBuffer.free();
    }

    @Override
    public IGpuBuffer getBuffer() {
        return this.chunkPosBuffer;
    }

    @Override
    public int getCount() {
        return this.count>>1;
    }
}
