package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalNative;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.metal.MetalRenderEncoder;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

/** Indirect indexed draws must deliver each command's baseInstance to [[base_instance]] with no CPU push. */
public final class MetalBaseInstanceProbe {
    private static final String VERTEX = """
            #version 460 core
            layout(binding = 0, std430) readonly buffer Rects { vec4 rects[]; };
            layout(location = 0) out flat uvec2 ids;
            void main() {
                vec4 r = rects[gl_BaseInstance];
                vec2 corner = vec2(gl_VertexIndex & 1, (gl_VertexIndex >> 1) & 1);
                gl_Position = vec4(r.xy + corner * r.zw, 0.5, 1.0);
                ids = uvec2(gl_BaseInstance, gl_InstanceIndex);
            }
            """;
    private static final String FRAGMENT = """
            #version 460 core
            layout(location = 0) in flat uvec2 ids;
            layout(location = 0) out vec4 color;
            void main() {
                color = vec4(ids.x & 255u, ids.x >> 8, ids.y & 255u, ids.y >> 8) / 255.0;
            }
            """;
    private static final int CELLS = 8, CELL = 32, W = CELLS * CELL, H = CELL;
    private static final int[] BASES = {3, 17, 250, 1000, 7, 1, 513, 2047};

    public static void main(String[] args) {
        var backend = new MetalRenderBackend();
        IGpuPipeline pipeline = null;
        var rects = (MetalBuffer) backend.createBuffer(2048L * 16, 0, true);
        var indices = (MetalBuffer) backend.createBuffer(6 * 4, 0, true);
        var commands = (MetalBuffer) backend.createBuffer(CELLS * 20L, 0, true);
        try {
            long r = rects.getContentsPtr();
            // Index 0 is off-screen: a baseInstance that reads as 0 draws nothing.
            MemoryUtil.memPutFloat(r, 5); MemoryUtil.memPutFloat(r + 4, 5);
            for (int i = 0; i < CELLS; i++) {
                long p = r + BASES[i] * 16L;
                MemoryUtil.memPutFloat(p, -1 + 2f * i / CELLS); MemoryUtil.memPutFloat(p + 4, -1);
                MemoryUtil.memPutFloat(p + 8, 2f / CELLS); MemoryUtil.memPutFloat(p + 12, 2);
                long c = commands.getContentsPtr() + i * 20L;
                MemoryUtil.memPutInt(c, 6); MemoryUtil.memPutInt(c + 4, 1); MemoryUtil.memPutInt(c + 8, 0);
                MemoryUtil.memPutInt(c + 12, 0); MemoryUtil.memPutInt(c + 16, BASES[i]);
            }
            int[] quad = {0, 1, 2, 2, 1, 3};
            for (int i = 0; i < 6; i++) MemoryUtil.memPutInt(indices.getContentsPtr() + i * 4L, quad[i]);

            var target = backend.createTexture(0x0DE1);
            target.store(0x8058, 1, W, H);
            pipeline = backend.createGraphicsPipeline(new GraphicsPipelineDesc(VERTEX, FRAGMENT, Map.of(), null, null, null, null,
                    0x8058, VertexLayout.EMPTY, PipelineState.DEFAULT, "probe/base-instance"));
            try (var encoder = (MetalRenderEncoder) backend.beginRenderPass(RenderPassDesc.builder(W, H).clearColor(target, 0, 0, 0, 0).build())) {
                encoder.setPipeline(pipeline);
                encoder.setBuffer(0, rects, 0);
                // No per-draw push: source 0 skips setVertexBytes.
                MetalNative.mtlRenderEncoderDrawIndexedPrimitivesIndirectBatch(encoder.handle(), MetalNative.MTLPrimitiveTypeTriangle,
                        MetalNative.MTLIndexTypeUInt32, indices.getHandle(), 0, commands.getHandle(), 0, 0, CELLS, 20, 6);
            }
            backend.finish();
            byte[] px = backend.readPixelsRGBA8(target, 0, 0, W, H);
            boolean ok = true;
            for (int i = 0; i < CELLS; i++) {
                int o = ((H / 2) * W + i * CELL + CELL / 2) * 4;
                int base = (px[o] & 255) | (px[o + 1] & 255) << 8, inst = (px[o + 2] & 255) | (px[o + 3] & 255) << 8;
                System.out.printf("draw %d: expected baseInstance %d -> gl_BaseInstance %d, gl_InstanceIndex %d%n", i, BASES[i], base, inst);
                ok &= base == BASES[i];
            }
            target.free();
            // Terrain relies on this: quads3.vert reads positionBuffer[gl_BaseInstance] without a CPU push.
            if (!ok) throw new AssertionError("[[base_instance]] did not receive the indirect command's baseInstance");
            System.out.println("Metal baseInstance OK: indirect indexed draws deliver each command's baseInstance to [[base_instance]]");
        } finally {
            if (pipeline != null) pipeline.close();
            rects.free(); indices.free(); commands.free();
            backend.shutdown();
        }
    }
}
