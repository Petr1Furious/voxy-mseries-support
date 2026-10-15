package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.util.HashMap;

/** Encodes the 26.3 SSAO shader and its terrain metadata-to-alpha conversion on Metal. */
public final class GpuSsao implements AutoCloseable {
    private final RenderBackend backend;
    private final RenderProperties properties;
    private final boolean better;
    private final IGpuPipeline pipeline;
    private final IGpuSampler depthSampler, colorSampler;

    public GpuSsao(RenderBackend backend, RenderProperties properties, SSAO.SSAOMode mode) {
        this.backend = backend; this.properties = properties;
        this.better = mode == SSAO.SSAOMode.BETTER || mode == SSAO.SSAOMode.BEST;
        var defines = new HashMap<>(properties.gpuShaderDefines());
        String source = ShaderLoader.parse("voxy:post/ssao.comp");
        if (better) {
            int samples = mode == SSAO.SSAOMode.BEST ? 24 : 12;
            defines.put("BETTER_SSAO", "1"); defines.put("SSAO_STEPS", Integer.toString(samples));
            defines.put("USE_GENERATED_SAMPLE_POINTS", "1");
            StringBuilder points = new StringBuilder();
            for (int i = 0; i < samples; i++) {
                float a = (i + 0.5f) / samples;
                float r = (float) Math.sqrt(((float) (i / 1.6180339887 + 0.5)) % 1);
                float theta = a * 6.2831853f;
                if (i != 0) points.append(", ");
                points.append("vec2(").append((float) (r * Math.cos(theta))).append(", ")
                        .append((float) (r * Math.sin(theta))).append(")");
            }
            source = source.replace("%%CONST_ARRAY%%", points);
        }
        IGpuPipeline shader = null; IGpuSampler depth = null, color = null;
        try {
            shader = backend.createComputePipeline(new ComputePipelineDesc(source, defines, null, null, 8, 8, 1, "voxy:ssao/" + mode));
            depth = backend.createSampler(SamplerDesc.builder().filter(better ? SamplerDesc.Filter.LINEAR : SamplerDesc.Filter.NEAREST,
                    better ? SamplerDesc.Filter.LINEAR : SamplerDesc.Filter.NEAREST).label("voxy:ssao/depth").build());
            color = backend.createSampler(SamplerDesc.builder().label("voxy:ssao/color").build());
        } catch (RuntimeException | Error failure) {
            if (color != null) color.close(); if (depth != null) depth.close(); if (shader != null) shader.close(); throw failure;
        }
        pipeline = shader; depthSampler = depth; colorSampler = color;
    }

    public void compute(Viewport<?> viewport, IGpuTexture output, IGpuTexture color, IGpuTexture depth, IGpuTexture vanillaDepth) {
        if (!properties.equals(viewport.properties)) throw new IllegalArgumentException("SSAO depth convention mismatch");
        for (var texture : new IGpuTexture[]{output, color, depth, vanillaDepth}) {
            if (texture.getWidth() != viewport.width || texture.getHeight() != viewport.height) throw new IllegalArgumentException("SSAO viewport size mismatch");
        }
        if (output == color) throw new IllegalArgumentException("SSAO requires a separate output texture");
        try (var stack = MemoryStack.stackPush(); var encoder = backend.beginComputePass()) {
            int bytes = better ? 256 : 128;
            long ptr = stack.nmalloc(16, bytes);
            if (better) {
                viewport.projection.getToAddress(ptr); viewport.projection.invert(new Matrix4f()).getToAddress(ptr + 64);
                viewport.modelView.getToAddress(ptr + 128); viewport.vanillaProjection.invert(new Matrix4f()).getToAddress(ptr + 192);
            } else {
                viewport.MVP.getToAddress(ptr); viewport.MVP.invert(new Matrix4f()).getToAddress(ptr + 64);
            }
            encoder.setPipeline(pipeline); encoder.setBytes(4, ptr, bytes);
            encoder.setStorageImage(0, output, 0);
            encoder.setTexture(1, color); encoder.setSampler(1, colorSampler);
            encoder.setTexture(2, depth); encoder.setSampler(2, depthSampler);
            if (better) { encoder.setTexture(3, vanillaDepth); encoder.setSampler(3, depthSampler); }
            encoder.dispatch((viewport.width + 7) / 8, (viewport.height + 7) / 8, 1);
        }
    }
    public void close() { backend.finish(); colorSampler.close(); depthSampler.close(); pipeline.close(); }
}
