package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.*;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

/** Maximum queue stress of the production command kernels, without repeated raster overdraw. */
public final class MetalMDICCapacitySmokeTest {
    public static void main(String[] args) {
        var backend = new MetalRenderBackend();
        var viewport = new MDICViewport(new RenderProperties(true, false, false), 1, backend);
        var uniform = backend.createBuffer(256);
        var metadata = backend.createBuffer(32);
        var distances = backend.createBuffer((1024L + GpuMDICCapacity.TRANSLUCENT) * 4);
        try (var uploads = new UploadStream(backend, 2 << 20);
             var commands = pipeline(backend, "lod/gl46/cmdgen.comp", Map.of("TRANSLUCENT_WRITE_BASE", "1024",
                     "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7", "TEMPORAL_OFFSET", Integer.toString(GpuMDICCapacity.TEMPORAL_BASE)), 128);
             var prefix = pipeline(backend, "util/prefixsum/simple.comp", Map.of("IO_BUFFER", "0"), 256);
             var translucent = pipeline(backend, "lod/gl46/buildtranslucents.comp", Map.of("TRANSLUCENT_WRITE_BASE", "1024",
                     "TRANSLUCENT_DISTANCE_BUFFER_BINDING", "5", "TRANSLUCENT_OFFSET", Integer.toString(GpuMDICCapacity.OPAQUE)), 128)) {
            long ptr = uploads.upload(uniform, 0, 256); MemoryUtil.memSet(ptr, 0, 256); MemoryUtil.memPutInt(ptr + 76, 7);
            ptr = uploads.upload(metadata, 0, 32); MemoryUtil.memSet(ptr, 0, 32);
            for (int i = 4; i < 8; i++) MemoryUtil.memPutInt(ptr + i * 4L, 0x10001);
            ptr = uploads.upload(viewport.indirectLookupBuffer, 0, viewport.indirectLookupBuffer.size());
            MemoryUtil.memSet(ptr, 0, viewport.indirectLookupBuffer.size()); MemoryUtil.memPutInt(ptr, GpuMDICCapacity.SECTIONS);
            MemoryUtil.memPutInt(uploads.upload(viewport.visibilityBuffer, 0, 4), 7);
            ptr = uploads.upload(viewport.drawCountCallBuffer, 0, 1024); MemoryUtil.memSet(ptr, 0, 1024);
            MemoryUtil.memPutInt(ptr, (GpuMDICCapacity.SECTIONS + 127) / 128); MemoryUtil.memPutInt(ptr + 4, 1); MemoryUtil.memPutInt(ptr + 8, 1);
            MemoryUtil.memSet(uploads.upload(distances, 0, 4096), 0, 4096); uploads.commit();
            try (var encoder = backend.beginComputePass()) {
                encoder.setPipeline(commands); encoder.setBuffer(0, uniform, 0);
                encoder.setBuffer(1, viewport.drawCallBuffer, 0); encoder.setBuffer(2, viewport.drawCountCallBuffer, 0);
                encoder.setBuffer(3, metadata, 0); encoder.setBuffer(4, viewport.visibilityBuffer, 0);
                encoder.setBuffer(5, viewport.indirectLookupBuffer, 0); encoder.setBuffer(6, viewport.positionScratchBuffer, 0);
                encoder.setBuffer(7, distances, 0); encoder.dispatchIndirect(viewport.drawCountCallBuffer, 0);
                encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
                encoder.setPipeline(prefix); encoder.setBuffer(0, distances, 0); encoder.dispatch(1, 1, 1);
                encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
                encoder.setPipeline(translucent); encoder.setBuffer(0, uniform, 0); encoder.setBuffer(1, viewport.drawCallBuffer, 0);
                encoder.setBuffer(2, viewport.drawCountCallBuffer, 0); encoder.setBuffer(3, metadata, 0);
                encoder.setBuffer(4, viewport.indirectLookupBuffer, 0); encoder.setBuffer(5, distances, 0);
                encoder.dispatchIndirect(viewport.drawCountCallBuffer, 0);
            }
            backend.finish();
            long counts = ((MetalBuffer) viewport.drawCountCallBuffer).getContentsPtr();
            if (MemoryUtil.memGetInt(counts + 12) != GpuMDICCapacity.OPAQUE
                    || MemoryUtil.memGetInt(counts + 16) != GpuMDICCapacity.TRANSLUCENT
                    || MemoryUtil.memGetInt(counts + 20) != GpuMDICCapacity.TEMPORAL) throw new AssertionError("Maximum command counts changed");
            long draws = ((MetalBuffer) viewport.drawCallBuffer).getContentsPtr();
            for (long base : new long[]{0, GpuMDICDrawCalls.TEMPORAL_OFFSET}) {
                for (int i : new int[]{0, 1, GpuMDICCapacity.OPAQUE - 2, GpuMDICCapacity.OPAQUE - 1}) check(draws + base + i * 20L, false);
            }
            for (int i : new int[]{0, 1, GpuMDICCapacity.TRANSLUCENT - 2, GpuMDICCapacity.TRANSLUCENT - 1}) {
                check(draws + GpuMDICDrawCalls.TRANSLUCENT_OFFSET + i * 20L, true);
            }
            System.out.println("Metal MDIC capacity OK: 200000 sections emit 1400000 opaque + 1400000 temporal + 200000 translucent commands in 60 MB");
        } finally {
            backend.finish(); distances.free(); metadata.free(); uniform.free(); viewport.delete(); backend.shutdown();
        }
    }
    private static IGpuPipeline pipeline(MetalRenderBackend backend, String path, Map<String, String> defines, int size) {
        return backend.createComputePipeline(new ComputePipelineDesc(ShaderLoader.parse("voxy:" + path), defines, null, null, size, 1, 1, "capacity:" + path));
    }
    private static void check(long ptr, boolean translucent) {
        int vertex = MemoryUtil.memGetInt(ptr + 12), instance = MemoryUtil.memGetInt(ptr + 16);
        if (MemoryUtil.memGetInt(ptr) != 6 || MemoryUtil.memGetInt(ptr + 4) != 1 || MemoryUtil.memGetInt(ptr + 8) != 0
                || (translucent ? vertex != 0 : vertex < 4 || vertex > 28 || vertex % 4 != 0)
                || instance < 0 || instance >= GpuMDICCapacity.SECTIONS) throw new AssertionError("Boundary command corruption");
    }
}
