package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedFramebufferfv;
import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedFramebufferiv;
import static org.lwjgl.opengl.GL11C.GL_DEPTH;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.*;

public class DepthFramebuffer {
    private final int depthType;
    private IGpuTexture depthBuffer;
    public final GlFramebuffer framebuffer;
    private final RenderBackend backend;

    public DepthFramebuffer() {
        this(GL_DEPTH_COMPONENT24);
    }

    public DepthFramebuffer(int depthType) {
        this.depthType = depthType;
        this.backend = null;
        this.framebuffer = new GlFramebuffer();
    }

    public DepthFramebuffer(RenderBackend backend) {
        this.backend = java.util.Objects.requireNonNull(backend);
        this.depthType = 0x8cac; // Depth32Float, matching Metal pipeline depth attachments.
        this.framebuffer = null;
    }

    public boolean resize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Depth target requires positive dimensions");
        if (this.depthBuffer == null || this.depthBuffer.getWidth() != width || this.depthBuffer.getHeight() != height) {
            IGpuTexture replacement = this.backend == null ? new GlTexture() : this.backend.createTexture();
            try {
                replacement.store(this.depthType, 1, width, height);
                if (this.backend == null) this.framebuffer.bind(this.getDepthAttachmentType(), (GlTexture) replacement).verify();
                else this.backend.finish();
            } catch (RuntimeException | Error failure) {
                replacement.free();
                throw failure;
            }
            if (this.depthBuffer != null) this.depthBuffer.free();
            this.depthBuffer = replacement;
            //glTextureParameteri(this.depthBuffer.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            //glTextureParameteri(this.depthBuffer.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            return true;
        }
        return false;
    }

    public int getDepthAttachmentType() {
        return this.depthType == GL_DEPTH24_STENCIL8?GL_DEPTH_STENCIL_ATTACHMENT: GL_DEPTH_ATTACHMENT;
    }

    public void clear(float depth) {
        if (this.backend != null) {
            try (var encoder = this.backend.beginRenderPass(RenderPassDesc.builder(this.depthBuffer.getWidth(), this.depthBuffer.getHeight())
                    .clearDepth(this.depthBuffer, depth).build())) {}
            return;
        }
        try (var stack = MemoryStack.stackPush()) {
            nglClearNamedFramebufferfv(this.framebuffer.id, GL_DEPTH, 0, stack.nfloat(depth));
        }
    }

    public void clearStencil(int to) {
        if (this.backend != null) throw new UnsupportedOperationException("Metal Depth32Float targets have no stencil attachment");
        try (var stack = MemoryStack.stackPush()) {
            nglClearNamedFramebufferiv(this.framebuffer.id, GL_STENCIL, 0, stack.nint(to));
        }
    }

    public GlTexture getDepthTex() {
        if (this.backend != null) throw new IllegalStateException("GPU depth targets require GPU texture bindings");
        return (GlTexture) this.depthBuffer;
    }

    public IGpuTexture getGpuDepthTex() { return this.depthBuffer; }

    public void free() {
        if (this.backend == null) this.framebuffer.free();
        else this.backend.finish();
        if (this.depthBuffer != null) {
            this.depthBuffer.free();
        }
    }

    public void bind() {
        if (this.backend != null) throw new IllegalStateException("GPU depth targets require a render pass");
        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
    }

    public int getFormat() {
        return this.depthType;
    }
}
