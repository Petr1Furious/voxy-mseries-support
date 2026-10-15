package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;

/** Explicit GPU encoding of the current 26.3 traversal and its indirect queue dispatches. */
public final class GpuHierarchicalTraversal implements AutoCloseable {
    public static final int ITERATIONS = WorldEngine.MAX_LOD_LAYER + 1;
    public record Buffers(IGpuBuffer scene, IGpuBuffer requests, IGpuBuffer renderList, IGpuBuffer nodes,
                          IGpuBuffer metadata, IGpuBuffer topNodes, IGpuBuffer queueA, IGpuBuffer queueB,
                          IGpuBuffer visibility, IGpuBuffer statistics) {}

    private final RenderBackend backend;
    private final IGpuPipeline pipeline;
    private final IGpuSampler sampler;
    private final boolean statistics;
    private final int queueCapacity;

    public GpuHierarchicalTraversal(RenderBackend backend, RenderProperties properties, boolean statistics) {
        this(backend, properties, statistics, HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE);
    }

    public GpuHierarchicalTraversal(RenderBackend backend, RenderProperties properties, boolean statistics, int queueCapacity) {
        if (queueCapacity <= 0) throw new IllegalArgumentException("Traversal queue capacity must be positive");
        this.backend = backend;
        this.statistics = statistics;
        this.queueCapacity = queueCapacity;
        var defines = new HashMap<>(properties.gpuShaderDefines());
        defines.put("MAX_ITERATIONS", Integer.toString(ITERATIONS));
        defines.put("LOCAL_SIZE_BITS", "5");
        defines.put("MAX_REQUEST_QUEUE_SIZE", Integer.toString(HierarchicalOcclusionTraverser.MAX_REQUEST_QUEUE_SIZE));
        defines.put("NODE_QUEUE_CAPACITY", Integer.toString(queueCapacity));
        defines.put("HIZ_BINDING", "0");
        defines.put("SCENE_UNIFORM_BINDING", "1");
        defines.put("REQUEST_QUEUE_BINDING", "2");
        defines.put("RENDER_QUEUE_BINDING", "3");
        defines.put("NODE_DATA_BINDING", "4");
        defines.put("NODE_QUEUE_INDEX_BINDING", "5");
        defines.put("NODE_QUEUE_META_BINDING", "6");
        defines.put("NODE_QUEUE_SOURCE_BINDING", "7");
        defines.put("NODE_QUEUE_SINK_BINDING", "8");
        defines.put("RENDER_TRACKER_BINDING", "9");
        if (statistics) {
            defines.put("HAS_STATISTICS", "1");
            defines.put("STATISTICS_BUFFER_BINDING", "10");
        }
        String source = PrintfDebugUtil.PRINTF_processor.process(ShaderType.COMPUTE,
                ShaderLoader.parse("voxy:lod/hierarchical/traversal_dev.comp"));
        this.pipeline = backend.createComputePipeline(new ComputePipelineDesc(source, defines,
                null, null, 32, 1, 1, "Hierarchical Traversal"));
        try {
            this.sampler = backend.createSampler(SamplerDesc.builder()
                    .filter(SamplerDesc.Filter.NEAREST, SamplerDesc.Filter.NEAREST)
                    .mipFilter(SamplerDesc.MipFilter.NEAREST).label("Traversal HiZ").build());
        } catch (RuntimeException | Error failure) {
            this.pipeline.close();
            throw failure;
        }
    }

    /** Scene, counters and metadata must already be uploaded; requests/render list are caller-owned. */
    public void traverse(Buffers buffers, IGpuTexture hiz, int topNodeCount) {
        if (topNodeCount < 0 || topNodeCount > buffers.topNodes.size() / 4
                || topNodeCount > this.queueCapacity || buffers.queueA.size() < this.queueCapacity * 4L
                || buffers.queueB.size() < this.queueCapacity * 4L
                || buffers.metadata.size() < ITERATIONS * 16L || buffers.scene.size() < 208) {
            throw new IllegalArgumentException("Traversal input exceeds buffer capacity");
        }
        if (topNodeCount == 0) return;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            IGpuBuffer input = iteration == 0 ? buffers.topNodes : ((iteration & 1) == 0 ? buffers.queueA : buffers.queueB);
            IGpuBuffer output = (iteration & 1) == 0 ? buffers.queueB : buffers.queueA;
            // A fresh encoder makes previous queue writes visible to both shader reads and indirect arguments.
            try (MemoryStack stack = MemoryStack.stackPush(); var encoder = this.backend.beginComputePass()) {
                var parameters = stack.calloc(16);
                parameters.putInt(0, iteration);
                encoder.setPipeline(this.pipeline);
                encoder.setBuffer(1, buffers.scene, 0);
                encoder.setBuffer(2, buffers.requests, 0);
                encoder.setBuffer(3, buffers.renderList, 0);
                encoder.setBuffer(4, buffers.nodes, 0);
                encoder.setBytes(5, MemoryUtil.memAddress(parameters), 16);
                encoder.setBuffer(6, buffers.metadata, 0);
                encoder.setBuffer(7, input, 0);
                encoder.setBuffer(8, output, 0);
                encoder.setBuffer(9, buffers.visibility, 0);
                if (this.statistics) encoder.setBuffer(10, buffers.statistics, 0);
                encoder.setTexture(0, hiz);
                encoder.setSampler(0, this.sampler);
                if (iteration == 0) encoder.dispatch((topNodeCount + 31) / 32, 1, 1);
                else encoder.dispatchIndirect(buffers.metadata, iteration * 16L);
            }
        }
    }

    @Override public void close() {
        this.backend.finish();
        this.sampler.close();
        this.pipeline.close();
    }
}
