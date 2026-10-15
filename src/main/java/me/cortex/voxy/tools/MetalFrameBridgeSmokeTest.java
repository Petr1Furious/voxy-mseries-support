package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.interop.GlMetalFrameBridge;
import me.cortex.voxy.client.core.interop.MetalFrameDepth;
import me.cortex.voxy.client.core.interop.GlTextureReadback;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;
import java.util.Arrays;

import static org.lwjgl.opengl.CGL.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL12C.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12C.GL_PACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING;

/** Real depth/lightmap handoff, transformed color/depth composition and state preservation on GL 4.1 + Metal. */
public final class MetalFrameBridgeSmokeTest {
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
                for (boolean reverse : new boolean[]{false, true}) for (boolean zero : new boolean[]{false, true}) {
                    var properties = new RenderProperties(zero, reverse, false);
                    try (var bridge = new GlMetalFrameBridge(backend, properties)) {
                        for (int[] size : new int[][]{{64, 32}, {65, 37}, {128, 16}}) run(backend, bridge, properties, size[0], size[1]);
                    }
                }
                System.out.println("Metal frame bridge OK: depth/lightmap GPU copies, alpha/fog/fade, projection depth, GL occlusion, resize and GL state restoration");
            } finally { backend.shutdown(); }
        } finally {
            CGLSetCurrentContext(previous); GL.setCapabilities(capabilities);
            if (context != 0) CGLDestroyContext(context);
        }
    }

    private static void run(MetalRenderBackend backend, GlMetalFrameBridge bridge, RenderProperties properties, int width, int height) {
        int source = glGenTextures(), lighting = glGenTextures(), color = glGenTextures(), depth = glGenTextures();
        int framebuffer = glGenFramebuffers(), sentinelVao = glGenVertexArrays(), sentinelSampler = glGenSamplers();
        var input = MemoryUtil.memAllocFloat(width * height);
        var light = MemoryUtil.memAlloc(16 * 16 * 4);
        try {
            for (int i = 0; i < input.capacity(); i++) input.put(i, i % 17 == 0 ? properties.clearDepth() : ((i * 13) % 211 + 16) / 255f);
            for (int i = 0; i < light.capacity(); i++) light.put(i, (byte) (i * 29 + 7));
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, source); glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, input);
            glBindTexture(GL_TEXTURE_2D, lighting); glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, light);
            glBindTexture(GL_TEXTURE_2D, color); glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glBindTexture(GL_TEXTURE_2D, depth); glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
            verifyAtlasReadback(lighting, light);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0); glReadBuffer(GL_COLOR_ATTACHMENT0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Test composition target");
            me.cortex.voxy.client.core.gpu.IGpuTexture previousSurface = null;
            try { previousSurface = bridge.colorTarget(); } catch (IllegalStateException initialFrame) {}
            bridge.resize(width, height, 16, 16);
            if (previousSurface != null) {
                if (!previousSurface.isFreed()) throw new AssertionError("Previous frame surface still allocated");
                try { previousSurface.id(); throw new AssertionError("Stale IOSurface handle accepted"); }
                catch (IllegalStateException expected) {}
            }
            if (bridge.resize(width, height, 16, 16)) throw new AssertionError("Unnecessary bridge reallocation");
            hostileState(sentinelVao, sentinelSampler, source, lighting);
            int[] before = state();
            bridge.copyInputs(source, lighting);
            requireState(before);
            var transferred = java.nio.ByteBuffer.wrap(backend.readPixelsRGBA8(bridge.inputDepth(), 0, 0, width, height)).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < input.capacity(); i++) {
                if (Math.abs(transferred.getFloat(i * 4) - input.get(i)) > 0.000001f) throw new AssertionError("Minecraft depth handoff at " + i);
            }
            byte[] lightImage = backend.readPixelsRGBA8(bridge.lightmap(), 0, 0, 16, 16);
            for (int i = 0; i < lightImage.length; i++) if (lightImage[i] != light.get(i)) throw new AssertionError("Minecraft lightmap handoff at " + i);
            var gpuDepth = backend.createTexture().store(0x8cac, 1, width, height);
            try (var depthPass = new MetalFrameDepth(backend, properties)) {
                depthPass.prepare(bridge.inputDepth(), gpuDepth, bridge.colorTarget());
                depthPass.export(gpuDepth, bridge.depthTarget());
                var masked = java.nio.ByteBuffer.wrap(backend.readPixelsRGBA8(gpuDepth, 0, 0, width, height)).order(ByteOrder.LITTLE_ENDIAN);
                var exported = java.nio.ByteBuffer.wrap(backend.readPixelsRGBA8(bridge.depthTarget(), 0, 0, width, height)).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < input.capacity(); i++) {
                    float expected = input.get(i) == properties.clearDepth() ? properties.clearDepth() : properties.inverseClearDepth();
                    if (masked.getFloat(i * 4) != expected || exported.getFloat(i * 4) != expected) throw new AssertionError("Vanilla mask / native depth export at " + i);
                }
                for (float value : new float[]{0.21f, 0.37f, 0.81f}) {
                    try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(width, height).clearDepth(gpuDepth, value).build())) {}
                    depthPass.export(gpuDepth, bridge.depthTarget());
                    var floats = java.nio.ByteBuffer.wrap(backend.readPixelsRGBA8(bridge.depthTarget(), 0, 0, width, height)).order(ByteOrder.LITTLE_ENDIAN);
                    while (floats.hasRemaining()) if (Math.abs(floats.getFloat() - value) > 0.000001f) throw new AssertionError("Nontrivial native depth export");
                }
            } finally { gpuDepth.free(); }
            float[] noFog = {0, 0, 0, 0}, noFade = {0, 0, 0, 0};
            var identity = new Matrix4f();
            for (int mode = 0; mode < 6; mode++) {
                clearOutput(properties, framebuffer, mode == 1 ? properties.isReverseZ() ? 0.6f : 0.2f : properties.clearDepth());
                float lodDepth = mode == 5 ? properties.clearDepth() : 0.37f;
                try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(width, height)
                        .clearColor(bridge.colorTarget(), 0.2f, 0.6f, 0.9f, 0.5f).build())) {}
                try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(width, height)
                        .clearColor(bridge.depthTarget(), lodDepth, 0, 0, 1).build())) {}
                hostileState(sentinelVao, sentinelSampler, source, lighting); before = state();
                Matrix4f vanilla = mode == 2 ? new Matrix4f().m22(0.5f).m32(properties.isZero2One() ? 0.1f : 0) : identity;
                bridge.composite(framebuffer, identity, vanilla, mode == 3 ? new float[]{0, 1, 1, 0} : noFog,
                        mode == 3 ? new float[]{1, 0, 0, 1} : noFog, mode == 4 ? new float[]{1, 1, 0, 0} : noFade);
                requireState(before);
                try (var stack = MemoryStack.stackPush()) {
                    var pixel = stack.malloc(4); var z = stack.mallocFloat(1);
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
                    glReadPixels(width / 2, height / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                    glReadPixels(width / 2, height / 2, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, z);
                    float[] expected = mode == 1 || mode == 4 || mode == 5 ? new float[]{0.1f, 0.2f, 0.3f}
                            : mode == 3 ? new float[]{0.55f, 0.1f, 0.15f} : new float[]{0.15f, 0.4f, 0.6f};
                    for (int c = 0; c < 3; c++) {
                        if (Math.abs((pixel.get(c) & 255) - Math.round(expected[c] * 255)) > 2) throw new AssertionError("Composition mode=" + mode + " channel=" + c);
                    }
                    float expectedDepth = mode == 1 ? properties.isReverseZ() ? 0.6f : 0.2f : mode == 5 ? properties.clearDepth()
                            : mode == 2 ? properties.isZero2One() ? 0.285f : 0.435f : 0.37f;
                    if (Math.abs(z.get(0) - expectedDepth) > 0.000001f) throw new AssertionError("Composition depth mode=" + mode + " expected=" + expectedDepth + " actual=" + z.get(0));
                }
            }
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("Frame bridge GL error");
        } finally {
            glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_BLEND); glDisable(GL_FRAMEBUFFER_SRGB);
            glColorMask(true, true, true, true); glDepthMask(true); glActiveTexture(GL_TEXTURE0);
            glBindVertexArray(0); glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteSamplers(sentinelSampler); glDeleteVertexArrays(sentinelVao); glDeleteFramebuffers(framebuffer);
            glDeleteTextures(source); glDeleteTextures(lighting); glDeleteTextures(color); glDeleteTextures(depth);
            MemoryUtil.memFree(light); MemoryUtil.memFree(input);
        }
    }

    private static void clearOutput(RenderProperties properties, int framebuffer, float depth) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer); glDisable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true); glDepthMask(true);
        glClearColor(0.1f, 0.2f, 0.3f, 1); glClearDepth(depth); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }
    private static void verifyAtlasReadback(int texture, java.nio.ByteBuffer expected) {
        int pbo = glGenBuffers();
        int[] tokens = {GL_PACK_ROW_LENGTH, GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS,
                GL_PACK_SKIP_IMAGES, GL_PACK_ALIGNMENT, GL_PACK_SWAP_BYTES, GL_PACK_LSB_FIRST};
        int[] previous = new int[tokens.length], hostile = {37, 41, 3, 5, 2, 8, 1, 1};
        int oldPbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING), oldBinding = glGetInteger(GL_TEXTURE_BINDING_2D);
        for (int i = 0; i < tokens.length; i++) previous[i] = glGetInteger(tokens[i]);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo); glBufferData(GL_PIXEL_PACK_BUFFER, 4096, GL_STATIC_READ);
            for (int i = 0; i < tokens.length; i++) glPixelStorei(tokens[i], hostile[i]);
            int[] pixels = new int[256];
            GlTextureReadback.rgba8(texture, 0, 16, 16, pixels);
            for (int i = 0; i < pixels.length; i++) {
                int value = (expected.get(i * 4) & 255) | ((expected.get(i * 4 + 1) & 255) << 8)
                        | ((expected.get(i * 4 + 2) & 255) << 16) | ((expected.get(i * 4 + 3) & 255) << 24);
                if (pixels[i] != value) throw new AssertionError("Software-bakery atlas readback pixel " + i);
            }
            if (glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING) != pbo || glGetInteger(GL_TEXTURE_BINDING_2D) != oldBinding) throw new AssertionError("Atlas readback bindings changed");
            for (int i = 0; i < tokens.length; i++) if (glGetInteger(tokens[i]) != hostile[i]) throw new AssertionError("Atlas pixel-pack state changed");
        } finally {
            for (int i = 0; i < tokens.length; i++) glPixelStorei(tokens[i], previous[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, oldPbo); glDeleteBuffers(pbo);
        }
    }
    private static void hostileState(int vao, int sampler, int source, int light) {
        glBindVertexArray(vao); glViewport(3, 7, 19, 23);
        glEnable(GL_SCISSOR_TEST); glScissor(0, 0, 1, 1); glEnable(GL_STENCIL_TEST); glEnable(GL_CULL_FACE);
        glEnable(GL_FRAMEBUFFER_SRGB); glEnable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDepthMask(false); glDepthFunc(GL_NOTEQUAL);
        glBlendFuncSeparate(GL_ONE, GL_ZERO, GL_ZERO, GL_ONE); glBlendEquationSeparate(GL_FUNC_REVERSE_SUBTRACT, GL_FUNC_ADD);
        glColorMask(false, true, false, true);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, source); glBindSampler(0, sampler);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, light); glBindSampler(1, sampler);
        glActiveTexture(GL_TEXTURE3);
    }
    private static int[] state() {
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] values = new int[36];
        glGetIntegerv(GL_VIEWPORT, values); // LWJGL writes only the first four entries.
        int i = 4;
        for (int token : new int[]{GL_CURRENT_PROGRAM, GL_VERTEX_ARRAY_BINDING, GL_DRAW_FRAMEBUFFER_BINDING,
                GL_READ_FRAMEBUFFER_BINDING, GL_ACTIVE_TEXTURE, GL_DEPTH_FUNC, GL_BLEND_SRC_RGB, GL_BLEND_DST_RGB,
                GL_BLEND_SRC_ALPHA, GL_BLEND_DST_ALPHA, GL_BLEND_EQUATION_RGB, GL_BLEND_EQUATION_ALPHA}) values[i++] = glGetInteger(token);
        values[i++] = glGetBoolean(GL_DEPTH_WRITEMASK) ? 1 : 0;
        for (int token : new int[]{GL_BLEND, GL_DEPTH_TEST, GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST, GL_FRAMEBUFFER_SRGB}) values[i++] = glIsEnabled(token) ? 1 : 0;
        for (int unit = 0; unit < 2; unit++) { glActiveTexture(GL_TEXTURE0 + unit); values[i++] = glGetInteger(GL_TEXTURE_BINDING_2D); values[i++] = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE); values[i++] = glGetInteger(GL_SAMPLER_BINDING); }
        try (var stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            for (int channel = 0; channel < 4; channel++) values[i++] = mask.get(channel);
        }
        values[i++] = glIsEnabled(GL_DITHER) ? 1 : 0;
        glActiveTexture(active); return values;
    }
    private static void requireState(int[] expected) { if (!Arrays.equals(expected, state())) throw new AssertionError("Frame bridge changed caller GL state"); }
    private static void check(int error) { if (error != kCGLNoError) throw new IllegalStateException("CGL error " + error); }
}
