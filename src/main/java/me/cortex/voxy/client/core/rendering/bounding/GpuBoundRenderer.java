package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;

/** Rasterizes the current visible chunk AABBs into the minimum terrain depth bound. */
public final class GpuBoundRenderer implements AutoCloseable {
    private final RenderBackend backend;
    private final UploadStream uploads;
    private final RenderProperties properties;
    private final IGpuBuffer uniform;
    private final IGpuPipeline pipeline;
    private final SharedIndexBuffer indices;

    public GpuBoundRenderer(RenderBackend backend, UploadStream uploads, RenderProperties properties) {
        if (backend.getType() != BackendType.METAL) throw new IllegalArgumentException("GPU chunk bounds currently require Metal");
        this.backend = backend;
        this.uploads = uploads;
        this.properties = properties;
        this.uniform = backend.createBuffer(128);
        SharedIndexBuffer cube = null;
        try {
            cube = new SharedIndexBuffer(backend, uploads);
            var defines = new HashMap<>(properties.gpuShaderDefines());
            defines.put("VOXY_METAL", "1");
            // The Metal clip-space Y flip reverses GL's clockwise back-face convention.
            var state = new PipelineState(new PipelineState.DepthState(true, true,
                    properties.isReverseZ() ? PipelineState.CompareOp.LESS : PipelineState.CompareOp.GREATER),
                    PipelineState.BlendState.OPAQUE, new PipelineState.RasterState(PipelineState.CullMode.BACK,
                    PipelineState.FrontFace.COUNTER_CLOCKWISE, PipelineState.PolygonMode.FILL));
            this.pipeline = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                    ShaderLoader.parse("voxy:chunkoutline/outline.vsh"), ShaderLoader.parse("voxy:chunkoutline/outline.fsh"),
                    defines, null, null, null, null, 0, VertexLayout.EMPTY, state, "voxy:chunk-bounds"));
        } catch (RuntimeException | Error failure) {
            backend.finish();
            if (cube != null) cube.close();
            uniform.free();
            throw failure;
        }
        this.indices = cube;
    }

    public void render(Viewport<?> viewport, IBoundStore store, float renderDistanceBlocks) {
        if (!properties.equals(viewport.properties)) throw new IllegalArgumentException("Bounds depth convention mismatch");
        if (!Float.isFinite(renderDistanceBlocks) || renderDistanceBlocks < 0) throw new IllegalArgumentException("Invalid chunk distance");
        store.preRender(viewport);
        try {
            int count = store.getCount();
            if (count < 0 || (long) count * 8 > store.getBuffer().size()) throw new IllegalArgumentException("Invalid chunk store count");
            if (count != 0) {
                long ptr = uploads.upload(uniform, 0, 128);
                MemoryUtil.memSet(ptr, 0, 128);
                int bx = (int) Math.floor(viewport.cameraX), by = (int) Math.floor(viewport.cameraY), bz = (int) Math.floor(viewport.cameraZ);
                new Matrix4f(viewport.MVP).translate((float) (bx - viewport.cameraX), (float) (by - viewport.cameraY),
                        (float) (bz - viewport.cameraZ)).getToAddress(ptr);
                MemoryUtil.memPutInt(ptr + 64, bx); MemoryUtil.memPutInt(ptr + 68, by); MemoryUtil.memPutInt(ptr + 72, bz);
                MemoryUtil.memPutFloat(ptr + 80, (float) (bx - viewport.cameraX));
                MemoryUtil.memPutFloat(ptr + 84, (float) (by - viewport.cameraY));
                MemoryUtil.memPutFloat(ptr + 88, (float) (bz - viewport.cameraZ));
                MemoryUtil.memPutFloat(ptr + 92, renderDistanceBlocks);
                uploads.commit();
            }
            try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(viewport.width, viewport.height)
                    .clearDepth(viewport.depthBoundingBuffer.getGpuDepthTex(), properties.inverseClearDepth()).build())) {
                if (count == 0) return;
                encoder.setPipeline(pipeline);
                encoder.setViewport(0, 0, viewport.width, viewport.height, 0, 1);
                encoder.setScissor(0, 0, viewport.width, viewport.height);
                encoder.setBuffer(0, uniform, 0);
                encoder.setBuffer(1, store.getBuffer(), 0);
                encoder.bindIndexBuffer(indices.getBuffer(), RenderEncoder.INDEX_TYPE_UINT16, indices.getCubeOffset());
                encoder.drawIndexed(RenderEncoder.PRIMITIVE_TRIANGLES, 36, count, 0, 0, 0);
            }
        } finally {
            store.postRender(viewport);
        }
    }

    @Override
    public void close() {
        backend.finish();
        pipeline.close(); indices.close(); uniform.free();
    }
}
