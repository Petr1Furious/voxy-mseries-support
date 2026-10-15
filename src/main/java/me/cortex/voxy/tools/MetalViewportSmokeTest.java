package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exercises production viewport allocation, clip planes and cleared depth on a real Metal device. */
public final class MetalViewportSmokeTest {
    public static void main(String[] args) {
        var backend = new MetalRenderBackend();
        int buffers = MetalBuffer.getCount(), textures = MetalTexture.getCount();
        try {
            for (boolean reverse : new boolean[]{false, true}) {
                for (boolean zeroToOne : new boolean[]{false, true}) {
                    var properties = new RenderProperties(zeroToOne, reverse, false);
                    var viewport = new MDICViewport(properties, 16, backend);
                    try {
                        if (!(viewport.getRenderList() instanceof MetalBuffer)) throw new AssertionError("GL render list");
                        viewport.setCamera(-0.25, 33.75, -32.25);
                        for (int[] size : new int[][]{{64, 32}, {65, 37}, {128, 16}, {65, 37}, {65, 37}, {1, 1}}) {
                            viewport.setScreenSize(size[0], size[1]).update();
                            if (viewport.section.x != -1 || viewport.section.y != 1 || viewport.section.z != -2
                                    || viewport.innerTranslation.x != 31.75f || viewport.innerTranslation.y != 1.75f
                                    || viewport.innerTranslation.z != 31.75f) throw new AssertionError("Negative camera section split");
                            var depth = viewport.depthBoundingBuffer.getGpuDepthTex();
                            if (!(depth instanceof MetalTexture) || depth.getWidth() != size[0] || depth.getHeight() != size[1]) {
                                throw new AssertionError("Depth allocation dimensions");
                            }
                            viewport.update();
                            if (depth != viewport.depthBoundingBuffer.getGpuDepthTex()) throw new AssertionError("Unneeded depth reallocation");
                            var near = viewport.frustumPlanes[4];
                            float outside = reverse ? 1.1f : zeroToOne ? -0.1f : -1.1f;
                            if (near.z * 0.5f + near.w <= 0 || near.z * outside + near.w >= 0) {
                                throw new AssertionError("Near plane does not match active depth convention");
                            }
                            viewport.hiZBuffer.buildMipChain(depth, size[0], size[1]);
                            backend.finish();
                            var pyramid = viewport.hiZBuffer.getTexture();
                            var view = pyramid.createView(pyramid.getLevels() - 1, 1);
                            try {
                                var actual = ByteBuffer.wrap(backend.readPixelsRGBA8(view, 0, 0,
                                        view.getWidth(), view.getHeight())).order(ByteOrder.LITTLE_ENDIAN);
                                while (actual.hasRemaining()) {
                                    if (actual.getFloat() != properties.inverseClearDepth()) throw new AssertionError("Depth clear / HiZ mismatch");
                                }
                            } finally {
                                view.free();
                            }
                        }
                        var depth = viewport.depthBoundingBuffer.getGpuDepthTex();
                        try {
                            viewport.depthBoundingBuffer.resize(0, 16);
                            throw new AssertionError("Invalid dimensions accepted");
                        } catch (IllegalArgumentException expected) {
                            if (depth != viewport.depthBoundingBuffer.getGpuDepthTex()) throw new AssertionError("Invalid resize lost depth");
                        }
                    } finally {
                        viewport.delete();
                    }
                    if (MetalBuffer.getCount() != buffers || MetalTexture.getCount() != textures) throw new AssertionError("Viewport resource leak");
                }
            }
            System.out.println("Metal viewport OK: depth clear, HiZ, clip conventions, camera split, resize and resource lifetime");
        } finally {
            backend.shutdown();
        }
    }
}
