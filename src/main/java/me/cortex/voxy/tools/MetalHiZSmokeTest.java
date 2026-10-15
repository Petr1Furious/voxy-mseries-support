package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Compares every GPU mip pixel with a CPU reference of 26.3's conservative textureGather reduction. */
public final class MetalHiZSmokeTest {
    public static void main(String[] args) {
        MetalRenderBackend backend = new MetalRenderBackend();
        int initialTextures = MetalTexture.getCount();
        try {
            for (boolean reverse : new boolean[]{false, true}) {
                for (boolean zeroToOne : new boolean[]{false, true}) {
                    var properties = new RenderProperties(zeroToOne, reverse, false);
                    var hiz = new HiZBuffer(properties, backend);
                    try {
                        int seed = 0;
                        for (int[] dimensions : new int[][]{{1, 1}, {2, 1}, {8, 4}, {65, 37}, {128, 16}, {64, 32}, {65, 37}, {65, 37}}) {
                            int sourceWidth = dimensions[0], sourceHeight = dimensions[1];
                            var source = backend.createTexture().storeUploadable(0x822e, 1, sourceWidth, sourceHeight);
                            var pixels = new MemoryBuffer((long) sourceWidth * sourceHeight * 4);
                            try {
                                float[] reference = new float[sourceWidth * sourceHeight];
                                for (int y = 0; y < sourceHeight; y++) {
                                    for (int x = 0; x < sourceWidth; x++) {
                                        float value = (x + y + seed) % 23 == 0 ? properties.clearDepth()
                                                : (16 + (x * 17 + y * 31 + seed) % 211) / 255.0f;
                                        reference[y * sourceWidth + x] = value;
                                        MemoryUtil.memPutFloat(pixels.address + (y * sourceWidth + x) * 4L, value);
                                    }
                                }
                                source.uploadSubImage2D(0, 0, 0, sourceWidth, sourceHeight, 0x1903, 0x1406, pixels.address);
                                hiz.buildMipChain(source, sourceWidth, sourceHeight);
                                backend.submit();
                                var pyramid = hiz.getTexture();
                                int width = Integer.highestOneBit(sourceWidth), height = Integer.highestOneBit(sourceHeight);
                                if (hiz.getPackedLevels() != ((width << 16) | height)) throw new IllegalStateException("HiZ packed dimensions changed");
                                int previousWidth = sourceWidth, previousHeight = sourceHeight;
                                for (int level = 0; level < pyramid.getLevels(); level++) {
                                    float[] expected = reduce(reference, previousWidth, previousHeight, width, height, reverse);
                                    var view = pyramid.createView(level, 1);
                                    try {
                                        // R32F and RGBA8 both contain four raw bytes per pixel in this blit readback helper.
                                        var actual = ByteBuffer.wrap(backend.readPixelsRGBA8(view, 0, 0, width, height)).order(ByteOrder.LITTLE_ENDIAN);
                                        for (int i = 0; i < expected.length; i++) {
                                            float value = actual.getFloat(i * 4);
                                            if (!Float.isFinite(value) || Math.abs(value - expected[i]) > 0.000001f) {
                                                throw new IllegalStateException("HiZ source=" + sourceWidth + "x" + sourceHeight
                                                        + " reverse=" + reverse + " mip=" + level + " pixel=" + i
                                                        + " expected=" + expected[i] + " actual=" + value);
                                            }
                                        }
                                    } finally {
                                        view.free();
                                    }
                                    reference = expected;
                                    previousWidth = width; previousHeight = height;
                                    width = Math.max(1, width / 2); height = Math.max(1, height / 2);
                                }
                            } finally {
                                pixels.free();
                                source.free();
                            }
                            seed++;
                        }
                        // The input may be an actual Metal depth render target, not only an R32F mirror.
                        var depthTarget = backend.createTexture().store(0x8cac, 1, 32, 16);
                        try {
                            try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(32, 16)
                                    .depthAttachment(depthTarget, 0, RenderPassDesc.LoadAction.CLEAR,
                                            RenderPassDesc.StoreAction.STORE, 0.37f).build())) {}
                            hiz.buildMipChain(depthTarget, 32, 16);
                            backend.submit();
                            var pyramid = hiz.getTexture();
                            var view = pyramid.createView(pyramid.getLevels() - 1, 1);
                            try {
                                var actual = ByteBuffer.wrap(backend.readPixelsRGBA8(view, 0, 0,
                                        view.getWidth(), view.getHeight())).order(ByteOrder.LITTLE_ENDIAN);
                                for (int i = 0; i < actual.capacity(); i += 4) {
                                    if (Math.abs(actual.getFloat(i) - 0.37f) > 0.000001f) {
                                        throw new IllegalStateException("HiZ cannot consume a Metal depth attachment");
                                    }
                                }
                            } finally {
                                view.free();
                            }
                        } finally {
                            depthTarget.free();
                        }
                    } finally {
                        hiz.free();
                    }
                    if (MetalTexture.getCount() != initialTextures) throw new IllegalStateException("HiZ resize/view texture leak");
                }
            }
            System.out.println("Metal HiZ OK: every mip matches CPU depth reduction for reverse-Z, clip ranges, holes, tiny targets and resize");
        } finally {
            backend.shutdown();
        }
    }

    private static float[] reduce(float[] input, int sourceWidth, int sourceHeight, int width, int height, boolean reverse) {
        float[] result = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int baseX = (int) Math.floor(((x + 0.5f) / width) * sourceWidth - 0.5f);
                int baseY = (int) Math.floor(((y + 0.5f) / height) * sourceHeight - 0.5f);
                float depth = reverse ? 1 : 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = Math.max(0, Math.min(sourceWidth - 1, baseX + dx));
                        int sy = Math.max(0, Math.min(sourceHeight - 1, baseY + dy));
                        float sample = input[sy * sourceWidth + sx];
                        depth = reverse ? Math.min(depth, sample) : Math.max(depth, sample);
                    }
                }
                result[y * width + x] = depth;
            }
        }
        return result;
    }
}
