package me.cortex.voxy.client.core.interop;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12C.GL_PACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING;

/** Synchronous atlas download supported by macOS GL 4.1, preserving Minecraft's bindings and pixel-pack state. */
public final class GlTextureReadback {
    private GlTextureReadback() {}
    public static void rgba8(int texture, int mip, int width, int height, int[] pixels) {
        if (texture == 0 || mip < 0 || width <= 0 || height <= 0 || pixels.length < Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("Invalid RGBA8 readback region");
        }
        int binding = glGetInteger(GL_TEXTURE_BINDING_2D), packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] tokens = {GL_PACK_ROW_LENGTH, GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS,
                GL_PACK_SKIP_IMAGES, GL_PACK_ALIGNMENT, GL_PACK_SWAP_BYTES, GL_PACK_LSB_FIRST};
        int[] values = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) values[i] = glGetInteger(tokens[i]);
        try {
            glBindTexture(GL_TEXTURE_2D, texture);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, mip, GL_TEXTURE_WIDTH) != width
                    || glGetTexLevelParameteri(GL_TEXTURE_2D, mip, GL_TEXTURE_HEIGHT) != height) {
                throw new IllegalArgumentException("Atlas dimensions differ from requested mip");
            }
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            for (int token : tokens) glPixelStorei(token, token == GL_PACK_ALIGNMENT ? 4 : 0);
            glGetTexImage(GL_TEXTURE_2D, mip, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        } finally {
            for (int i = 0; i < tokens.length; i++) glPixelStorei(tokens[i], values[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer); glBindTexture(GL_TEXTURE_2D, binding);
        }
    }
}
