package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opengl.CGL.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.GL_TEXTURE_RECTANGLE;
import static org.lwjgl.opengl.GL31C.GL_TEXTURE_BINDING_RECTANGLE;

/** Actual Metal -> IOSurface -> OpenGL readback, without a window or Minecraft. */
public final class IOSurfaceGlSmokeTest {
    private static void check(int result, String operation) {
        if (result != kCGLNoError) throw new IllegalStateException(operation + ": CGL error " + result);
    }

    public static void main(String[] args) {
        long previousContext = CGLGetCurrentContext();
        long context = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pixelFormat = stack.mallocPointer(1);
            var count = stack.mallocInt(1);
            check(CGLChoosePixelFormat(stack.ints(kCGLPFAOpenGLProfile, kCGLOGLPVersion_3_2_Core,
                    kCGLPFAAccelerated, kCGLPFAAllowOfflineRenderers, 0), pixelFormat, count), "choose format");
            if (pixelFormat.get(0) == 0) throw new IllegalStateException("No accelerated CGL pixel format");
            var contextOut = stack.mallocPointer(1);
            try {
                check(CGLCreateContext(pixelFormat.get(0), 0, contextOut), "create context");
                context = contextOut.get(0);
            } finally {
                CGLDestroyPixelFormat(pixelFormat.get(0));
            }
            check(CGLSetCurrentContext(context), "make current");
            GL.createCapabilities();
            System.out.println("OpenGL: " + glGetString(GL_VERSION));
            MetalRenderBackend backend = new MetalRenderBackend();
            int sentinel = glGenTextures();
            glBindTexture(GL_TEXTURE_RECTANGLE, sentinel);
            try {
                // Recreate surfaces at different sizes to cover framebuffer resizing.
                for (int size : new int[]{32, 65, 128}) {
                    try (var bridge = IOSurfaceBridge.create(backend.device(), size, size,
                            IOSurfaceBridge.IOSurfaceFormat.BGRA8)) {
                        int texture = glGenTextures();
                        int framebuffer = glGenFramebuffers();
                        try {
                            if (!bridge.bindToGlTexture(texture)) throw new IllegalStateException("CGL IOSurface bind failed");
                            if (glGetInteger(GL_TEXTURE_BINDING_RECTANGLE) != sentinel) {
                                throw new IllegalStateException("JNI changed caller texture binding");
                            }
                            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, texture, 0);
                            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                                throw new IllegalStateException("IOSurface GL framebuffer incomplete");
                            }
                            // GL must finish reading before Metal writes the same surface again.
                            for (float red : new float[]{0.2f, 0.8f}) {
                                glFinish();
                                try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(size, size)
                                        .clearColor(bridge.asGpuTexture(), red, 0.6f, 0.9f, 1).build())) {}
                                // submit waits for Metal completion before GL consumes the shared image.
                                backend.finish();
                                glReadBuffer(GL_COLOR_ATTACHMENT0);
                                ByteBuffer pixel = stack.malloc(4);
                                glReadPixels(size / 2, size / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                                int[] expected = {Math.round(red * 255), 153, 230, 255};
                                for (int channel = 0; channel < 4; channel++) {
                                    int actual = pixel.get(channel) & 255;
                                    if (Math.abs(actual - expected[channel]) > 2) {
                                        throw new IllegalStateException("size=" + size + " channel=" + channel
                                                + " expected=" + expected[channel] + " actual=" + actual);
                                    }
                                }
                                if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("GL readback error");
                            }
                            glFinish();
                        } finally {
                            glBindFramebuffer(GL_FRAMEBUFFER, 0);
                            glDeleteFramebuffers(framebuffer);
                            glDeleteTextures(texture);
                        }
                    }
                }
                // The frame pipeline also needs float depth handoff in both directions.
                for (int[] dimensions : new int[][]{{32, 16}, {65, 37}, {128, 64}}) {
                    int width = dimensions[0], height = dimensions[1];
                    try (var bridge = IOSurfaceBridge.create(backend.device(), width, height, IOSurfaceBridge.IOSurfaceFormat.R32F)) {
                        int texture = glGenTextures(), framebuffer = glGenFramebuffers();
                        try {
                            if (!bridge.bindToGlTexture(texture)) throw new IllegalStateException("R32F CGL IOSurface bind failed");
                            if (glGetInteger(GL_TEXTURE_BINDING_RECTANGLE) != sentinel) throw new IllegalStateException("R32F binding restoration");
                            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, texture, 0);
                            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("R32F GL framebuffer incomplete");
                            for (float depth : new float[]{0.37f, 0.81f}) {
                                glFinish();
                                try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(width, height)
                                        .clearColor(bridge.asGpuTexture(), depth, 0, 0, 1).build())) {}
                                backend.finish();
                                var pixels = stack.mallocFloat(1);
                                glReadPixels(width / 2, height / 2, 1, 1, GL_RED, GL_FLOAT, pixels);
                                if (Math.abs(pixels.get(0) - depth) > 0.000001f) throw new IllegalStateException("Metal -> GL depth mismatch");
                                // GL writes the same shared storage; Metal must observe the float, not zeros or reinterpreted RGBA.
                                float glDepth = 1 - depth;
                                glClearColor(glDepth, 0, 0, 1);
                                glClear(GL_COLOR_BUFFER_BIT);
                                glFinish();
                                var actual = ByteBuffer.wrap(backend.readPixelsRGBA8(bridge.asGpuTexture(), 0, 0, width, height))
                                        .order(ByteOrder.LITTLE_ENDIAN);
                                while (actual.hasRemaining()) {
                                    if (Math.abs(actual.getFloat() - glDepth) > 0.000001f) throw new IllegalStateException("GL -> Metal depth mismatch");
                                }
                                if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("R32F GL transfer error");
                            }
                            glFinish();
                        } finally {
                            glBindFramebuffer(GL_FRAMEBUFFER, 0);
                            glDeleteFramebuffers(framebuffer); glDeleteTextures(texture);
                        }
                    }
                }
                System.out.println("IOSurface GL OK: shared colors and bidirectional float depth across updates and resizes");
            } finally {
                glDeleteTextures(sentinel);
                backend.shutdown();
            }
        } finally {
            GL.setCapabilities(null);
            CGLSetCurrentContext(previousContext);
            if (context != 0) CGLDestroyContext(context);
        }
    }
}
