package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Encodes 26.3's depth-tested visibility raster and production MDIC compute shaders. */
public final class GpuMDICDrawCalls implements AutoCloseable {
    public static final long TRANSLUCENT_OFFSET = GpuMDICCapacity.OPAQUE * 20L;
    public static final long TEMPORAL_OFFSET = GpuMDICCapacity.TEMPORAL_BASE * 20L;
    private final RenderBackend backend;
    private final UploadStream uploads;
    private final IGpuBuffer uniform, distances, statistics;
    private final IGpuPipeline prep, cull, commands, prefix, translucents;
    private final SharedIndexBuffer indices;
    private final RenderProperties properties;

    public GpuMDICDrawCalls(RenderBackend backend, UploadStream uploads, RenderProperties properties) {
        this.backend = backend;
        this.uploads = uploads;
        this.properties = properties;
        var allocated = new ArrayList<AutoCloseable>();
        try {
            uniform = buffer(1024, allocated);
            distances = buffer((1024L + GpuMDICCapacity.TRANSLUCENT) * 4, allocated);
            statistics = buffer(1024, allocated);
            indices = new SharedIndexBuffer(backend, uploads);
            allocated.add(indices);
            Map<String, String> defines = new HashMap<>(properties.gpuShaderDefines());
            if (backend.getType() == BackendType.METAL) defines.put("VOXY_METAL", "1");
            prep = compute("lod/gl46/prep.comp", defines, 1, allocated);
            var state = new PipelineState(new PipelineState.DepthState(true, false,
                    properties.isReverseZ() ? PipelineState.CompareOp.GREATER_EQUAL : PipelineState.CompareOp.LESS_EQUAL),
                    PipelineState.BlendState.OPAQUE, PipelineState.RasterState.NO_CULL);
            cull = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                    ShaderLoader.parse("voxy:lod/gl46/cull/raster.vert"), ShaderLoader.parse("voxy:lod/gl46/cull/raster.frag"),
                    defines, null, null, null, null, 0, VertexLayout.EMPTY, state, "voxy:mdic/cull"));
            allocated.add(cull);
            commands = compute("lod/gl46/cmdgen.comp", Map.of("TRANSLUCENT_WRITE_BASE", "1024",
                    "TEMPORAL_OFFSET", Integer.toString(GpuMDICCapacity.TEMPORAL_BASE), "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7",
                    "HAS_STATISTICS", "1", "STATISTICS_BUFFER_BINDING", "8"), 128, allocated);
            prefix = compute("util/prefixsum/simple.comp", Map.of("IO_BUFFER", "0"), 256, allocated);
            translucents = compute("lod/gl46/buildtranslucents.comp", Map.of("TRANSLUCENT_WRITE_BASE", "1024",
                    "TRANSLUCENT_OFFSET", Integer.toString(GpuMDICCapacity.OPAQUE), "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "5"), 128, allocated);
        } catch (RuntimeException | Error failure) {
            backend.finish();
            for (int i = allocated.size() - 1; i >= 0; i--) {
                try { allocated.get(i).close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private IGpuBuffer buffer(long size, ArrayList<AutoCloseable> allocated) {
        IGpuBuffer buffer = backend.createBuffer(size);
        allocated.add(buffer::free);
        return buffer;
    }

    private IGpuPipeline compute(String path, Map<String, String> defines, int threads, ArrayList<AutoCloseable> allocated) {
        var pipeline = backend.createComputePipeline(new ComputePipelineDesc(ShaderLoader.parse("voxy:" + path),
                defines, null, null, threads, 1, 1, "voxy:mdic/" + path));
        allocated.add(pipeline);
        return pipeline;
    }

    public IGpuBuffer getUniform() { return uniform; }
    public IGpuBuffer getStatistics() { return statistics; }
    public SharedIndexBuffer getIndices() { return indices; }

    /** Refresh matrices before rendering the previous frame's retained draw commands. */
    public void updateScene(MDICViewport viewport) {
        long ptr = uploads.upload(uniform, 0, 1024);
        MemoryUtil.memSet(ptr, 0, 1024);
        new Matrix4f(viewport.MVP).translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y,
                -viewport.innerTranslation.z).getToAddress(ptr);
        viewport.section.getToAddress(ptr + 64);
        MemoryUtil.memPutInt(ptr + 76, viewport.frameId & 0x7fffffff);
        viewport.innerTranslation.getToAddress(ptr + 80);
        uploads.commit();
    }

    public void build(MDICViewport viewport, IGpuBuffer metadata, IGpuTexture occlusionDepth) {
        if (!properties.equals(viewport.properties)) throw new IllegalArgumentException("Viewport depth conventions differ");
        if (occlusionDepth.getWidth() != viewport.width || occlusionDepth.getHeight() != viewport.height) {
            throw new IllegalArgumentException("Occlusion depth dimensions differ from viewport");
        }
        updateScene(viewport);
        MemoryUtil.memSet(uploads.upload(distances, 0, 4096), 0, 4096);
        MemoryUtil.memSet(uploads.upload(statistics, 0, 1024), 0, 1024);
        uploads.commit();
        try (var encoder = backend.beginComputePass()) {
            encoder.setPipeline(prep);
            encoder.setBuffer(1, viewport.drawCountCallBuffer, 0);
            encoder.setBuffer(2, viewport.indirectLookupBuffer, 0);
            encoder.dispatch(1, 1, 1);
        }
        // The current Metal indirect encoder reads baseInstance from shared command memory.
        backend.finish();
        if (viewport.drawCountCallBuffer instanceof me.cortex.voxy.client.core.metal.MetalBuffer counts
                && Integer.toUnsignedLong(MemoryUtil.memGetInt(counts.getContentsPtr() + 28)) > GpuMDICCapacity.SECTIONS) {
            throw new IllegalStateException("Traversal render list exceeds MDIC section capacity");
        }
        try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(viewport.width, viewport.height)
                .depthAttachment(occlusionDepth, 0,
                        RenderPassDesc.LoadAction.LOAD, RenderPassDesc.StoreAction.STORE, 0).build())) {
            encoder.setPipeline(cull);
            encoder.setViewport(0, 0, viewport.width, viewport.height, 0, 1);
            encoder.setScissor(0, 0, viewport.width, viewport.height);
            encoder.setBuffer(0, uniform, 0);
            encoder.setBuffer(1, metadata, 0);
            encoder.setBuffer(2, viewport.visibilityBuffer, 0);
            encoder.setBuffer(3, viewport.indirectLookupBuffer, 0);
            encoder.bindIndexBuffer(indices.getBuffer(), RenderEncoder.INDEX_TYPE_UINT16, indices.getCubeOffset());
            encoder.drawIndexedIndirect(RenderEncoder.PRIMITIVE_TRIANGLES, viewport.drawCountCallBuffer, 24, 1, 20);
        }
        try (var encoder = backend.beginComputePass()) {
            encoder.setPipeline(commands);
            encoder.setBuffer(0, uniform, 0);
            encoder.setBuffer(1, viewport.drawCallBuffer, 0);
            encoder.setBuffer(2, viewport.drawCountCallBuffer, 0);
            encoder.setBuffer(3, metadata, 0);
            encoder.setBuffer(4, viewport.visibilityBuffer, 0);
            encoder.setBuffer(5, viewport.indirectLookupBuffer, 0);
            encoder.setBuffer(6, viewport.positionScratchBuffer, 0);
            encoder.setBuffer(7, distances, 0);
            encoder.setBuffer(8, statistics, 0);
            encoder.dispatchIndirect(viewport.drawCountCallBuffer, 0);
            encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
            encoder.setPipeline(prefix);
            encoder.setBuffer(0, distances, 0);
            encoder.dispatch(1, 1, 1);
            encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
            encoder.setPipeline(translucents);
            encoder.setBuffer(0, uniform, 0);
            encoder.setBuffer(1, viewport.drawCallBuffer, 0);
            encoder.setBuffer(2, viewport.drawCountCallBuffer, 0);
            encoder.setBuffer(3, metadata, 0);
            encoder.setBuffer(4, viewport.indirectLookupBuffer, 0);
            encoder.setBuffer(5, distances, 0);
            encoder.dispatchIndirect(viewport.drawCountCallBuffer, 0);
        }
        // Makes counts and per-draw baseInstance safe for the Metal host encoding loop.
        backend.finish();
    }

    @Override
    public void close() {
        backend.finish();
        translucents.close(); prefix.close(); commands.close(); cull.close(); prep.close();
        indices.close(); statistics.free(); distances.free(); uniform.free();
    }
}
