package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.hierachical.NodeGpuUploader;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

/** Exercises the exact GPU uploader now used by AsyncNodeManager. */
public final class MetalNodeUploadsSmokeTest {
    public static void main(String[] args) {
        MetalRenderBackend backend = new MetalRenderBackend();
        var geometry = backend.createBuffer(4096);
        var nodes = backend.createBuffer(4096);
        var metadata = backend.createBuffer(4096);
        try (var uploads = new UploadStream(backend, 4096);
             var downloads = new DownloadStream(backend, 8192);
             var uploader = new NodeGpuUploader(backend)) {
            // Copy jobs use 8-byte quad indices; headers and data are independent buffer ranges.
            int headersSize = uploads.alignAllocation(32);
            int dataSize = uploads.alignAllocation(96);
            long offset = uploads.rawUploadAddress(headersSize + dataSize);
            long ptr = uploads.getBaseAddress() + offset;
            MemoryUtil.memSet(ptr, 0, headersSize + dataSize);
            MemoryUtil.memPutInt(ptr, 0);
            MemoryUtil.memPutInt(ptr + 4, 16);
            MemoryUtil.memPutInt(ptr + 8, 8);
            MemoryUtil.memPutInt(ptr + 16, 8);
            MemoryUtil.memPutInt(ptr + 20, 40);
            MemoryUtil.memPutInt(ptr + 24, 4);
            for (int i = 0; i < 12; i++) MemoryUtil.memPutLong(ptr + headersSize + 8L * i, 1000 + i);
            uploads.commit();
            uploader.copy(uploads, offset, headersSize, offset + headersSize, dataSize, geometry, 2);
            uploads.tick();
            downloads.download(geometry, (result, size) -> {
                for (int i = 0; i < 512; i++) {
                    long expected = i >= 16 && i < 24 ? 1000 + i - 16
                            : i >= 40 && i < 44 ? 1008 + i - 40 : 0;
                    if (MemoryUtil.memGetLong(result + 8L * i) != expected) {
                        throw new IllegalStateException("Batched quad copy mismatch at element " + i);
                    }
                }
            });
            downloads.flushWaitClear();

            // Partial chunks and workgroup boundaries must honor the runtime count uniform.
            for (int writes : new int[]{1, 3, 4, 5, 127, 128, 129}) {
                nodes.fill(0x12345678);
                metadata.fill(0x12345678);
                int chunks = (writes + 3) / 4;
                int inputSize = uploads.alignAllocation(chunks * 80);
                long inputOffset = uploads.rawUploadAddress(inputSize);
                long inputPtr = uploads.getBaseAddress() + inputOffset;
                MemoryUtil.memSet(inputPtr, 0, inputSize);
                for (int i = 0; i < chunks * 4; i++) {
                    long chunk = inputPtr + (i / 4) * 80L;
                    int location = i < writes ? (i / 2) | ((i & 1) << 31) : 200;
                    MemoryUtil.memPutInt(chunk + (i % 4) * 4L, location);
                    for (int word = 0; word < 4; word++) {
                        MemoryUtil.memPutInt(chunk + 16 + (i % 4) * 16L + word * 4L, 10000 + i * 10 + word);
                    }
                }
                uploads.commit();
                uploader.scatter(uploads, inputOffset, inputSize, nodes, metadata, writes);
                uploads.tick();
                int count = writes;
                downloads.download(nodes, (result, size) -> checkScatter(result, count, 0));
                downloads.download(metadata, (result, size) -> checkScatter(result, count, 1));
                downloads.flushWaitClear();
            }
            System.out.println("Metal node uploads OK: batched quad copies and scatter into nodes/metadata at chunk/workgroup boundaries");
        } finally {
            geometry.free();
            nodes.free();
            metadata.free();
            backend.shutdown();
        }
    }

    private static void checkScatter(long ptr, int writes, int destination) {
        for (int entry = 0; entry < 256; entry++) {
            int source = entry * 2 + destination;
            for (int word = 0; word < 4; word++) {
                int expected = source < writes ? 10000 + source * 10 + word : 0x12345678;
                int actual = MemoryUtil.memGetInt(ptr + entry * 16L + word * 4L);
                if (actual != expected) throw new IllegalStateException("Scatter writes=" + writes
                        + " destination=" + destination + " entry=" + entry + " word=" + word);
            }
        }
    }
}
