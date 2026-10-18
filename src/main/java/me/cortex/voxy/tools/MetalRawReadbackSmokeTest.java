package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.ComputePipelineDesc;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.util.RawDownloadStream;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** GPU writes directly into mapped readback; tiny arena exercises fence-protected reuse. */
public final class MetalRawReadbackSmokeTest {
    public static void main(String[] args) {
        MetalRenderBackend backend = new MetalRenderBackend();
        AtomicInteger callbacks = new AtomicInteger();
        try (var pipeline = backend.createComputePipeline(new ComputePipelineDesc(
                ShaderLoader.parse("voxy:tools/increment.comp"), Map.of(), null, null, 64, 1, 1, "raw-readback"));
             var readback = new RawDownloadStream(backend, 512)) {
            for (int frame = 0; frame < 40; frame++) {
                int offset = readback.download(256, ptr -> {
                    for (int i = 0; i < 64; i++) {
                        if (MemoryUtil.memGetInt(ptr + i * 4L) != i * 2 + 1) {
                            throw new IllegalStateException("Raw GPU write corrupted at word " + i);
                        }
                    }
                    callbacks.incrementAndGet();
                });
                try (var encoder = backend.beginComputePass()) {
                    encoder.setPipeline(pipeline);
                    encoder.setBuffer(0, readback.getBuffer(), offset, 256);
                    encoder.dispatch(1, 1, 1);
                }
            }
            // Submission is asynchronous: wait for the fenced frame before collecting callbacks.
            readback.submit();
            backend.finish();
            readback.tick();
            if (callbacks.get() != 40) throw new IllegalStateException("Lost raw readback callbacks");
            try {
                readback.getBufferId();
                throw new IllegalStateException("Metal readback exposed as a GL buffer");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("GPU buffer bindings")) throw expected;
            }
            System.out.println("Metal raw readback OK: direct compute writes, fences, 40 callbacks and arena reuse");
        } finally {
            backend.shutdown();
        }
    }
}
