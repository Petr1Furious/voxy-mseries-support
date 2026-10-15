package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gpu.*;

/** Native depth masking and float export for the vanilla 26.3 frame pipeline. */
public final class MetalFrameDepth implements AutoCloseable {
    private final RenderBackend backend;
    private final RenderProperties properties;
    private final IGpuPipeline mask, export;
    private final IGpuSampler sampler;
    private static final String VERTEX = """
            #version 460 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String MASK = """
            #version 460 core
            layout(binding=0) uniform sampler2D sourceDepth;
            void main() {
                float depth = texelFetch(sourceDepth, ivec2(gl_FragCoord.xy), 0).r;
                #ifdef USE_REVERSE_Z
                if (depth == 0.0) discard;
                gl_FragDepth = 1.0;
                #else
                if (depth == 1.0) discard;
                gl_FragDepth = 0.0;
                #endif
            }
            """;
    private static final String EXPORT = """
            #version 460 core
            layout(binding=0) uniform sampler2D sourceDepth;
            layout(location=0) out float depth;
            void main() { depth = texelFetch(sourceDepth, ivec2(gl_FragCoord.xy), 0).r; }
            """;

    public MetalFrameDepth(RenderBackend backend, RenderProperties properties) {
        this.backend = backend; this.properties = properties;
        IGpuPipeline first = null, second = null;
        IGpuSampler sampling = null;
        try {
            var state = new PipelineState(new PipelineState.DepthState(true, true, PipelineState.CompareOp.ALWAYS),
                    PipelineState.BlendState.OPAQUE, PipelineState.RasterState.NO_CULL);
            first = backend.createGraphicsPipeline(new GraphicsPipelineDesc(VERTEX, MASK, properties.gpuShaderDefines(),
                    null, null, null, null, 0, VertexLayout.EMPTY, state, "voxy:frame/mask-vanilla"));
            second = backend.createGraphicsPipeline(new GraphicsPipelineDesc(VERTEX, EXPORT, java.util.Map.of(),
                    null, null, null, null, 0x822e, VertexLayout.EMPTY, PipelineState.DEFAULT, "voxy:frame/export-depth"));
            sampling = backend.createSampler(SamplerDesc.builder().label("voxy:frame/depth").build());
        } catch (RuntimeException | Error failure) {
            if (sampling != null) sampling.close(); if (second != null) second.close(); if (first != null) first.close();
            throw failure;
        }
        mask = first; export = second; sampler = sampling;
    }

    /** Equivalent depth mask to setup_stencil_depth.frag; terrain also checks the shared vanilla mask explicitly. */
    public void prepare(IGpuTexture vanillaDepth, IGpuTexture targetDepth, IGpuTexture targetColor) {
        dimensions(vanillaDepth, targetDepth);
        dimensions(targetDepth, targetColor);
        try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(targetDepth.getWidth(), targetDepth.getHeight())
                .clearDepth(targetDepth, properties.clearDepth()).clearColor(targetColor, 0, 0, 0, 0).build())) {}
        try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(targetDepth.getWidth(), targetDepth.getHeight())
                .depthAttachment(targetDepth, 0, RenderPassDesc.LoadAction.LOAD, RenderPassDesc.StoreAction.STORE, 0).build())) {
            draw(encoder, mask, vanillaDepth);
        }
    }

    public void export(IGpuTexture sourceDepth, IGpuTexture targetFloat) {
        dimensions(sourceDepth, targetFloat);
        if (targetFloat.getFormat() != 0x822e) throw new IllegalArgumentException("Depth export requires R32F");
        try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(targetFloat.getWidth(), targetFloat.getHeight())
                .addColorAttachment(targetFloat, 0, RenderPassDesc.LoadAction.DONT_CARE,
                        RenderPassDesc.StoreAction.STORE, 0, 0, 0, 0).build())) {
            draw(encoder, export, sourceDepth);
        }
    }

    private void draw(RenderEncoder encoder, IGpuPipeline pipeline, IGpuTexture source) {
        encoder.setPipeline(pipeline); encoder.setViewport(0, 0, source.getWidth(), source.getHeight(), 0, 1);
        encoder.setScissor(0, 0, source.getWidth(), source.getHeight());
        encoder.setTexture(0, source); encoder.setSampler(0, sampler);
        encoder.draw(RenderEncoder.PRIMITIVE_TRIANGLES, 0, 3, 1, 0);
    }
    private static void dimensions(IGpuTexture source, IGpuTexture target) {
        if (source.getWidth() != target.getWidth() || source.getHeight() != target.getHeight()) throw new IllegalArgumentException("Frame depth size mismatch");
    }
    public void close() { backend.finish(); sampler.close(); export.close(); mask.close(); }
}
