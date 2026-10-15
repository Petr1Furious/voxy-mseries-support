package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.ComputePipelineDesc;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests actual 26.3 staging allocation, copies, fences, callbacks and reuse on Metal. */
public final class MetalStreamsSmokeTest {
    public static void main(String[] args) {
        MetalRenderBackend backend = new MetalRenderBackend();
        var target = (MetalBuffer) backend.createBuffer(512);
        try (var uploads = new UploadStream(backend, 1024);
             var downloads = new DownloadStream(backend, 256)) {
            for (int frame = 0; frame < 40; frame++) {
                int generation = frame;
                long address = uploads.upload(target, 64, 128);
                for (int i = 0; i < 32; i++) MemoryUtil.memPutInt(address + 4L * i, frame * 1000 + i);
                uploads.commit();
                uploads.tick();
                AtomicInteger completed = new AtomicInteger();
                downloads.download(target, 64, 128, (ptr, size) -> {
                    if (size != 128) throw new IllegalStateException("Readback length changed");
                    for (int i = 0; i < 32; i++) {
                        if (MemoryUtil.memGetInt(ptr + 4L * i) != generation * 1000 + i) {
                            throw new IllegalStateException("Staging corruption at frame " + generation + " word " + i);
                        }
                    }
                    completed.incrementAndGet();
                });
                if (completed.get() != 0) throw new IllegalStateException("Callback ran before fence processing");
                downloads.tick();
                downloads.tick();
                if (completed.get() != 1) throw new IllegalStateException("Callback must run exactly once");
            }
            // Exhaust the tiny download arena to exercise reclamation rather than only normal ticks.
            AtomicInteger pressureCallbacks = new AtomicInteger();
            for (int i = 0; i < 30; i++) {
                downloads.download(target, 64, 64, (ptr, size) -> pressureCallbacks.incrementAndGet());
            }
            downloads.flushWaitClear();
            if (pressureCallbacks.get() != 30) throw new IllegalStateException("Pressure path lost callbacks");

            AtomicInteger discarded = new AtomicInteger();
            downloads.download(target, 0, 64, (ptr, size) -> discarded.incrementAndGet());
            downloads.waitDiscard();
            downloads.tick();
            if (discarded.get() != 0) throw new IllegalStateException("Discarded callback executed");

            // Raw staging is consumed directly by the original 26.3 prep kernel.
            try (var pipeline = backend.createComputePipeline(new ComputePipelineDesc(
                    ShaderLoader.parse("voxy:lod/gl46/prep.comp"), Map.of(), null, null, 1, 1, 1, "streams:prep"))) {
                for (int i = 0; i < 40; i++) {
                    int sections = 129 + i;
                    long offset = uploads.rawUploadAddress(4);
                    MemoryUtil.memPutInt(uploads.getBaseAddress() + offset, sections);
                    uploads.commit();
                    try (var encoder = backend.beginComputePass()) {
                        encoder.setPipeline(pipeline);
                        encoder.setBuffer(1, target, 0);
                        encoder.setBuffer(2, uploads.getBuffer(), offset, 4);
                        encoder.dispatch(1, 1, 1);
                    }
                    uploads.tick();
                    if (MemoryUtil.memGetInt(target.getContentsPtr() + 28) != sections) {
                        throw new IllegalStateException("Raw staging reuse corrupted prep input");
                    }
                }
            }
            try {
                uploads.upload(target, Long.MAX_VALUE, 16);
                throw new IllegalStateException("Out-of-range upload accepted");
            } catch (IllegalArgumentException expected) {}
            try {
                downloads.download(target, -1, 16, (ptr, size) -> {});
                throw new IllegalStateException("Negative download range accepted");
            } catch (IllegalArgumentException expected) {}
            System.out.println("Metal streams OK: staged copies, readback fences, pressure, discard, raw compute and reuse");
        } finally {
            target.free();
            backend.shutdown();
        }
    }
}
