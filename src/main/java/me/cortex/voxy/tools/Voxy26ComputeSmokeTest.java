package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.ComputePipelineDesc;
import me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc;
import me.cortex.voxy.client.core.gpu.PipelineState;
import me.cortex.voxy.client.core.gpu.VertexLayout;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

/** Runs the unmodified 26.3 MDIC preparation kernel and compiles its command generators. */
public final class Voxy26ComputeSmokeTest {
    public static void main(String[] args) {
        MetalRenderBackend backend = new MetalRenderBackend();
        try {
            String source = ShaderLoader.parse("voxy:lod/gl46/prep.comp");
            try (var pipeline = backend.createComputePipeline(new ComputePipelineDesc(source, Map.of(),
                    null, null, 1, 1, 1, "voxy26:prep"))) {
                var output = (MetalBuffer) backend.createBuffer(128);
                var lookup = (MetalBuffer) backend.createBuffer(128);
                try {
                    for (int count : new int[]{0, 1, 127, 128, 129, 4096}) {
                        output.fill(-1);
                        MemoryUtil.memPutInt(lookup.getContentsPtr(), count);
                        try (var encoder = backend.beginComputePass()) {
                            encoder.setPipeline(pipeline);
                            encoder.setBuffer(1, output, 0);
                            encoder.setBuffer(2, lookup, 0);
                            encoder.dispatch(1, 1, 1);
                        }
                        backend.submit();
                        int[] expected = {(count + 127) / 128, 1, 1, 0, 0, 0, 36, count, 786432, 0, 0};
                        for (int i = 0; i < expected.length; i++) {
                            int actual = MemoryUtil.memGetInt(output.getContentsPtr() + i * 4L);
                            if (actual != expected[i]) throw new IllegalStateException("sectionCount=" + count
                                    + " word=" + i + " expected=" + expected[i] + " actual=" + actual);
                        }
                    }
                } finally {
                    output.free();
                    lookup.free();
                }
            }
            compile(backend, "voxy:lod/gl46/cmdgen.comp", Map.of("TRANSLUCENT_WRITE_BASE", "1024",
                    "TEMPORAL_OFFSET", "100000", "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7"), 128);
            compile(backend, "voxy:lod/gl46/buildtranslucents.comp", Map.of("TRANSLUCENT_WRITE_BASE", "1024",
                    "TRANSLUCENT_OFFSET", "50000", "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "5"), 128);
            compile(backend, "voxy:util/prefixsum/simple.comp", Map.of("IO_BUFFER", "0"), 256);
            var terrainDefines = Map.of("UP_FACE_TINT", "1.0", "DOWN_FACE_TINT", "0.5",
                    "Z_AXIS_FACE_TINT", "0.8", "X_AXIS_FACE_TINT", "0.6", "NO_SHADE_FACE_TINT", "1.0");
            try (var terrain = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                    ShaderLoader.parse("voxy:lod/gl46/quads3.vert"), ShaderLoader.parse("voxy:lod/gl46/quads.frag"),
                    terrainDefines, null, null, null, null, 0x8058, VertexLayout.EMPTY,
                    PipelineState.DEFAULT, "voxy26:terrain"))) {
                System.out.println("Metal terrain shader compiled from Voxy 26.3 sources");
            }
            System.out.println("Voxy 26.3 Metal OK: prep output verified at dispatch boundaries; command kernels compiled");
        } finally {
            backend.shutdown();
        }
    }

    private static void compile(MetalRenderBackend backend, String shader, Map<String, String> defines, int localSize) {
        try (var pipeline = backend.createComputePipeline(new ComputePipelineDesc(ShaderLoader.parse(shader), defines,
                null, null, localSize, 1, 1, shader))) {
            System.out.println("Metal pipeline compiled: " + shader);
        }
    }
}
