package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.model.ModelTextureUploader;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

/** Verifies the actual software-bakery upload path at every mip and across atlas rows/columns. */
public final class MetalModelTexturesSmokeTest {
    public static void main(String[] args) {
        MetalRenderBackend backend = new MetalRenderBackend();
        var atlas = backend.createTexture().storeUploadable(0x8058, 4, 96, 64);
        try {
            for (int tile = 0; tile < 4; tile++) {
                var pixels = new MemoryBuffer(8160);
                try {
                    long ptr = pixels.address;
                    for (int level = 0; level < 4; level++) {
                        int width = 48 >> level;
                        int height = 32 >> level;
                        for (int i = 0; i < width * height; i++) {
                            MemoryUtil.memPutByte(ptr + i * 4L, (byte) (32 + tile * 40));
                            MemoryUtil.memPutByte(ptr + i * 4L + 1, (byte) (40 + level * 40));
                            MemoryUtil.memPutByte(ptr + i * 4L + 2, (byte) 160);
                            MemoryUtil.memPutByte(ptr + i * 4L + 3, (byte) 255);
                        }
                        ptr += width * height * 4L;
                    }
                    ModelTextureUploader.upload(atlas, (tile & 1) | ((tile >> 1) << 8), true, pixels);
                } finally {
                    pixels.free();
                }
            }
            for (int level = 0; level < 4; level++) {
                var view = atlas.createView(level, 1);
                try {
                    int width = 96 >> level;
                    int height = 64 >> level;
                    if (view.getWidth() != width || view.getHeight() != height) {
                        throw new IllegalStateException("Mip view reports the parent dimensions");
                    }
                    byte[] actual = backend.readPixelsRGBA8(view, 0, 0, width, height);
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int tile = (x / (48 >> level)) + (y / (32 >> level)) * 2;
                            int offset = (y * width + x) * 4;
                            if ((actual[offset] & 255) != 32 + tile * 40
                                    || (actual[offset + 1] & 255) != 40 + level * 40
                                    || (actual[offset + 2] & 255) != 160 || (actual[offset + 3] & 255) != 255) {
                                throw new IllegalStateException("Software model upload mismatch at mip " + level + " x=" + x + " y=" + y);
                            }
                        }
                    }
                } finally {
                    view.free();
                }
            }
            var incomplete = new MemoryBuffer(4);
            try {
                try {
                    ModelTextureUploader.upload(atlas, 0, true, incomplete);
                    throw new IllegalStateException("Incomplete pixel buffer accepted");
                } catch (IllegalArgumentException expected) {}
            } finally {
                incomplete.free();
            }
            // Also verify allocation/lifetime of the production 16-bit model store
            // (full atlas + model/color buffers + immutable Metal sampler).
            int bufferCount = MetalBuffer.getCount();
            int textureCount = MetalTexture.getCount();
            try (var uploads = new UploadStream(backend, 4096)) {
                var store = new ModelStore(backend, uploads, 3);
                store.free();
            }
            if (MetalBuffer.getCount() != bufferCount || MetalTexture.getCount() != textureCount) {
                throw new IllegalStateException("GPU model store leaked resources");
            }
            System.out.println("Metal model textures OK: bakery tiles/mips, mip-view dimensions and full model store lifetime");
        } finally {
            atlas.free();
            backend.shutdown();
        }
    }
}
