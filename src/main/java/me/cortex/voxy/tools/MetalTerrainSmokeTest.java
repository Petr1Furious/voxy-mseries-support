package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.*;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Consumer;

/** Checks pixels from production terrain shaders and actual GPU-generated indirect quad draws. */
public final class MetalTerrainSmokeTest {
    public static void main(String[] args) {
        var backend = new MetalRenderBackend();
        int buffers = MetalBuffer.getCount(), textures = MetalTexture.getCount();
        try {
            for (boolean reverse : new boolean[]{false, true}) {
                for (boolean zero : new boolean[]{false, true}) {
                    run(backend, new RenderProperties(zero, reverse, false), null, image -> {});
                    if (MetalBuffer.getCount() != buffers || MetalTexture.getCount() != textures) throw new AssertionError("Terrain resource leak");
                }
            }
            System.out.println("Metal terrain OK: production quad/atlas/lightmap shaders, nonzero baseInstance, cutout, translucency, bounds and depth ordering");
        } finally {
            backend.shutdown();
        }
    }

    static void run(MetalRenderBackend backend, RenderProperties properties, IGpuTexture externalTarget, Consumer<byte[]> verifySharedImage) {
        var viewport = new MDICViewport(properties, 4, backend);
        var metadata = backend.createBuffer(128);
        var geometry = backend.createBuffer(16);
        var models = backend.createBuffer(128);
        var colors = backend.createBuffer(16);
        // One pixel per face, same fixed 256-model grid as production. Tiny fixtures avoid a 510 MiB atlas per test.
        var atlas = backend.createTexture().storeUploadable(0x8058, 1, 768, 512);
        var lightmap = backend.createTexture().storeUploadable(0x8058, 1, 16, 16);
        var target = externalTarget == null ? backend.createTexture().store(0x8058, 1, 128, 64) : externalTarget;
        var depth = backend.createTexture().store(0x8cac, 1, 128, 64);
        var sampler = backend.createSampler(SamplerDesc.builder().build());
        ByteBuffer pixels = MemoryUtil.memAlloc(768 * 512 * 4);
        try (var uploads = new UploadStream(backend, 2 << 20);
             var calls = new GpuMDICDrawCalls(backend, uploads, properties);
             var terrain = new GpuMDICTerrain(backend, properties, Map.of("NO_SHADE_FACE_TINT", "1.0",
                     "UP_FACE_TINT", "1.0", "DOWN_FACE_TINT", "0.5", "X_AXIS_FACE_TINT", "0.6", "Z_AXIS_FACE_TINT", "0.8"), target.getFormat())) {
            viewport.setScreenSize(128, 64).setProjection(new Matrix4f().m00(0.04f).m11(0.2f)
                    .m22(0.05f).m30(-0.9f).m31(-0.9f).m32(properties.isZero2One() ? 0.3f : -0.4f), 0.1f, 200).update();
            long ptr = uploads.upload(models, 0, models.size());
            MemoryUtil.memSet(ptr, 0, models.size());
            for (int model = 0; model < 2; model++) {
                for (int face = 0; face < 6; face++) MemoryUtil.memPutInt(ptr + model * 64L + face * 4L, 0xf0f0);
                MemoryUtil.memPutInt(ptr + model * 64L + 28, -1);
            }
            MemoryUtil.memSet(uploads.upload(colors, 0, colors.size()), 0, colors.size());
            writeGeometry(uploads, geometry, 3, 3);
            writeMetadata(uploads, metadata, false, false);
            ptr = uploads.upload(viewport.indirectLookupBuffer, 0, 12);
            MemoryUtil.memPutInt(ptr, 2);
            MemoryUtil.memPutInt(ptr + 4, 0);
            MemoryUtil.memPutInt(ptr + 8, 1);
            MemoryUtil.memSet(uploads.upload(viewport.visibilityBuffer, 0, viewport.visibilityBuffer.size()), 0, viewport.visibilityBuffer.size());
            MemoryUtil.memSet(MemoryUtil.memAddress(pixels), 0, pixels.capacity());
            setPixel(pixels, 1, 255, 0, 0, 255);
            setPixel(pixels, 4, 0, 0, 255, 255);
            atlas.uploadSubImage2D(0, 0, 0, 768, 512, 0x1908, 0x1401, MemoryUtil.memAddress(pixels));
            MemoryUtil.memSet(MemoryUtil.memAddress(pixels), 255, 16 * 16 * 4);
            lightmap.uploadSubImage2D(0, 0, 0, 16, 16, 0x1908, 0x1401, MemoryUtil.memAddress(pixels));
            Consumer<RenderEncoder> bindings = encoder -> {
                encoder.setBuffer(3, models, 0); encoder.setBuffer(4, colors, 0);
                encoder.setTexture(0, atlas); encoder.setSampler(0, sampler);
            };
            clearDepth(backend, depth, properties.clearDepth());
            viewport.frameId = 7;
            calls.build(viewport, metadata, depth);
            if (MemoryUtil.memGetInt(((MetalBuffer) viewport.drawCountCallBuffer).getContentsPtr() + 12) != 2) {
                throw new AssertionError("Terrain fixtures failed visibility raster");
            }
            render(terrain, GpuMDICTerrain.Layer.OPAQUE, viewport, calls, geometry, bindings, lightmap, target, depth, properties);
            byte[] image = readImage(backend, target, verifySharedImage);
            checkRGB(image, 17, 32, 255, 0, 0, "first section atlas");
            checkRGB(image, 99, 32, 0, 0, 255, "nonzero baseInstance atlas");
            checkRGB(image, 3, 3, 0, 0, 0, "background");
            checkRGB(image, 13, 17, 255, 0, 0, "first triangle / raw row orientation");
            checkRGB(image, 20, 40, 255, 0, 0, "second triangle flat attributes");
            checkRGB(image, 95, 17, 0, 0, 255, "nonzero baseInstance first triangle");
            checkRGB(image, 102, 40, 0, 0, 255, "nonzero baseInstance second triangle");
            checkRGB(image, 17, 47, 0, 0, 0, "raw row orientation exterior");

            // Exercise the last full 16-bit metadata quad group through UInt32 indices.
            // A negative baseVertex maps its high vertex IDs onto our two tiny geometry fixtures.
            long commandAddress = ((MetalBuffer) viewport.drawCallBuffer).getContentsPtr();
            for (int i = 0; i < 2; i++) {
                int vertex = MemoryUtil.memGetInt(commandAddress + i * 20L + 12);
                long command = uploads.upload(viewport.drawCallBuffer, i * 20L + 8, 8);
                MemoryUtil.memPutInt(command, 65534 * 6);
                MemoryUtil.memPutInt(command + 4, vertex - 65534 * 4);
            }
            uploads.commit();
            render(terrain, GpuMDICTerrain.Layer.OPAQUE, viewport, calls, geometry, bindings, lightmap, target, depth, properties);
            image = readImage(backend, target, verifySharedImage);
            checkRGB(image, 17, 32, 255, 0, 0, "large quad-group UInt32 indices");
            checkRGB(image, 99, 32, 0, 0, 255, "large quad-group nonzero baseInstance");

            // Lightmap and the active world's north-face tint must affect actual atlas shading.
            for (int i = 0; i < 256; i++) setPixel(pixels, i, 128, 255, 64, 255);
            lightmap.uploadSubImage2D(0, 0, 0, 16, 16, 0x1908, 0x1401, MemoryUtil.memAddress(pixels));
            MemoryUtil.memPutInt(uploads.upload(models, 24, 4), 8);
            uploads.commit();
            render(terrain, GpuMDICTerrain.Layer.OPAQUE, viewport, calls, geometry, bindings, lightmap, target, depth, properties);
            image = readImage(backend, target, verifySharedImage);
            checkRGB(image, 17, 32, 102, 0, 0, "lightmap and north-face tint");
            checkRGB(image, 99, 32, 0, 0, 64, "unshaded lightmap");
            MemoryUtil.memSet(MemoryUtil.memAddress(pixels), 255, 16 * 16 * 4);
            lightmap.uploadSubImage2D(0, 0, 0, 16, 16, 0x1908, 0x1401, MemoryUtil.memAddress(pixels));
            MemoryUtil.memPutInt(uploads.upload(models, 24, 4), 0);

            // Model 1 is cutout: transparent atlas texels must discard without erasing other sections.
            MemoryUtil.memPutInt(uploads.upload(models, 64 + 2 * 4, 4), 0xf0f0 | (1 << 22));
            uploads.commit();
            setPixel(pixels, 0, 0, 0, 255, 0);
            atlas.uploadSubImage2D(0, 4, 0, 1, 1, 0x1908, 0x1401, MemoryUtil.memAddress(pixels));
            render(terrain, GpuMDICTerrain.Layer.OPAQUE, viewport, calls, geometry, bindings, lightmap, target, depth, properties);
            image = readImage(backend, target, verifySharedImage);
            checkRGB(image, 17, 32, 255, 0, 0, "cutout retained first section");
            checkRGB(image, 99, 32, 0, 0, 0, "cutout discarded");

            // Same atlas colour with alpha 128, emitted by the genuine translucent command path.
            setPixel(pixels, 0, 0, 0, 255, 128);
            atlas.uploadSubImage2D(0, 4, 0, 1, 1, 0x1908, 0x1401, MemoryUtil.memAddress(pixels));
            MemoryUtil.memPutInt(uploads.upload(models, 64 + 24, 4), 4);
            writeMetadata(uploads, metadata, true, false);
            uploads.commit();
            clearDepth(backend, depth, properties.clearDepth());
            viewport.frameId++;
            calls.build(viewport, metadata, depth);
            render(terrain, GpuMDICTerrain.Layer.TRANSLUCENT, viewport, calls, geometry, bindings, lightmap, target, depth, properties);
            image = readImage(backend, target, verifySharedImage);
            checkRGB(image, 99, 32, 0, 0, 128, "translucent alpha blend");
            var translucentDepth = java.nio.ByteBuffer.wrap(backend.readPixelsRGBA8(depth, 0, 0, 128, 64))
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            float expectedTranslucentDepth = properties.isZero2One() ? 0.45f : 0.375f;
            if (Math.abs(translucentDepth.getFloat((32 * 128 + 99) * 4) - expectedTranslucentDepth) > 0.000001f) {
                throw new AssertionError("Translucent terrain did not preserve composition depth");
            }

            viewport.depthBoundingBuffer.clear(properties.clearDepth());
            render(terrain, GpuMDICTerrain.Layer.TRANSLUCENT, viewport, calls, geometry, bindings, lightmap, target, depth, properties);
            image = readImage(backend, target, verifySharedImage);
            checkRGB(image, 99, 32, 0, 0, 0, "fragment depth bounds reject");
            viewport.depthBoundingBuffer.clear(properties.inverseClearDepth());

            // Overlap two opaque quads at different depths; the active depth comparison decides the winner.
            setPixel(pixels, 0, 0, 0, 255, 255);
            atlas.uploadSubImage2D(0, 4, 0, 1, 1, 0x1908, 0x1401, MemoryUtil.memAddress(pixels));
            MemoryUtil.memPutInt(uploads.upload(models, 64 + 24, 4), 0);
            writeMetadata(uploads, metadata, false, true);
            writeGeometry(uploads, geometry, 3, 5);
            uploads.commit();
            clearDepth(backend, depth, properties.clearDepth());
            viewport.frameId++;
            calls.build(viewport, metadata, depth);
            render(terrain, GpuMDICTerrain.Layer.OPAQUE, viewport, calls, geometry, bindings, lightmap, target, depth, properties);
            image = readImage(backend, target, verifySharedImage);
            checkRGB(image, 17, 32, properties.isReverseZ() ? 0 : 255, 0,
                    properties.isReverseZ() ? 255 : 0, "depth ordering");
            // Preserve vanilla stencil exclusion without relying only on equal-near depth rejection.
            var vanillaMask = backend.createTexture().storeUploadable(0x822e, 1, 128, 64);
            try (var maskedTerrain = new GpuMDICTerrain(backend, properties, Map.of("NO_SHADE_FACE_TINT", "1.0",
                    "UP_FACE_TINT", "1.0", "DOWN_FACE_TINT", "0.5", "X_AXIS_FACE_TINT", "0.6", "Z_AXIS_FACE_TINT", "0.8"), target.getFormat(), true)) {
                for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) {
                    MemoryUtil.memPutFloat(MemoryUtil.memAddress(pixels) + (y * 128L + x) * 4, x < 64 ? 0.5f : properties.clearDepth());
                }
                vanillaMask.uploadSubImage2D(0, 0, 0, 128, 64, 0x1903, 0x1406, MemoryUtil.memAddress(pixels));
                maskedTerrain.setVanillaDepth(vanillaMask);
                writeMetadata(uploads, metadata, false, false); uploads.commit();
                clearDepth(backend, depth, properties.clearDepth()); viewport.frameId++;
                calls.build(viewport, metadata, depth);
                render(maskedTerrain, GpuMDICTerrain.Layer.OPAQUE, viewport, calls, geometry, bindings, lightmap, target, depth, properties);
                image = readImage(backend, target, verifySharedImage);
                checkRGB(image, 17, 32, 0, 0, 0, "vanilla geometry exclusion");
                checkRGB(image, 99, 32, 0, 0, 255, "vanilla sky preserves LOD");
            } finally { vanillaMask.free(); }
        } finally {
            backend.finish();
            MemoryUtil.memFree(pixels);
            sampler.close(); depth.free();
            if (externalTarget == null) target.free();
            lightmap.free(); atlas.free();
            colors.free(); models.free(); geometry.free(); metadata.free(); viewport.delete();
        }
    }

    private static byte[] readImage(MetalRenderBackend backend, IGpuTexture target, Consumer<byte[]> verifier) {
        byte[] image = backend.readPixelsRGBA8(target, 0, 0, 128, 64);
        verifier.accept(image);
        return image;
    }

    private static void writeGeometry(UploadStream uploads, IGpuBuffer geometry, int firstZ, int secondZ) {
        long ptr = uploads.upload(geometry, 0, 16);
        for (int i = 0; i < 2; i++) {
            long quad = 2L | (3L << 3) | (3L << 7) | ((i == 0 ? firstZ : secondZ) << 11)
                    | (2L << 16) | (2L << 21) | ((long) i << 26) | (255L << 55);
            MemoryUtil.memPutLong(ptr + 8L * i, quad);
        }
    }

    private static void writeMetadata(UploadStream uploads, IGpuBuffer metadata, boolean translucent, boolean overlap) {
        long ptr = uploads.upload(metadata, 0, 128);
        MemoryUtil.memSet(ptr, 0, 128);
        for (int i = 0; i < 2; i++) {
            MemoryUtil.memPutInt(ptr + i * 32L + 4, overlap ? 0 : i << 4);
            MemoryUtil.memPutInt(ptr + i * 32L + 8, 2 | (2 << 5) | (3 << 10) | (3 << 15) | (3 << 20) | (2 << 25));
            MemoryUtil.memPutInt(ptr + i * 32L + 12, i);
            MemoryUtil.memPutInt(ptr + i * 32L + 16, translucent && i == 1 ? 1 : 1 << 16);
        }
    }

    private static void clearDepth(MetalRenderBackend backend, IGpuTexture depth, float value) {
        try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(128, 64).clearDepth(depth, value).build())) {}
    }

    private static void render(GpuMDICTerrain terrain, GpuMDICTerrain.Layer layer, MDICViewport viewport,
                               GpuMDICDrawCalls calls, IGpuBuffer geometry, Consumer<RenderEncoder> bindings,
                               IGpuTexture lightmap, IGpuTexture target, IGpuTexture depth, RenderProperties properties) {
        terrain.render(layer, viewport, calls, geometry, bindings, lightmap, RenderPassDesc.builder(128, 64)
                .clearColor(target, 0, 0, 0, 0).clearDepth(depth, properties.clearDepth()).build());
    }

    private static void setPixel(ByteBuffer buffer, int pixel, int r, int g, int b, int a) {
        buffer.put(pixel * 4, (byte) r).put(pixel * 4 + 1, (byte) g)
                .put(pixel * 4 + 2, (byte) b).put(pixel * 4 + 3, (byte) a);
    }

    private static void checkRGB(byte[] image, int x, int y, int r, int g, int b, String label) {
        int ptr = (y * 128 + x) * 4;
        int actualR = image[ptr] & 255, actualG = image[ptr + 1] & 255, actualB = image[ptr + 2] & 255;
        if (Math.abs(actualR - r) > 2 || Math.abs(actualG - g) > 2 || Math.abs(actualB - b) > 2) {
            throw new AssertionError(label + " expected=" + r + "," + g + "," + b + " actual="
                    + actualR + "," + actualG + "," + actualB);
        }
    }
}
