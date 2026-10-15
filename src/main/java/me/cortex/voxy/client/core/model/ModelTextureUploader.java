package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.common.util.MemoryBuffer;

import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;

/** Uploads the existing software bakery's six-face/mip layout through either GPU API. */
public final class ModelTextureUploader {
    private ModelTextureUploader() {}

    public static void upload(IGpuTexture atlas, int modelId, boolean hasMips, MemoryBuffer pixels) {
        if (modelId < 0 || modelId >= 65536) throw new IllegalArgumentException("Model ID exceeds atlas capacity");
        atlas.assertNotFreed();
        pixels.assertNotFreed();
        int levels = hasMips ? ModelFactory.LAYERS : 1;
        if (atlas.getLevels() < levels) throw new IllegalArgumentException("Atlas lacks required mip levels");
        int x = (modelId & 255) * ModelFactory.MODEL_TEXTURE_SIZE * 3;
        int y = (modelId >>> 8) * ModelFactory.MODEL_TEXTURE_SIZE * 2;
        int width = ModelFactory.MODEL_TEXTURE_SIZE * 3;
        int height = ModelFactory.MODEL_TEXTURE_SIZE * 2;
        if (x > atlas.getWidth() - width || y > atlas.getHeight() - height) {
            throw new IllegalArgumentException("Model tile exceeds atlas dimensions");
        }
        long requiredBytes = 0;
        for (int level = 0; level < levels; level++) requiredBytes += ((long) width * height * 4) >> (2 * level);
        if (pixels.size < requiredBytes) throw new IllegalArgumentException("Software bakery pixel buffer is too small");
        long address = pixels.address;
        for (int level = 0; level < levels; level++) {
            atlas.uploadSubImage2D(level, x >> level, y >> level, width >> level, height >> level,
                    GL_RGBA, GL_UNSIGNED_BYTE, address);
            address += ((long) width * height * 4) >> (2 * level);
        }
    }
}
