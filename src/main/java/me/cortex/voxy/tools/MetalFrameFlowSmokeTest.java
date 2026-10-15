package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.*;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.opengl.CGL.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;

/** Complete GL input -> Metal frame -> SSAO/translucency -> exported depth -> GL composition. */
public final class MetalFrameFlowSmokeTest {
    public static void main(String[] args) {
        long previous = CGLGetCurrentContext(), context = 0;
        var capabilities = previous == 0 ? null : GL.getCapabilities();
        try (var stack = MemoryStack.stackPush()) {
            var format = stack.mallocPointer(1); var count = stack.mallocInt(1); var output = stack.mallocPointer(1);
            check(CGLChoosePixelFormat(stack.ints(kCGLPFAOpenGLProfile, kCGLOGLPVersion_3_2_Core,
                    kCGLPFAAccelerated, kCGLPFAAllowOfflineRenderers, 0), format, count));
            try { check(CGLCreateContext(format.get(0), 0, output)); context = output.get(0); }
            finally { CGLDestroyPixelFormat(format.get(0)); }
            check(CGLSetCurrentContext(context)); GL.createCapabilities();
            var backend = new MetalRenderBackend();
            try {
                for (boolean reverse : new boolean[]{false, true}) for (boolean zero : new boolean[]{false, true}) run(backend, new RenderProperties(zero, reverse, false));
                System.out.println("Metal frame flow OK: first-frame temporal draw, retained opaque draws, vanilla masking, SSAO, translucent depth, resize and final GL pixels");
            } finally { backend.shutdown(); }
        } finally { CGLSetCurrentContext(previous); GL.setCapabilities(capabilities); if (context != 0) CGLDestroyContext(context); }
    }

