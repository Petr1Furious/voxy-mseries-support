package me.cortex.voxy.client.core.metal;

import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.common.Logger;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Shows Minecraft's finished frame through a CAMetalLayer instead of OpenGL's default framebuffer.
 * Writing the window framebuffer makes Apple's GL block the CPU until the GPU finishes the frame;
 * a GL flush that only writes an IOSurface does not, so CPU and GPU frames overlap.
 */
public final class MetalPresenter {
    public static final boolean ENABLED = System.getProperty("os.name", "").startsWith("Mac")
            && Boolean.parseBoolean(System.getProperty("voxy.metal.present", "true"));
    private static final int SLOTS = 3;

    private static MetalPresenter instance;
    private static boolean failed;

    private final long window, device, queue, layer;
    private final Slot[] slots = new Slot[SLOTS];
    private final int readFbo = glGenFramebuffers();
    private int width, height, next;
    private Slot pending;

    private static final class Slot {
        IOSurfaceBridge surface;
        int texture, fbo;
        long commandBuffer;
    }

    private MetalPresenter(long window, long device) {
        this.window = window;
        this.device = device;
        this.queue = MetalNative.mtlDeviceNewCommandQueue(device);
        this.layer = MetalNative.mtlPresentLayerCreate(device, window);
        if (this.queue == 0 || this.layer == 0) throw new IllegalStateException("Metal present layer creation failed");
    }

    /** The presenter for this SDL window, or null when presenting stays on OpenGL. */
    public static MetalPresenter get(long sdlWindow) {
        if (!ENABLED || failed) return null;
        if (instance != null) return instance;
        try {
            if (!MetalNative.load()) throw new IllegalStateException("libvoxy_metal unavailable");
            long window = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(sdlWindow), SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0);
            long device = MetalNative.mtlCreateSystemDefaultDevice();
            if (window == 0 || device == 0) throw new IllegalStateException("No NSWindow or MTLDevice");
            instance = new MetalPresenter(window, device);
            Logger.info("Presenting frames through Metal");
        } catch (Throwable t) {
            failed = true;
            Logger.error("Metal presentation unavailable, using OpenGL", t);
        }
        return instance;
    }

    public void configure(int width, int height, boolean vsync) {
        MetalNative.mtlPresentLayerConfigure(this.layer, this.window, width, height, vsync);
        if (width == this.width && height == this.height) return;
        this.width = width;
        this.height = height;
        for (int i = 0; i < SLOTS; i++) {
            if (this.slots[i] != null) free(this.slots[i]);
            this.slots[i] = allocate(width, height);
        }
        this.pending = null;
    }

    private Slot allocate(int width, int height) {
        var slot = new Slot();
        slot.surface = IOSurfaceBridge.create(this.device, width, height, IOSurfaceBridge.IOSurfaceFormat.BGRA8);
        int previous = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        slot.texture = glGenTextures();
        if (!slot.surface.bindToGlTexture(slot.texture)) throw new IllegalStateException("CGL IOSurface bind failed");
        glBindTexture(GL_TEXTURE_RECTANGLE, previous);
        slot.fbo = glGenFramebuffers();
        int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, slot.fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, slot.texture, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        return slot;
    }

    private void free(Slot slot) {
        if (slot.commandBuffer != 0) {
            MetalNative.mtlCommandBufferWaitUntilCompleted(slot.commandBuffer);
            MetalNative.mtlRelease(slot.commandBuffer);
        }
        glDeleteFramebuffers(slot.fbo);
        glDeleteTextures(slot.texture);
        slot.surface.close();
    }

    /** Copies a GL texture into the next IOSurface; flipped, since Metal's row 0 is the top. */
    public void blit(int glTexture, int srcWidth, int srcHeight) {
        if (this.width == 0) return;
        var slot = this.slots[this.next];
        this.next = (this.next + 1) % SLOTS;
        if (slot.commandBuffer != 0) {
            MetalNative.mtlCommandBufferWaitUntilCompleted(slot.commandBuffer);
            MetalNative.mtlRelease(slot.commandBuffer);
            slot.commandBuffer = 0;
        }
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        if (scissor) glDisable(GL_SCISSOR_TEST);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, this.readFbo);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, glTexture, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, slot.fbo);
        int w = Math.min(srcWidth, this.width), h = Math.min(srcHeight, this.height);
        glBlitFramebuffer(0, 0, w, h, 0, h, w, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        if (scissor) glEnable(GL_SCISSOR_TEST);
        this.pending = slot;
    }

    public void present() {
        var slot = this.pending;
        this.pending = null;
        if (slot == null) return;
        // Metal reads the IOSurface after GL's writes only once they are submitted.
        glFlush();
        slot.commandBuffer = MetalNative.mtlPresentLayerPresent(this.layer, this.queue, slot.surface.metalTextureHandle());
    }
}
