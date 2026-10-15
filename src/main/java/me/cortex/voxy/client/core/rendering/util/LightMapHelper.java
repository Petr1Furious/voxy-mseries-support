package me.cortex.voxy.client.core.rendering.util;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Minecraft;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL33.glGenSamplers;

public class LightMapHelper {
    // The Metal path only needs the Minecraft texture ID, without initializing any GL draw resources.
    private static final class GlSampler {
        static final int ID = create();
    }

    private static int create() {
        int sampler = glGenSamplers();
        glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        return sampler;
    }

    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, GlSampler.ID);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        return ((GlTexture)(Minecraft.getInstance().gameRenderer.levelLightmap().texture())).glId();
    }
}