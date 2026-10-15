package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.GlobalCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.lang.ref.Cleaner;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore {
    public static final int MODEL_SIZE = 64;
    private Cleaner.Cleanable ref;
    final IGpuBuffer modelBuffer;
    final IGpuBuffer modelColourBuffer;
    final IGpuTexture textures;
    public final int blockSampler;
    private final RenderBackend backend;
    private final IGpuSampler gpuSampler;
    private final UploadStream uploads;

    public ModelStore() {
        this.backend = null;
        this.gpuSampler = null;
        this.uploads = UploadStream.getInstance();
        this.blockSampler = glGenSamplers();
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        GlTexture tex = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
        this.textures = tex;
        this.ref = GlobalCleaner.CLEANER.register(this, ()->RenderResourceReuse.giveBackModelStoreTextureAtlas(tex));

        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .maxMipLevel;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
    }

    /** Full 16-bit model atlas with CPU-uploadable storage for the 26.3 software bakery. */
    public ModelStore(RenderBackend backend, UploadStream uploads, int maxMipLevel) {
        this.backend = backend;
        this.uploads = uploads;
        this.blockSampler = 0;
        this.modelBuffer = backend.createBuffer(MODEL_SIZE * (1 << 16)).name("ModelData");
        IGpuBuffer colors = null;
        IGpuTexture atlas = null;
        IGpuSampler sampler = null;
        try {
            colors = backend.createBuffer(4 * (1 << 16)).name("ModelColour");
            atlas = backend.createTexture();
            atlas.storeUploadable(0x8058, ModelFactory.LAYERS,
                    ModelFactory.MODEL_TEXTURE_SIZE * 3 * 256, ModelFactory.MODEL_TEXTURE_SIZE * 2 * 256);
            atlas.name("ModelTextures");
            sampler = backend.createSampler(SamplerDesc.builder().filter(SamplerDesc.Filter.NEAREST, SamplerDesc.Filter.NEAREST)
                    .mipFilter(SamplerDesc.MipFilter.LINEAR).lod(0, Math.max(0, Math.min(maxMipLevel, ModelFactory.LAYERS - 1)))
                    .label("ModelSampler").build());
        } catch (RuntimeException | Error failure) {
            if (sampler != null) sampler.close();
            if (atlas != null) atlas.free();
            if (colors != null) colors.free();
            this.modelBuffer.free();
            throw failure;
        }
        this.modelColourBuffer = colors;
        this.textures = atlas;
        this.gpuSampler = sampler;
    }

    public boolean usesGpuBackend() { return this.backend != null; }

    public UploadStream getUploadStream() { return this.uploads; }


    public void free() {
        if (this.backend != null) {
            this.uploads.commit();
            this.backend.finish();
        }
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        if (this.backend == null) {
            this.ref.clean();
            glDeleteSamplers(this.blockSampler);
        } else {
            this.textures.free();
            this.gpuSampler.close();
        }
    }


    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        if (this.backend != null) throw new IllegalStateException("GPU model store must bind through a render encoder");
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id());
        glBindTextureUnit(textureBindingIndex, this.textures.id());
        glBindSampler(textureBindingIndex, this.blockSampler);
    }

    public void bind(RenderEncoder encoder, int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        if (this.backend == null) throw new IllegalStateException("GL model store uses GL bindings");
        encoder.setBuffer(modelBindingIndex, this.modelBuffer, 0);
        encoder.setBuffer(colourBindingIndex, this.modelColourBuffer, 0);
        encoder.setTexture(textureBindingIndex, this.textures);
        encoder.setSampler(textureBindingIndex, this.gpuSampler);
    }
}
