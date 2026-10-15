package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.bounding.BoundRenderer;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Actual visible-chunk bounds raster, packed positions and streamed-store growth on M3. */
public final class MetalBoundsSmokeTest {
    public static void main(String[] args) {
        var backend = new MetalRenderBackend();
        int buffers = MetalBuffer.getCount(), textures = MetalTexture.getCount();
        try {
            for (boolean reverse : new boolean[]{false, true}) for (boolean zero : new boolean[]{false, true}) {
                var properties = new RenderProperties(zero, reverse, false);
                var viewport = new MDICViewport(properties, 4, backend);
                try (var uploads = new UploadStream(backend, 2 << 20)) {
                    var store = new StreamedBoundStore(backend, uploads);
                    var bounds = new BoundRenderer(properties, backend, uploads);
                    try {
                        viewport.setScreenSize(64, 64).setProjection(new Matrix4f().m00(0.0625f).m11(0.0625f)
                                .m22(reverse ? 0.025f : -0.025f).m30(-0.5f).m31(-0.5f)
                                .m32(zero ? reverse ? 0.2f : 0.8f : reverse ? -0.6f : 0.6f), 0.1f, 128);
                        for (int origin : new int[]{0, -2}) {
                            // Same local geometry, including negative block-floor / section packing.
                            viewport.setCamera(origin * 16 - 0.25, -0.25, origin * 16 - 0.5).update();
                            for (int count : new int[]{1, 31, 32, 33, 65, 129}) {
                                store.reset();
                                for (int i = 1; i < count; i++) store.put(SectionPos.asLong(100, 0, 100));
                                store.put(SectionPos.asLong(origin, 0, origin));
                                bounds.render(viewport, store, 32);
                                var depth = readDepth(backend, viewport);
                                float expected = zero ? reverse ? 0.213f : 0.787f : reverse ? 0.2065f : 0.7935f;
                                check(depth, 32, 32, expected, "back-face depth, count=" + count + " reverse=" + reverse + " zero=" + zero);
                                check(depth, 3, 3, properties.inverseClearDepth(), "bounds exterior");
                                // Exercise the unchanged-store path; it must retain uploaded section positions.
                                bounds.render(viewport, store, 32);
                                check(readDepth(backend, viewport), 32, 32, expected, "unchanged store");
                            }
                        }
                        store.reset();
                        store.put(SectionPos.asLong(100, 0, 100));
                        bounds.render(viewport, store, 32);
                        checkAll(readDepth(backend, viewport), properties.inverseClearDepth(), "distance rejected");
                        store.reset();
                        bounds.render(viewport, store, 32);
                        checkAll(readDepth(backend, viewport), properties.inverseClearDepth(), "empty store clears previous depth");

                        // Grow beyond the initial 4096 sections; inspect every packed GPU position.
                        for (int i = 0; i < 5000; i++) store.put(SectionPos.asLong(i - 2500, (i % 32) - 16, 2500 - i));
                        store.preRender(viewport);
                        backend.finish();
                        if (store.getCount() != 5000 || store.getBuffer().size() < 40000) throw new AssertionError("Bound-store growth");
                        long ptr = ((MetalBuffer) store.getBuffer()).getContentsPtr();
                        for (int i = 0; i < 5000; i++) {
                            long expected = SectionPos.asLong(i - 2500, (i % 32) - 16, 2500 - i);
                            if (MemoryUtil.memGetLong(ptr + i * 8L) != expected) throw new AssertionError("Packed chunk position " + i);
                        }
                    } finally {
                        bounds.free(); store.free();
                    }
                } finally {
                    viewport.delete();
                }
                if (MetalBuffer.getCount() != buffers || MetalTexture.getCount() != textures) throw new AssertionError("Chunk bounds resource leak");
            }
            System.out.println("Metal chunk bounds OK: back-face depth, clip conventions, negative positions, distance rejection, 32-instance boundaries, streaming growth and lifetime");
        } finally {
            backend.shutdown();
        }
    }

    private static ByteBuffer readDepth(MetalRenderBackend backend, MDICViewport viewport) {
        return ByteBuffer.wrap(backend.readPixelsRGBA8(viewport.depthBoundingBuffer.getGpuDepthTex(), 0, 0, 64, 64))
                .order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void check(ByteBuffer pixels, int x, int y, float expected, String label) {
        float actual = pixels.getFloat((y * 64 + x) * 4);
        if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.00001f) throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
    }

    private static void checkAll(ByteBuffer pixels, float expected, String label) {
        for (int i = 0; i < 64 * 64; i++) {
            if (pixels.getFloat(i * 4) != expected) throw new AssertionError(label + " at pixel " + i);
        }
    }
}
