package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Builds actual conservative depth mips with explicit texture views and compute passes. */
public final class GpuHiZBuffer implements AutoCloseable {
    private final RenderBackend backend;
    private final IGpuPipeline reduction;
    private final IGpuSampler sampler;
    private IGpuTexture texture;
    private IGpuTexture[] views;
    private int width, height;

    public GpuHiZBuffer(RenderBackend backend, RenderProperties properties) {
        this.backend = backend;
        this.reduction = backend.createComputePipeline(new ComputePipelineDesc(
                ShaderLoader.parse("voxy:hiz/reduce.comp"), properties.gpuShaderDefines(),
                null, null, 8, 8, 1, "HiZ Reduction"));
        try {
            this.sampler = backend.createSampler(SamplerDesc.builder()
                    .filter(SamplerDesc.Filter.NEAREST, SamplerDesc.Filter.NEAREST).label("HiZ Sampler").build());
        } catch (RuntimeException | Error failure) {
            this.reduction.close();
            throw failure;
        }
    }

    public void buildMipChain(IGpuTexture sourceDepth, int sourceWidth, int sourceHeight) {
        sourceDepth.assertNotFreed();
        if (sourceWidth <= 0 || sourceHeight <= 0 || sourceDepth.getWidth() != sourceWidth || sourceDepth.getHeight() != sourceHeight) {
            throw new IllegalArgumentException("HiZ source dimensions do not match the texture");
        }
        int targetWidth = Integer.highestOneBit(sourceWidth);
        int targetHeight = Integer.highestOneBit(sourceHeight);
        if (this.texture == null || this.width != targetWidth || this.height != targetHeight) {
            this.allocate(targetWidth, targetHeight);
        }
        IGpuTexture input = sourceDepth;
        for (IGpuTexture output : this.views) {
            int w = output.getWidth(), h = output.getHeight();
            try (MemoryStack stack = MemoryStack.stackPush(); var encoder = this.backend.beginComputePass()) {
                var parameters = stack.calloc(16);
                parameters.putInt(0, w).putInt(4, h);
                encoder.setPipeline(this.reduction);
                encoder.setTexture(0, input);
                encoder.setSampler(0, this.sampler);
                encoder.setStorageImage(1, output, 0);
                encoder.setBytes(2, MemoryUtil.memAddress(parameters), 16);
                encoder.dispatch((w + 7) / 8, (h + 7) / 8, 1);
            }
            input = output;
        }
    }

    private void allocate(int w, int h) {
        this.backend.finish();
        this.releaseTextures();
        // Preserve the original chain length (omit final 1x1), with a valid level for tiny targets.
        int levels = Math.max(1, 31 - Integer.numberOfLeadingZeros(Math.max(w, h)));
        IGpuTexture allocated = this.backend.createTexture();
        IGpuTexture[] allocatedViews = new IGpuTexture[levels];
        try {
            allocated.store(0x822e, levels, w, h).name("HiZ");
            for (int i = 0; i < levels; i++) allocatedViews[i] = allocated.createView(i, 1);
        } catch (RuntimeException | Error failure) {
            for (IGpuTexture view : allocatedViews) if (view != null) view.free();
            allocated.free();
            throw failure;
        }
        this.texture = allocated;
        this.views = allocatedViews;
        this.width = w;
        this.height = h;
    }

    public IGpuTexture getTexture() {
        if (this.texture == null) throw new IllegalStateException("HiZ has not been built");
        return this.texture;
    }

    public int getPackedLevels() { return (this.width << 16) | this.height; }

    private void releaseTextures() {
        if (this.views != null) {
            for (IGpuTexture view : this.views) view.free();
            this.views = null;
        }
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
    }

    @Override public void close() {
        this.backend.finish();
        this.releaseTextures();
        this.sampler.close();
        this.reduction.close();
    }
}
