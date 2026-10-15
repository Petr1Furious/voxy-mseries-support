package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;

public class MDICViewport extends Viewport<MDICViewport> {
    public final IGpuBuffer drawCountCallBuffer;
    public final IGpuBuffer drawCallBuffer;
    public final IGpuBuffer positionScratchBuffer;
    public final IGpuBuffer indirectLookupBuffer;
    public final IGpuBuffer visibilityBuffer;
    private final RenderBackend backend;

    public MDICViewport(RenderProperties properties, int maxSectionCount) {
        this(properties, maxSectionCount, null);
    }

    public MDICViewport(RenderProperties properties, int maxSectionCount, RenderBackend backend) {
        super(properties, backend);
        this.backend = backend;
        var allocated = new java.util.ArrayList<IGpuBuffer>();
        try {
            if (maxSectionCount <= 0) throw new IllegalArgumentException("Viewport section capacity must be positive");
            this.drawCountCallBuffer = allocate(backend, 1024, allocated);
            this.drawCallBuffer = allocate(backend, backend == null ? 20L * (MDICSectionRenderer.OPAQUE_DRAW_COUNT
                    + MDICSectionRenderer.TRANSLUCENT_DRAW_COUNT + MDICSectionRenderer.TEMPORAL_DRAW_COUNT) : GpuMDICCapacity.DRAW_BYTES, allocated);
            this.positionScratchBuffer = allocate(backend, 8L * 400000, allocated);
            this.indirectLookupBuffer = allocate(backend, HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE * 4L + 4, allocated);
            this.visibilityBuffer = allocate(backend, maxSectionCount * 4L, allocated);
            if (backend != null) this.visibilityBuffer.fill(0);
        } catch (RuntimeException | Error failure) {
            for (IGpuBuffer buffer : allocated) buffer.free();
            super.delete0();
            throw failure;
        }
    }

    private static IGpuBuffer allocate(RenderBackend backend, long size, java.util.List<IGpuBuffer> allocated) {
        IGpuBuffer buffer = backend == null ? new GlBuffer(size) : backend.createBuffer(size);
        allocated.add(buffer);
        return buffer;
    }

    @Override
    protected void delete0() {
        if (this.backend != null) this.backend.finish();
        super.delete0();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    @Override
    public IGpuBuffer getRenderList() {
        return this.indirectLookupBuffer;
    }
}
