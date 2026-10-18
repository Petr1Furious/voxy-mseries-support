package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.util.FrameTiming;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.GL_TEXTURE_RECTANGLE;
import static org.lwjgl.opengl.GL31C.GL_TEXTURE_BINDING_RECTANGLE;
import static org.lwjgl.opengl.GL33C.*;

/** GL 3.3 frame handoff. Every mutation is restored so RenderPearl's cached GL state stays valid. */
public final class GlMetalFrameBridge implements AutoCloseable {
    private final MetalRenderBackend backend;
    private final RenderProperties properties;
    private final int copyProgram, compositeProgram, vao, framebuffer, sampler;
    private boolean closed;
    private Surface depthInput, lightInput, colorOutput, depthOutput;

    private static final String VERTEX = """
            #version 330 core
            out vec2 uv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String COPY = """
            #version 330 core
            uniform sampler2D sourceTexture;
            in vec2 uv;
            out vec4 color;
            void main() { color = textureLod(sourceTexture, uv, 0.0); }
            """;
    private static final String COMPOSITE = """
            #version 330 core
            uniform sampler2DRect lodColor;
            uniform sampler2DRect lodDepth;
            uniform mat4 inverseVoxy;
            uniform mat4 vanilla;
            uniform vec4 fogParameters;
            uniform vec4 fogColor;
            uniform vec4 fadeParameters;
            in vec2 uv;
            out vec4 color;
            void main() {
                vec2 pixel = uv * vec2(textureSize(lodDepth));
                float depth = texture(lodDepth, pixel).r;
                if (depth == 0.0 || depth == 1.0) discard;
                vec3 ndc = vec3(uv * 2.0 - 1.0, depth);
                #ifndef USE_ZERO_ONE_DEPTH
                ndc.z = depth * 2.0 - 1.0;
                #endif
                vec4 view = inverseVoxy * vec4(ndc, 1.0);
                vec3 point = view.xyz / view.w;
                vec4 clip = vanilla * vec4(point, 1.0);
                float projected = clip.z / clip.w;
                #ifndef USE_ZERO_ONE_DEPTH
                projected = projected * 0.5 + 0.5;
                #endif
                const float margin = 2.0 / 16777215.0;
                #ifdef USE_REVERSE_Z
                projected = max(margin, projected);
                #else
                projected = min(1.0 - margin, projected);
                #endif
                gl_FragDepth = gl_DepthRange.diff * projected + gl_DepthRange.near;
                color = texture(lodColor, pixel);
                if (color.a == 0.0) discard;
                float fog = clamp(length(point) * fogParameters.x + fogParameters.y, 0.0, fogParameters.z);
                color.rgb = mix(color.rgb, fogColor.rgb, fog * fogColor.a);
                if (fadeParameters.x > 0.0) {
                    float distance = fadeParameters.x > 1.5 ? length(point) : length(point.xz);
                    color.a *= 1.0 - clamp(distance * fadeParameters.z + fadeParameters.y, 0.0, 1.0);
                }
            }
            """;

    public GlMetalFrameBridge(MetalRenderBackend backend, RenderProperties properties) {
        this.backend = backend;
        this.properties = properties;
        int copy = 0, composite = 0, vertexArray = 0, fbo = 0, sampling = 0;
        try (var state = new State()) {
            try {
                copy = program(COPY);
                String defines = (properties.isZero2One() ? "#define USE_ZERO_ONE_DEPTH\n" : "")
                        + (properties.isReverseZ() ? "#define USE_REVERSE_Z\n" : "");
                composite = program(COMPOSITE.replace("#version 330 core\n", "#version 330 core\n" + defines));
                vertexArray = glGenVertexArrays(); fbo = glGenFramebuffers(); sampling = glGenSamplers();
                glSamplerParameteri(sampling, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glSamplerParameteri(sampling, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glSamplerParameteri(sampling, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glSamplerParameteri(sampling, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glSamplerParameteri(sampling, GL_TEXTURE_COMPARE_MODE, GL_NONE);
            } catch (RuntimeException | Error failure) {
                if (copy != 0) glDeleteProgram(copy); if (composite != 0) glDeleteProgram(composite);
                if (vertexArray != 0) glDeleteVertexArrays(vertexArray); if (fbo != 0) glDeleteFramebuffers(fbo);
                if (sampling != 0) glDeleteSamplers(sampling);
                throw failure;
            }
        }
        copyProgram = copy; compositeProgram = composite; vao = vertexArray; framebuffer = fbo; sampler = sampling;
    }

    /** Allocate replacements before releasing the previous complete frame. */
    public boolean resize(int width, int height, int lightWidth, int lightHeight) {
        if (closed) throw new IllegalStateException("Frame bridge is closed");
        if (width <= 0 || height <= 0 || lightWidth <= 0 || lightHeight <= 0) throw new IllegalArgumentException("Invalid frame size");
        if (depthInput != null && depthInput.bridge.width() == width && depthInput.bridge.height() == height
                && lightInput.bridge.width() == lightWidth && lightInput.bridge.height() == lightHeight) return false;
        backend.finish(); glFinish();
        Surface input = null, light = null, color = null, depth = null;
        try (var state = new State()) {
            try {
                input = new Surface(width, height, IOSurfaceBridge.IOSurfaceFormat.R32F);
                light = new Surface(lightWidth, lightHeight, IOSurfaceBridge.IOSurfaceFormat.BGRA8);
                color = new Surface(width, height, IOSurfaceBridge.IOSurfaceFormat.BGRA8);
                depth = new Surface(width, height, IOSurfaceBridge.IOSurfaceFormat.R32F);
            } catch (RuntimeException | Error failure) {
                if (depth != null) depth.close(); if (color != null) color.close();
                if (light != null) light.close(); if (input != null) input.close();
                throw failure;
            }
            releaseSurfaces();
            depthInput = input; lightInput = light; colorOutput = color; depthOutput = depth;
        }
        return true;
    }

    public IGpuTexture inputDepth() { requireFrame(); return depthInput.bridge.asGpuTexture(); }
    public IGpuTexture lightmap() { requireFrame(); return lightInput.bridge.asGpuTexture(); }
    public IGpuTexture colorTarget() { requireFrame(); return colorOutput.bridge.asGpuTexture(); }
    public IGpuTexture depthTarget() { requireFrame(); return depthOutput.bridge.asGpuTexture(); }

    /**
     * Full CPU waits at every GL/Metal hand-off (the previous behaviour). By default the bridge relies on
     * submission ordering of the shared IOSurfaces: glFlush before Metal reads GL output, and Metal work
     * scheduled before GL reads Metal output.
     */
    private static final boolean SYNC_INTEROP = Boolean.getBoolean("voxy.metal.syncInterop");

    /** Called before Metal starts a new frame. Source textures are ordinary Minecraft GL_TEXTURE_2D textures. */
    public void copyInputs(int sourceDepth, int sourceLightmap) {
        requireFrame();
        if (SYNC_INTEROP) backend.finish();
        try (var state = new State()) {
            rasterState(); glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDepthMask(false);
            glUseProgram(copyProgram); glUniform1i(glGetUniformLocation(copyProgram, "sourceTexture"), 0);
            copy(sourceDepth, depthInput); copy(sourceLightmap, lightInput);
            // Complete GL readers of last frame and GL writers of this frame before Metal uses shared storage.
            handOff();
        }
    }

    private void copy(int source, Surface target) {
        if (source == 0) throw new IllegalArgumentException("Missing Minecraft input texture");
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, source); glBindSampler(0, sampler);
        if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != target.bridge.width()
                || glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT) != target.bridge.height()) {
            throw new IllegalArgumentException("Minecraft source size differs from shared input");
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, 0, 0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, target.texture, 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0); verifyFramebuffer();
        glViewport(0, 0, target.bridge.width(), target.bridge.height()); glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    public static int[] textureSize(int texture) {
        int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, texture);
            return new int[]{glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH), glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT)};
        } finally { glBindTexture(GL_TEXTURE_2D, binding); }
    }

    public void compositeToTextures(int color, int depth, Matrix4fc inverseVoxy, Matrix4fc vanilla,
                                    float[] fog, float[] fogColor, float[] fade) {
        try (var state = new State()) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            composite(framebuffer, inverseVoxy, vanilla, fog, fogColor, fade);
        }
    }

    /** Output depth is an R32F export of the LOD depth, in Voxy's physical [0,1] range. */
    public void composite(int outputFramebuffer, Matrix4fc inverseVoxy, Matrix4fc vanilla,
                          float[] fog, float[] fogColor, float[] fade) {
        requireFrame();
        if (fog.length != 4 || fogColor.length != 4 || fade.length != 4) throw new IllegalArgumentException("Expected vec4 composition parameters");
        if (SYNC_INTEROP) backend.finish(); else backend.submitScheduled();
        try (var state = new State(); var stack = MemoryStack.stackPush()) {
            rasterState(); glEnable(GL_DEPTH_TEST); glDepthMask(true); glDepthFunc(properties.closerEqualDepthCompare());
            glEnable(GL_BLEND); glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, outputFramebuffer); verifyFramebuffer();
            glViewport(0, 0, colorOutput.bridge.width(), colorOutput.bridge.height());
            glUseProgram(compositeProgram);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_RECTANGLE, colorOutput.texture); glBindSampler(0, sampler);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_RECTANGLE, depthOutput.texture); glBindSampler(1, sampler);
            glUniform1i(glGetUniformLocation(compositeProgram, "lodColor"), 0);
            glUniform1i(glGetUniformLocation(compositeProgram, "lodDepth"), 1);
            glUniformMatrix4fv(glGetUniformLocation(compositeProgram, "inverseVoxy"), false, inverseVoxy.get(stack.mallocFloat(16)));
            glUniformMatrix4fv(glGetUniformLocation(compositeProgram, "vanilla"), false, vanilla.get(stack.mallocFloat(16)));
            glUniform4fv(glGetUniformLocation(compositeProgram, "fogParameters"), fog);
            glUniform4fv(glGetUniformLocation(compositeProgram, "fogColor"), fogColor);
            glUniform4fv(glGetUniformLocation(compositeProgram, "fadeParameters"), fade);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            // The next Metal frame may overwrite these surfaces only after GL finishes sampling them.
            handOff();
        }
    }

    private static void handOff() {
        long start = FrameTiming.ENABLED ? System.nanoTime() : 0;
        if (SYNC_INTEROP) glFinish(); else glFlush();
        if (FrameTiming.ENABLED) { FrameTiming.glFinishNs += System.nanoTime() - start; FrameTiming.glFinishCount++; }
    }

    private void rasterState() {
        glBindVertexArray(vao); glDisable(GL_CULL_FACE); glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB); glDisable(GL_DITHER); glColorMask(true, true, true, true); glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
    }

    private void requireFrame() { if (depthInput == null) throw new IllegalStateException("Frame bridge requires resize first"); }
    private static void verifyFramebuffer() {
        int status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Frame handoff framebuffer status=" + status);
    }

    private final class Surface implements AutoCloseable {
        final IOSurfaceBridge bridge;
        final int texture;
        Surface(int width, int height, IOSurfaceBridge.IOSurfaceFormat format) {
            bridge = IOSurfaceBridge.create(backend.device(), width, height, format); texture = glGenTextures();
            try {
                if (!bridge.bindToGlTexture(texture)) throw new IllegalStateException("CGL frame surface binding failed");
            } catch (RuntimeException | Error failure) { glDeleteTextures(texture); bridge.close(); throw failure; }
        }
        public void close() { glDeleteTextures(texture); bridge.close(); }
    }

    private void releaseSurfaces() {
        if (depthInput != null) depthInput.close(); if (lightInput != null) lightInput.close();
        if (colorOutput != null) colorOutput.close(); if (depthOutput != null) depthOutput.close();
        depthInput = lightInput = colorOutput = depthOutput = null;
    }

    @Override public void close() {
        if (closed) return;
        backend.finish(); glFinish(); releaseSurfaces();
        glDeleteSamplers(sampler); glDeleteFramebuffers(framebuffer); glDeleteVertexArrays(vao);
        glDeleteProgram(copyProgram); glDeleteProgram(compositeProgram); closed = true;
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type); glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader); glDeleteShader(shader); throw new IllegalStateException("Frame bridge shader: " + log);
        }
        return shader;
    }
    private static int program(String fragment) {
        int vertex = shader(GL_VERTEX_SHADER, VERTEX), frag = 0, program = 0;
        try {
            frag = shader(GL_FRAGMENT_SHADER, fragment); program = glCreateProgram();
            glAttachShader(program, vertex); glAttachShader(program, frag); glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException | Error failure) { if (program != 0) glDeleteProgram(program); throw failure; }
        finally { glDeleteShader(vertex); if (frag != 0) glDeleteShader(frag); }
    }

    /** Only captures state this bridge changes. Texture state is per-unit; FBO and VAO state stay owned by their objects. */
    private static final class State implements AutoCloseable {
        final int program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        final int active = glGetInteger(GL_ACTIVE_TEXTURE), depthFunc = glGetInteger(GL_DEPTH_FUNC);
        final int srcRGB = glGetInteger(GL_BLEND_SRC_RGB), dstRGB = glGetInteger(GL_BLEND_DST_RGB);
        final int srcA = glGetInteger(GL_BLEND_SRC_ALPHA), dstA = glGetInteger(GL_BLEND_DST_ALPHA);
        final int eqRGB = glGetInteger(GL_BLEND_EQUATION_RGB), eqA = glGetInteger(GL_BLEND_EQUATION_ALPHA);
        final boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        final int[] viewport = new int[4], textures = new int[2], rectangles = new int[2], samplers = new int[2];
        final boolean[] masks = new boolean[4];
        final int[] enables = {GL_DEPTH_TEST, GL_BLEND, GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST, GL_FRAMEBUFFER_SRGB, GL_DITHER};
        final boolean[] enabled = new boolean[enables.length];
        final int polygon;
        State() {
            glGetIntegerv(GL_VIEWPORT, viewport);
            try (var stack = MemoryStack.stackPush()) {
                var values = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, values);
                for (int i = 0; i < 4; i++) masks[i] = values.get(i) != 0;
                var modes = stack.mallocInt(2); glGetIntegerv(GL_POLYGON_MODE, modes); polygon = modes.get(0);
            }
            for (int i = 0; i < enables.length; i++) enabled[i] = glIsEnabled(enables[i]);
            for (int i = 0; i < 2; i++) {
                glActiveTexture(GL_TEXTURE0 + i); textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
                rectangles[i] = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE); samplers[i] = glGetInteger(GL_SAMPLER_BINDING);
            }
            glActiveTexture(active);
        }
        public void close() {
            for (int i = 0; i < 2; i++) {
                glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, textures[i]);
                glBindTexture(GL_TEXTURE_RECTANGLE, rectangles[i]); glBindSampler(i, samplers[i]);
            }
            glActiveTexture(active); glUseProgram(program); glBindVertexArray(vao);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw); glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            glColorMask(masks[0], masks[1], masks[2], masks[3]); glDepthMask(depthWrite); glDepthFunc(depthFunc);
            glBlendFuncSeparate(srcRGB, dstRGB, srcA, dstA); glBlendEquationSeparate(eqRGB, eqA);
            glPolygonMode(GL_FRONT_AND_BACK, polygon);
            for (int i = 0; i < enables.length; i++) { if (enabled[i]) glEnable(enables[i]); else glDisable(enables[i]); }
        }
    }
}
