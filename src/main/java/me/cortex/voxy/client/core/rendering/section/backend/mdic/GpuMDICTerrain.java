package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.model.ModelStore;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Production 26.3 quad decoding, atlas/lightmap shading and GPU-generated indirect terrain draws. */
public final class GpuMDICTerrain implements AutoCloseable {
    public enum Layer { OPAQUE, TEMPORAL, TRANSLUCENT }
    private final RenderBackend backend;
    private final IGpuPipeline opaque, translucent;
    private final IGpuSampler lightSampler, depthSampler;
    private final int colorFormat;
    private final boolean useVanillaMask;
    private IGpuTexture vanillaDepth;

    /** Face tint values come from the active world's cardinal lighting. */
    public GpuMDICTerrain(RenderBackend backend, RenderProperties properties, Map<String, String> faceTints) {
        this(backend, properties, faceTints, 0x8058);
    }

    public GpuMDICTerrain(RenderBackend backend, RenderProperties properties, Map<String, String> faceTints, int colorFormat) {
        this(backend, properties, faceTints, colorFormat, false);
    }

    public GpuMDICTerrain(RenderBackend backend, RenderProperties properties, Map<String, String> faceTints, int colorFormat, boolean useVanillaMask) {
        this.backend = backend;
        this.colorFormat = colorFormat;
        this.useVanillaMask = useVanillaMask;
        var defines = new HashMap<>(properties.gpuShaderDefines());
        defines.putAll(faceTints);
        if (useVanillaMask) defines.put("VOXY_VANILLA_MASK", "1");
        if (backend.getType() == BackendType.METAL) {
            defines.put("VOXY_METAL", "1");
        }
        IGpuPipeline first = null, second = null;
        IGpuSampler light = null, depth = null;
        try {
            first = pipeline(defines, properties, false);
            defines.put("TRANSLUCENT", "1");
            second = pipeline(defines, properties, true);
            light = backend.createSampler(SamplerDesc.builder().filter(SamplerDesc.Filter.LINEAR, SamplerDesc.Filter.LINEAR)
                    .label("voxy:lightmap").build());
            depth = backend.createSampler(SamplerDesc.builder().label("voxy:depthBounds").build());
        } catch (RuntimeException | Error failure) {
            if (depth != null) depth.close();
            if (light != null) light.close();
            if (second != null) second.close();
            if (first != null) first.close();
            throw failure;
        }
        opaque = first; translucent = second; lightSampler = light; depthSampler = depth;
    }

    private IGpuPipeline pipeline(Map<String, String> defines, RenderProperties properties, boolean transparent) {
        // The 26.3 sorted translucent pass writes depth too: final composition needs
        // the nearest translucent surface when no opaque LOD lies underneath it.
        var state = new PipelineState(new PipelineState.DepthState(true, true,
                properties.isReverseZ() ? PipelineState.CompareOp.GREATER_EQUAL : PipelineState.CompareOp.LESS_EQUAL),
                transparent ? PipelineState.BlendState.ALPHA : PipelineState.BlendState.OPAQUE, PipelineState.RasterState.NO_CULL);
        return backend.createGraphicsPipeline(new GraphicsPipelineDesc(ShaderLoader.parse("voxy:lod/gl46/quads3.vert"),
                ShaderLoader.parse("voxy:lod/gl46/quads.frag"), Map.copyOf(defines), null, null, null, null,
                colorFormat, VertexLayout.EMPTY, state, "voxy:terrain/" + (transparent ? "translucent" : "opaque")));
    }

    public void setVanillaDepth(IGpuTexture depth) {
        if (!useVanillaMask) throw new IllegalStateException("Terrain pipeline was compiled without vanilla masking");
        this.vanillaDepth = java.util.Objects.requireNonNull(depth);
    }

    public void render(Layer layer, MDICViewport viewport, GpuMDICDrawCalls calls, IGpuBuffer geometry,
                       ModelStore models, IGpuTexture lightmap, RenderPassDesc pass) {
        render(layer, viewport, calls, geometry, encoder -> models.bind(encoder, 3, 4, 0), lightmap, pass);
    }

    public void render(Layer layer, MDICViewport viewport, GpuMDICDrawCalls calls, IGpuBuffer geometry,
                       Consumer<RenderEncoder> modelBindings, IGpuTexture lightmap, RenderPassDesc pass) {
        if (useVanillaMask && (vanillaDepth == null || vanillaDepth.getWidth() != viewport.width || vanillaDepth.getHeight() != viewport.height)) {
            throw new IllegalStateException("Terrain requires the current viewport's vanilla depth mask");
        }
        try (var encoder = backend.beginRenderPass(pass)) {
            encoder.setViewport(0, 0, viewport.width, viewport.height, 0, 1);
            encoder.setScissor(0, 0, viewport.width, viewport.height);
            encoder.setPipeline(layer == Layer.TRANSLUCENT ? translucent : opaque);
            encoder.setBuffer(0, calls.getUniform(), 0);
            encoder.setBuffer(1, geometry, 0);
            modelBindings.accept(encoder);
            encoder.setBuffer(5, viewport.positionScratchBuffer, 0);
            encoder.setTexture(1, lightmap);
            encoder.setSampler(1, lightSampler);
            encoder.setTexture(2, viewport.depthBoundingBuffer.getGpuDepthTex());
            encoder.setSampler(2, depthSampler);
            if (useVanillaMask) {
                encoder.setTexture(7, vanillaDepth); encoder.setSampler(7, depthSampler);
            }
            encoder.bindIndexBuffer(calls.getIndices().getBuffer(), calls.getIndices().getIndexType(), 0);
            long drawOffset = switch (layer) {
                case OPAQUE -> 0;
                case TEMPORAL -> GpuMDICDrawCalls.TEMPORAL_OFFSET;
                case TRANSLUCENT -> GpuMDICDrawCalls.TRANSLUCENT_OFFSET;
            };
            long countOffset = switch (layer) { case OPAQUE -> 12; case TEMPORAL -> 20; case TRANSLUCENT -> 16; };
            int limit = switch (layer) {
                case OPAQUE -> GpuMDICCapacity.OPAQUE;
                case TEMPORAL -> GpuMDICCapacity.TEMPORAL;
                case TRANSLUCENT -> GpuMDICCapacity.TRANSLUCENT;
            };
            if (viewport.metalDraws != null) {
                // GPU-padded to the bounds of the build that generated these commands; see MetalDrawBounds.
                int draws = viewport.metalDraws.bound(switch (layer) { case OPAQUE -> 0; case TRANSLUCENT -> 1; case TEMPORAL -> 2; });
                if (draws > 0) encoder.drawIndexedIndirect(RenderEncoder.PRIMITIVE_TRIANGLES, viewport.drawCallBuffer, drawOffset, draws, 20);
            } else {
                encoder.drawIndexedIndirectCount(RenderEncoder.PRIMITIVE_TRIANGLES, viewport.drawCallBuffer, drawOffset,
                        viewport.drawCountCallBuffer, countOffset, limit, 20);
            }
        }
    }

    @Override
    public void close() {
        backend.finish();
        depthSampler.close(); lightSampler.close(); translucent.close(); opaque.close();
    }
}
