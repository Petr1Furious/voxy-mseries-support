package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.atomic.AtomicInteger;

/** Verifies current 26.3 geometry allocation and packed metadata on Metal, without GL. */
public final class MetalGeometrySmokeTest {
    public static void main(String[] args) {
        MetalRenderBackend backend = new MetalRenderBackend();
        int initialBuffers = MetalBuffer.getCount();
        var data = new BasicSectionGeometryData(backend, 4, 4096);
        var manager = new BasicAsyncGeometryManager(4, 4096);
        try (var uploads = new UploadStream(backend, 4096);
             var downloads = new DownloadStream(backend, 4096)) {
            int first = manager.uploadSection(section(0x1122334455667788L, 0x12345678, 100));
            int second = manager.uploadSection(section(0x2233445566778899L, 0x23456789, 200));
            sync(manager, data, uploads);
            AtomicInteger callbacks = new AtomicInteger();
            downloads.download(data.getMetadataBuffer(), (ptr, size) -> {
                checkMetadata(ptr + 32L * first, 0x1122334455667788L, 0x12345678, 0);
                checkMetadata(ptr + 32L * second, 0x2233445566778899L, 0x23456789, 128);
                callbacks.incrementAndGet();
            });
            downloads.download(data.getGeometryBuffer(), 0, 64, (ptr, size) -> checkGeometry(ptr, 100));
            downloads.download(data.getGeometryBuffer(), 1024, 64, (ptr, size) -> checkGeometry(ptr, 200));
            downloads.flushWaitClear();
            if (callbacks.get() != 1 || data.getSectionCount() != 2) throw new IllegalStateException("Geometry sync failed");

            // Replacement exercises ID and heap reuse, not just initial allocation.
            int replacement = manager.uploadReplaceSection(first, section(0x33445566778899AAL, 0x3456789A, 300));
            sync(manager, data, uploads);
            downloads.download(data.getGeometryBuffer(), 0, 64, (ptr, size) -> checkGeometry(ptr, 300));
            downloads.download(data.getMetadataBuffer(), (ptr, size) ->
                    checkMetadata(ptr + 32L * replacement, 0x33445566778899AAL, 0x3456789A, 0));
            downloads.flushWaitClear();

            manager.removeSection(replacement);
            manager.removeSection(second);
            sync(manager, data, uploads);
            downloads.download(data.getMetadataBuffer(), (ptr, size) -> {
                for (int i = 0; i < 64; i++) {
                    if (MemoryUtil.memGetByte(ptr + i) != 0) throw new IllegalStateException("Removed metadata not cleared");
                }
            });
            downloads.flushWaitClear();
            if (data.getSectionCount() != 0 || manager.getGeometryUsedBytes() != 0) {
                throw new IllegalStateException("Removed geometry remains allocated");
            }
        } finally {
            data.free();
        }
        if (MetalBuffer.getCount() != initialBuffers) throw new IllegalStateException("Owned geometry buffer leak");

        var external = backend.createBuffer(4096);
        var borrowed = new BasicSectionGeometryData(backend, 4, external);
        borrowed.free();
        if (external.isFreed()) throw new IllegalStateException("Borrowed geometry was freed by metadata owner");
        external.free();
        if (MetalBuffer.getCount() != initialBuffers) throw new IllegalStateException("Borrowed geometry buffer leak");
        backend.shutdown();
        System.out.println("Metal geometry OK: 26.3 metadata ABI, quad uploads, replacement, removal and ownership");
    }

    private static BuiltSection section(long position, int aabb, int seed) {
        var memory = new MemoryBuffer(64);
        for (int i = 0; i < 8; i++) MemoryUtil.memPutLong(memory.address + 8L * i, seed + i);
        return new BuiltSection(position, (byte) 0, aabb, memory, new int[]{0, 1, 2, 3, 4, 5, 6, 7}, null);
    }

    private static void sync(BasicAsyncGeometryManager manager, BasicSectionGeometryData data, UploadStream uploads) {
        for (var entry : manager.getUploads().int2ObjectEntrySet()) {
            uploads.upload(data.getGeometryBuffer(), Integer.toUnsignedLong(entry.getIntKey()) * 8, entry.getValue());
        }
        for (int id : manager.getUpdateIds()) {
            manager.writeMetadata(id, uploads.upload(data.getMetadataBuffer(), 32L * id, 32));
        }
        data.setSectionCount(manager.getSectionCount());
        uploads.tick();
    }

    private static void checkMetadata(long ptr, long position, int aabb, int quadOffset) {
        int[] words = {(int) (position >>> 32), (int) position, aabb, quadOffset,
                0x10001, 0x10001, 0x10001, 0x10001};
        for (int i = 0; i < words.length; i++) {
            if (MemoryUtil.memGetInt(ptr + 4L * i) != words[i]) throw new IllegalStateException("Metadata ABI mismatch at word " + i);
        }
    }

    private static void checkGeometry(long ptr, int seed) {
        for (int i = 0; i < 8; i++) {
            if (MemoryUtil.memGetLong(ptr + 8L * i) != seed + i) throw new IllegalStateException("Quad upload mismatch");
        }
    }
}