    private static void run(MetalRenderBackend backend, RenderProperties properties) {
        int initialBuffers = backend.getBufferCount(), initialTextures = backend.getTextureCount();
        var viewport = new MDICViewport(properties, 2, backend);
        var geometry = backend.createBuffer(16); var metadata = backend.createBuffer(64);
        var models = backend.createBuffer(128); var colors = backend.createBuffer(4);
        var atlas = backend.createTexture().storeUploadable(0x8058, 1, 768, 512);
        var sampler = backend.createSampler(SamplerDesc.builder().build());
        int glDepth = glGenTextures(), glColor = glGenTextures(), glLight = glGenTextures(), framebuffer = glGenFramebuffers();
        var pixels = MemoryUtil.memAlloc(768 * 512 * 4);
        try (var uploads = new UploadStream(backend, 4 << 20);
             var frames = new MetalFrameRenderer(backend, uploads, properties, Map.of("NO_SHADE_FACE_TINT", "1.0",
                     "UP_FACE_TINT", "1.0", "DOWN_FACE_TINT", "0.5", "X_AXIS_FACE_TINT", "0.6", "Z_AXIS_FACE_TINT", "0.8"), SSAO.SSAOMode.BASIC)) {
            long ptr = uploads.upload(models, 0, 128); MemoryUtil.memSet(ptr, 0, 128);
            for (int i = 0; i < 2; i++) {
                for (int face = 0; face < 6; face++) MemoryUtil.memPutInt(ptr + i * 64L + face * 4L, 0xf0f0);
                MemoryUtil.memPutInt(ptr + i * 64L + 28, -1);
            }
            MemoryUtil.memPutInt(ptr + 64 + 24, 4); // Second model is translucent.
            ptr = uploads.upload(geometry, 0, 16);
            for (int i = 0; i < 2; i++) MemoryUtil.memPutLong(ptr + i * 8L, 2L | (3L << 3) | (3L << 7)
                    | (3L << 11) | (2L << 16) | (2L << 21) | ((long) i << 26) | (255L << 55));
            ptr = uploads.upload(metadata, 0, 64); MemoryUtil.memSet(ptr, 0, 64);
            for (int i = 0; i < 2; i++) {
                MemoryUtil.memPutInt(ptr + i * 32L + 4, i << 4);
                MemoryUtil.memPutInt(ptr + i * 32L + 8, 2 | (2 << 5) | (3 << 10) | (3 << 15) | (3 << 20) | (2 << 25));
                MemoryUtil.memPutInt(ptr + i * 32L + 12, i); MemoryUtil.memPutInt(ptr + i * 32L + 16, i == 0 ? 1 << 16 : 1);
            }
            MemoryUtil.memSet(uploads.upload(viewport.visibilityBuffer, 0, 8), 0, 8); uploads.commit();
            MemoryUtil.memSet(MemoryUtil.memAddress(pixels), 0, pixels.capacity());
            pixels.put(4, (byte) 255).put(7, (byte) 255); pixels.put(18, (byte) 255).put(19, (byte) 128);
            atlas.uploadSubImage2D(0, 0, 0, 768, 512, 0x1908, 0x1401, MemoryUtil.memAddress(pixels));
            MemoryUtil.memSet(MemoryUtil.memAddress(pixels), 255, 16 * 16 * 4);
            glBindTexture(GL_TEXTURE_2D, glLight); glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                    MemoryUtil.memByteBuffer(MemoryUtil.memAddress(pixels), 16 * 16 * 4));
            Consumer<RenderEncoder> bindings = encoder -> {
                encoder.setBuffer(3, models, 0); encoder.setBuffer(4, colors, 0); encoder.setTexture(0, atlas); encoder.setSampler(0, sampler);
            };
            int[] calls = {0};
            for (int[] size : new int[][]{{128, 64}, {64, 32}, {65, 37}}) {
                int w = size[0], h = size[1];
                var projection = new Matrix4f().m00(0.04f).m11(0.2f).m22(0.05f).m30(-0.9f).m31(-0.9f).m32(properties.isZero2One() ? 0.3f : -0.4f);
                viewport.setScreenSize(w, h).setProjection(projection, 0.1f, 200).setVanillaProjection(projection).update();
                glBindTexture(GL_TEXTURE_2D, glColor); glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
                glBindTexture(GL_TEXTURE_2D, glDepth); glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, w, h, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, glColor, 0);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, glDepth, 0);
                glDrawBuffer(GL_COLOR_ATTACHMENT0); glReadBuffer(GL_COLOR_ATTACHMENT0);
                for (int frame = 0; frame < 3; frame++) {
                    glDisable(GL_SCISSOR_TEST); glDepthMask(true); glColorMask(true, true, true, true);
                    glClearDepth(properties.clearDepth()); glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    if (frame == 1) { glEnable(GL_SCISSOR_TEST); glScissor(0, 0, w / 2, h); glClearDepth(0.5); glClear(GL_DEPTH_BUFFER_BIT); glDisable(GL_SCISSOR_TEST); }
                    viewport.frameId++;
                    frames.render(viewport, glDepth, glColor, glLight, geometry, metadata, bindings, () -> {
                        if (viewport.hiZBuffer.getTexture() == null) throw new AssertionError("Primary work ran before HiZ");
                        long list = uploads.upload(viewport.indirectLookupBuffer, 0, 12);
                        MemoryUtil.memPutInt(list, 2); MemoryUtil.memPutInt(list + 4, 0); MemoryUtil.memPutInt(list + 8, 1);
                        uploads.commit(); calls[0]++;
                    }, new float[4], new float[4], new float[4]);
                    try (var stack = MemoryStack.stackPush()) {
                        var pixel = stack.malloc(4); var z = stack.mallocFloat(1);
                        glReadPixels((int) (w * 0.13f), (int) (h * 0.45f), 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                        if (Math.abs((pixel.get(0) & 255) - (frame == 1 ? 0 : 255)) > 2) throw new AssertionError("Opaque/temporal/vanilla frame=" + frame + " size=" + w + "x" + h + " actual=" + (pixel.get(0) & 255) + "," + (pixel.get(1) & 255) + "," + (pixel.get(2) & 255) + " props=" + properties);
                        glReadPixels((int) (w * 0.77f), (int) (h * 0.45f), 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                        // Match 26.3's two-stage alpha composition for a translucent surface over transparent LOD storage.
                        if (Math.abs((pixel.get(2) & 255) - 64) > 2) throw new AssertionError("Composited translucent terrain " + (pixel.get(2) & 255));
                        glReadPixels((int) (w * 0.77f), (int) (h * 0.45f), 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, z);
                        float expected = properties.isZero2One() ? 0.45f : 0.375f;
                        if (Math.abs(z.get(0) - expected) > 0.000001f) throw new AssertionError("Final translucent depth");
                    }
                    uploads.tick();
                }
            }
            if (calls[0] != 9 || glGetError() != GL_NO_ERROR) throw new AssertionError("Frame callback/state error");
        } finally {
            backend.finish(); viewport.delete(); sampler.close(); atlas.free(); colors.free(); models.free(); metadata.free(); geometry.free();
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glDeleteFramebuffers(framebuffer);
            glDeleteTextures(glLight); glDeleteTextures(glColor); glDeleteTextures(glDepth); MemoryUtil.memFree(pixels);
        }
        if (backend.getBufferCount() != initialBuffers || backend.getTextureCount() != initialTextures) throw new AssertionError("Frame flow resource leak");
    }
    private static void check(int error) { if (error != kCGLNoError) throw new IllegalStateException("CGL error " + error); }
}
