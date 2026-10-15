package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.metal.MetalTexture;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.CGL.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.GL_TEXTURE_RECTANGLE;
import static org.lwjgl.opengl.GL31C.GL_TEXTURE_BINDING_RECTANGLE;

/** Full production terrain fixture renders directly to IOSurface, then CGL verifies every RGBA pixel. */
public final class MetalTerrainIOSurfaceSmokeTest {
    public static void main(String[] args) {
        long previous = CGLGetCurrentContext(), context = 0;
        var previousCapabilities = previous == 0 ? null : GL.getCapabilities();
        try (var stack = MemoryStack.stackPush()) {
            var format = stack.mallocPointer(1);
            var count = stack.mallocInt(1);
            check(CGLChoosePixelFormat(stack.ints(kCGLPFAOpenGLProfile, kCGLOGLPVersion_3_2_Core,
                    kCGLPFAAccelerated, kCGLPFAAllowOfflineRenderers, 0), format, count));
            var output = stack.mallocPointer(1);
            try {
                check(CGLCreateContext(format.get(0), 0, output)); context = output.get(0);
            } finally { CGLDestroyPixelFormat(format.get(0)); }
            check(CGLSetCurrentContext(context));
            GL.createCapabilities();
            var backend = new MetalRenderBackend();
            int initialBuffers = MetalBuffer.getCount(), initialTextures = MetalTexture.getCount();
            int sentinel = glGenTextures();
            glBindTexture(GL_TEXTURE_RECTANGLE, sentinel);
            var readback = MemoryUtil.memAlloc(128 * 64 * 4);
            try {
                for (boolean reverse : new boolean[]{false, true}) for (boolean zero : new boolean[]{false, true}) {
                    // Recreate all shared targets and GPU fixtures, like independent world/view lifetimes.
                    try (var bridge = IOSurfaceBridge.create(backend.device(), 128, 64, IOSurfaceBridge.IOSurfaceFormat.BGRA8)) {
                        int texture = glGenTextures(), framebuffer = glGenFramebuffers();
                        try {
                            if (!bridge.bindToGlTexture(texture)) throw new AssertionError("Terrain IOSurface binding");
                            if (glGetInteger(GL_TEXTURE_BINDING_RECTANGLE) != sentinel) throw new AssertionError("Rectangle binding changed");
                            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, texture, 0);
                            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Terrain CGL target incomplete");
                            glReadBuffer(GL_COLOR_ATTACHMENT0);
                            glFinish();
                            MetalTerrainSmokeTest.run(backend, new RenderProperties(zero, reverse, false), bridge.asGpuTexture(), expected -> {
                                readback.clear();
                                glReadPixels(0, 0, 128, 64, GL_RGBA, GL_UNSIGNED_BYTE, readback);
                                for (int i = 0; i < expected.length; i++) {
                                    if ((readback.get(i) & 255) != (expected[i] & 255)) {
                                        throw new AssertionError("Metal/CGL terrain mismatch at pixel=" + (i / 4) + " channel=" + (i % 4));
                                    }
                                }
                                if (glGetError() != GL_NO_ERROR) throw new AssertionError("CGL terrain readback error");
                                // Complete GL consumers before Metal renders the next fixture pass.
                                glFinish();
                            });
                        } finally {
                            glBindFramebuffer(GL_FRAMEBUFFER, 0);
                            glDeleteFramebuffers(framebuffer); glDeleteTextures(texture);
                        }
                    }
                    if (MetalBuffer.getCount() != initialBuffers || MetalTexture.getCount() != initialTextures) throw new AssertionError("Shared terrain resource leak");
                }
                System.out.println("Metal terrain IOSurface OK: all shaded/cutout/translucent/depth pixels match CGL for every depth convention");
            } finally {
                MemoryUtil.memFree(readback); glDeleteTextures(sentinel); backend.shutdown();
            }
        } finally {
            CGLSetCurrentContext(previous); GL.setCapabilities(previousCapabilities);
            if (context != 0) CGLDestroyContext(context);
        }
    }

    private static void check(int result) {
        if (result != kCGLNoError) throw new IllegalStateException("CGL error " + result);
    }
}
