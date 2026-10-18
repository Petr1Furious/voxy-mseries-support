package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.interop.GlMetalFrameBridge;
import me.cortex.voxy.client.core.interop.MetalFrameDepth;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.CGL.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;

/**
 * Frame-to-frame ordering of the GL/Metal hand-off without CPU waits. Every frame carries a unique
 * lightmap colour and depth through GL -> Metal -> GL while a deliberately slow Metal pass keeps the GPU
 * busy. The previous frame's GL output is read only after the next frame's Metal work is in flight, so
 * a stale input, an unfinished Metal frame, or Metal overwriting surfaces GL is still sampling all show
 * up as a wrong colour or depth.
 */
public final class MetalInteropOrderingSmokeTest {
    private static final int SIZE = 1024, FRAMES = 240;
    private static final String VERTEX = """
            #version 460 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String SLOW_COLOR = """
            #version 460 core
            layout(binding=0) uniform sampler2D lightmap;
            layout(location=0) out vec4 color;
            void main() {
                vec4 light = texelFetch(lightmap, ivec2(3, 3), 0);
                float acc = gl_FragCoord.x * 0.001 + gl_FragCoord.y;
                for (int i = 0; i < 1500; i++) acc = fract(acc * 1.37 + 0.11);
                if (acc < -1.0) light = vec4(0.0);
                color = vec4(light.rgb, 1.0);
            }
            """;

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
                for (boolean reverse : new boolean[]{false, true}) run(backend, new RenderProperties(reverse, reverse, false));
                System.out.println("Metal interop ordering OK: " + FRAMES + " frames x2 depth conventions, unique per-frame colour/depth, no stale or overwritten surfaces");
            } finally { backend.shutdown(); }
        } finally {
            CGLSetCurrentContext(previous); GL.setCapabilities(capabilities);
            if (context != 0) CGLDestroyContext(context);
        }
    }

    private static float[] colour(int frame) {
        return new float[]{((frame * 37) % 200 + 20) / 255f, ((frame * 91) % 200 + 30) / 255f, ((frame * 53) % 200 + 10) / 255f};
    }
    private static float depth(int frame) { return 0.25f + (frame % 50) * 0.005f; }

    private static void run(MetalRenderBackend backend, RenderProperties properties) {
        int source = glGenTextures(), lighting = glGenTextures(), inputFbo = glGenFramebuffers(), lightFbo = glGenFramebuffers();
        int[] colors = {glGenTextures(), glGenTextures()}, depths = {glGenTextures(), glGenTextures()};
        int[] outputs = {glGenFramebuffers(), glGenFramebuffers()};
        IGpuPipeline slow = null;
        try (var bridge = new GlMetalFrameBridge(backend, properties); var depthPass = new MetalFrameDepth(backend, properties);
             var sampler = backend.createSampler(SamplerDesc.builder().label("ordering").build())) {
            glBindTexture(GL_TEXTURE_2D, source);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, SIZE, SIZE, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
            glBindTexture(GL_TEXTURE_2D, lighting);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glBindFramebuffer(GL_FRAMEBUFFER, inputFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, source, 0);
            glDrawBuffer(GL_NONE);
            glBindFramebuffer(GL_FRAMEBUFFER, lightFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, lighting, 0);
            for (int i = 0; i < 2; i++) {
                glBindTexture(GL_TEXTURE_2D, colors[i]);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
                glBindTexture(GL_TEXTURE_2D, depths[i]);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, SIZE, SIZE, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
                glBindFramebuffer(GL_FRAMEBUFFER, outputs[i]);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colors[i], 0);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depths[i], 0);
            }
            glBindTexture(GL_TEXTURE_2D, 0);
            slow = backend.createGraphicsPipeline(new GraphicsPipelineDesc(VERTEX, SLOW_COLOR, java.util.Map.of(), null, null, null, null,
                    GpuTextureFormats.BGRA8, VertexLayout.EMPTY, PipelineState.DEFAULT, "ordering/slow-colour"));
            bridge.resize(SIZE, SIZE, 16, 16);
            var identity = new Matrix4f();
            float[] none = {0, 0, 0, 0};
            for (int frame = 0; frame <= FRAMES; frame++) {
                int target = outputs[frame & 1];
                if (frame < FRAMES) {
                    float[] c = colour(frame);
                    glDisable(GL_SCISSOR_TEST); glColorMask(true, true, true, true); glDepthMask(true);
                    glBindFramebuffer(GL_FRAMEBUFFER, target);
                    glClearColor(0.1f, 0.2f, 0.3f, 1); glClearDepth(properties.clearDepth()); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    glBindFramebuffer(GL_FRAMEBUFFER, inputFbo);
                    glClearDepth(depth(frame)); glClear(GL_DEPTH_BUFFER_BIT);
                    glBindFramebuffer(GL_FRAMEBUFFER, lightFbo);
                    glClearColor(c[0], c[1], c[2], 1); glClear(GL_COLOR_BUFFER_BIT);
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    bridge.copyInputs(source, lighting);
                    try (var encoder = backend.beginRenderPass(RenderPassDesc.builder(SIZE, SIZE)
                            .addColorAttachment(bridge.colorTarget(), 0, RenderPassDesc.LoadAction.DONT_CARE,
                                    RenderPassDesc.StoreAction.STORE, 0, 0, 0, 0).build())) {
                        encoder.setPipeline(slow); encoder.setViewport(0, 0, SIZE, SIZE, 0, 1); encoder.setScissor(0, 0, SIZE, SIZE);
                        encoder.setTexture(0, bridge.lightmap()); encoder.setSampler(0, sampler);
                        encoder.draw(RenderEncoder.PRIMITIVE_TRIANGLES, 0, 3, 1, 0);
                    }
                    depthPass.export(bridge.inputDepth(), bridge.depthTarget());
                    // Like the game's mid-frame stream ticks: this frame's Metal work is in flight before GL reads the last one.
                    backend.submit();
                }
                if (frame > 0) verify(outputs[(frame - 1) & 1], frame - 1);
                if (frame < FRAMES) bridge.composite(target, identity, identity, none, none, none);
            }
            backend.finish();
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("Interop ordering GL error");
        } finally {
            if (slow != null) slow.close();
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(inputFbo); glDeleteFramebuffers(lightFbo); glDeleteFramebuffers(outputs[0]); glDeleteFramebuffers(outputs[1]);
            glDeleteTextures(source); glDeleteTextures(lighting);
            glDeleteTextures(colors[0]); glDeleteTextures(colors[1]); glDeleteTextures(depths[0]); glDeleteTextures(depths[1]);
        }
    }

    private static void verify(int framebuffer, int frame) {
        float[] expected = colour(frame);
        try (var stack = MemoryStack.stackPush()) {
            var pixel = stack.malloc(4); var z = stack.mallocFloat(1);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
            for (int[] p : new int[][]{{SIZE / 2, SIZE / 2}, {3, 5}, {SIZE - 2, SIZE - 7}}) {
                glReadPixels(p[0], p[1], 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                glReadPixels(p[0], p[1], 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, z);
                for (int c = 0; c < 3; c++) {
                    if (Math.abs((pixel.get(c) & 255) - Math.round(expected[c] * 255)) > 2)
                        throw new AssertionError("Frame " + frame + " pixel " + p[0] + "," + p[1] + " channel " + c + " expected "
                                + Math.round(expected[c] * 255) + " actual " + (pixel.get(c) & 255) + " (stale or overwritten surface)");
                }
                if (Math.abs(z.get(0) - depth(frame)) > 0.00001f)
                    throw new AssertionError("Frame " + frame + " depth expected " + depth(frame) + " actual " + z.get(0));
            }
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        }
    }

    private static void check(int error) { if (error != kCGLNoError) throw new IllegalStateException("CGL error " + error); }
}
