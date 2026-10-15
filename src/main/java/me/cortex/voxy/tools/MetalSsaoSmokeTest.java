package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.*;
import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

/** Executes the current SSAO kernel, comparing basic AO with an independent CPU implementation. */
public final class MetalSsaoSmokeTest {
    public static void main(String[] args) {
        var backend = new MetalRenderBackend();
        int buffers = MetalBuffer.getCount(), textures = MetalTexture.getCount();
        try {
            for (boolean reverse : new boolean[]{false, true}) for (boolean zero : new boolean[]{false, true}) {
                var properties = new RenderProperties(zero, reverse, false);
                var viewport = new MDICViewport(properties, 4, backend);
                try {
                    for (int[] size : new int[][]{{32, 32}, {33, 17}}) {
                        viewport.setScreenSize(size[0], size[1]).update();
                        run(backend, viewport, SSAO.SSAOMode.BASIC, false);
                        run(backend, viewport, SSAO.SSAOMode.BETTER, true);
                        run(backend, viewport, SSAO.SSAOMode.BEST, true);
                    }
                } finally { viewport.delete(); }
                if (MetalBuffer.getCount() != buffers || MetalTexture.getCount() != textures) throw new AssertionError("SSAO resource leak");
            }
            System.out.println("Metal SSAO OK: basic AO matches CPU at every pixel, 12/24-sample flat planes, all faces/depth conventions, metadata conversion and BGRA IOSurface writes");
        } finally { backend.shutdown(); }
    }

    private static void run(MetalRenderBackend backend, MDICViewport viewport, SSAO.SSAOMode mode, boolean flat) {
        int w = viewport.width, h = viewport.height, count = w * h;
        var colors = backend.createTexture().storeUploadable(0x8058, 1, w, h);
        var depth = backend.createTexture().storeUploadable(0x822e, 1, w, h);
        var vanilla = backend.createTexture().storeUploadable(0x822e, 1, w, h);
        var pixels = new MemoryBuffer(count * 4L);
        var depths = new MemoryBuffer(count * 4L);
        try (var output = IOSurfaceBridge.create(backend.device(), w, h, IOSurfaceBridge.IOSurfaceFormat.BGRA8);
             var ssao = new GpuSsao(backend, viewport.properties, mode)) {
            byte[] input = new byte[count * 4]; float[] values = new float[count];
            for (int i = 0; i < count; i++) {
                input[i * 4] = (byte) 190; input[i * 4 + 1] = (byte) 140; input[i * 4 + 2] = 70;
                input[i * 4 + 3] = (byte) ((i % 6) | (i % 11 == 0 ? 0 : 64));
                values[i] = flat ? 0.5f : i % 17 == 0 ? 0 : i % 19 == 0 ? 1
                        : 0.25f + ((i % w * 7 + i / w * 11) % 13) / 26f;
                MemoryUtil.memPutFloat(depths.address + i * 4L, values[i]);
            }
            if (!flat) for (int c = 0; c < 4; c++) input[5 * 4 + c] = 0; // Existing valid-depth / missing-color debug behavior.
            MemoryUtil.memByteBuffer(pixels.address, count * 4).put(input);
            colors.uploadSubImage2D(0, 0, 0, w, h, 0x1908, 0x1401, pixels.address);
            depth.uploadSubImage2D(0, 0, 0, w, h, 0x1903, 0x1406, depths.address);
            vanilla.uploadSubImage2D(0, 0, 0, w, h, 0x1903, 0x1406, depths.address);
            ssao.compute(viewport, output.asGpuTexture(), colors, depth, vanilla);
            byte[] image = backend.readPixelsRGBA8(output.asGpuTexture(), 0, 0, w, h);
            int occluded = 0;
            for (int i = 0; i < count; i++) {
                float factor = flat ? 1 : referenceAO(i, w, h, values, input, viewport.properties.isZero2One());
                if (factor < 0.99f) occluded++;
                boolean valid = values[i] != 0 && values[i] != 1;
                boolean missing = input[i * 4] == 0 && input[i * 4 + 1] == 0 && input[i * 4 + 2] == 0 && input[i * 4 + 3] == 0;
                for (int c = 0; c < 4; c++) {
                    int expected = !valid ? 0 : c == 3 ? 255 : missing ? c == 0 ? 255 : 0 : Math.round((input[i * 4 + c] & 255) * factor);
                    if (Math.abs((image[i * 4 + c] & 255) - expected) > 2) throw new AssertionError(mode + " SSAO pixel=" + i + " channel=" + c + " expected=" + expected + " actual=" + (image[i * 4 + c] & 255));
                }
            }
            if (!flat && occluded == 0) throw new AssertionError("AO test never exercised occlusion");
        } finally { pixels.free(); depths.free(); vanilla.free(); depth.free(); colors.free(); }
    }

    private static float referenceAO(int i, int w, int h, float[] depths, byte[] colors, boolean zeroOne) {
        int metadata = colors[i * 4 + 3] & 255;
        if ((metadata & 64) == 0 || depths[i] == 0 || depths[i] == 1) return 1;
        int face = metadata & 7, sign = (face & 1) * 2 - 1;
        int nx = (face >> 1) == 2 ? sign : 0, ny = (face >> 1) == 0 ? sign : 0, nz = (face >> 1) == 1 ? sign : 0;
        float u = (i % w + 0.5f) / w, v = (i / w + 0.5f) / h;
        float sampleU = Math.max(0, Math.min(1, ((int) ((u + nx * 0.25f) * w) + 0.5f) / w));
        float sampleV = Math.max(0, Math.min(1, ((int) ((v + ny * 0.25f) * h) + 0.5f) / h));
        int x = Math.max(0, Math.min(w - 1, (int) (sampleU * w)));
        int y = Math.max(0, Math.min(h - 1, (int) (sampleV * h)));
        int sample = y * w + x;
        if (depths[sample] == 0 || depths[sample] == 1 || ((colors[sample * 4 + 3] & 255) >> 6) == 0) return 1;
        float dx = (sampleU - u) * 2, dy = (sampleV - v) * 2;
        float dz = (depths[sample] - depths[i]) * (zeroOne ? 1 : 2);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.01f || length > 2.5f) return 1;
        float angle = (dx * nx + dy * ny + dz * nz) / length;
        if (angle < 0.01f) return 1;
        return Math.max(0, Math.min(1, (1 - (float) Math.pow(angle, 0.15f)) / 3f + 2f / 3f));
    }
}
