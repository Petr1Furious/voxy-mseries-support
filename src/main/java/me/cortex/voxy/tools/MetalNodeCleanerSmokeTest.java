package me.cortex.voxy.tools;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.atomic.AtomicInteger;

/** Executes visibility updates, eligibility filtering, GPU selection, transform and removal readback. */
public final class MetalNodeCleanerSmokeTest {
    public static void main(String[] args) {
        long smallHeap = 256L << 20;
        for (long used : new long[]{0, 16L << 20, 128L << 20, 192L << 20}) {
            if (NodeCleaner.shouldCleanGeometry(smallHeap, used)) {
                throw new AssertionError("Premature geometry eviction: 256 MiB heap, used MiB=" + (used >> 20));
            }
        }
        if (!NodeCleaner.shouldCleanGeometry(smallHeap, 193L << 20)) throw new AssertionError("Small heap pressure ignored");
        long largeHeap = 4L << 30;
        if (NodeCleaner.shouldCleanGeometry(largeHeap, largeHeap - 256_000_000L)) throw new AssertionError("Large heap reserve boundary");
        if (!NodeCleaner.shouldCleanGeometry(largeHeap, largeHeap - 256_000_000L + 1)) throw new AssertionError("Large heap pressure ignored");
        MetalRenderBackend backend = new MetalRenderBackend();
        var nodes = backend.createBuffer(2048 * 16);
        try (var uploads = new UploadStream(backend, 65536);
             var downloads = new DownloadStream(backend, 16384)) {
            AtomicInteger batches = new AtomicInteger();
            AtomicInteger expectedMin = new AtomicInteger();
            NodeCleaner cleaner = new NodeCleaner(backend, 2048, uploads, downloads, batch -> {
                try {
                    if (batch.size != 256 * 8) throw new IllegalStateException("Wrong removal batch size");
                    IntOpenHashSet selected = new IntOpenHashSet();
                    for (int i = 0; i < 256; i++) {
                        int id = MemoryUtil.memGetInt(batch.address + i * 8L);
                        int secondWord = MemoryUtil.memGetInt(batch.address + i * 8L + 4);
                        if (expectedMin.get() < 0 && id == -1 && secondWord == -1) continue;
                        int lower = expectedMin.get() < 0 ? 5 : expectedMin.get();
                        int upper = expectedMin.get() < 0 ? 8 : expectedMin.get() + 256;
                        if (id < lower || id >= upper || secondWord != 0x12340000 + id || !selected.add(id)) {
                            throw new IllegalStateException("Invalid, ineligible or duplicate removal candidate " + id);
                        }
                    }
                    if (selected.size() != (expectedMin.get() < 0 ? 3 : 256)) throw new IllegalStateException("Candidate count mismatch");
                    batches.incrementAndGet();
                } finally {
                    batch.free();
                }
            });
            try {
                cleaner.tick(nodes);
                for (int count : new int[]{1, 3, 127, 128, 129}) {
                    var ids = new IntOpenHashSet();
                    for (int i = 0; i < count; i++) ids.add(i | ((i & 1) << 31));
                    cleaner.updateIds(ids);
                    uploads.tick();
                    downloads.download(cleaner.getVisibilityBuffer(), (ptr, size) -> {
                        for (int i = 0; i < 2048; i++) {
                            int expected = i < count && (i & 1) == 1 ? cleaner.getVisibilityId() : -1;
                            if (MemoryUtil.memGetInt(ptr + i * 4L) != expected) {
                                throw new IllegalStateException("Visibility update mismatch for count " + count + " ID " + i);
                            }
                        }
                    });
                    downloads.flushWaitClear();
                }
                for (int active : new int[]{512, 1024, 1536, 2048, 8}) {
                    long nodePtr = uploads.uploadTo(nodes);
                    MemoryUtil.memSet(nodePtr, 255, nodes.size());
                    long visibilityPtr = uploads.uploadTo(cleaner.getVisibilityBuffer());
                    MemoryUtil.memSet(visibilityPtr, 255, 8192);
                    for (int i = 1; i < active; i++) {
                        MemoryUtil.memPutInt(nodePtr + i * 16L, i);
                        MemoryUtil.memPutInt(nodePtr + i * 16L + 4, 0x12340000 + i);
                        MemoryUtil.memPutInt(nodePtr + i * 16L + 8, i);
                        MemoryUtil.memPutInt(nodePtr + i * 16L + 12, 0x00ffffff);
                        MemoryUtil.memPutInt(visibilityPtr + i * 4L, active - i);
                    }
                    MemoryUtil.memPutInt(visibilityPtr, 0);
                    MemoryUtil.memPutInt(nodePtr + 16 + 8, 1 | (1 << 24));
                    MemoryUtil.memPutInt(nodePtr + 32, (4 << 28) | 2);
                    MemoryUtil.memPutInt(nodePtr + 48 + 8, 0x00ffffff);
                    MemoryUtil.memPutInt(nodePtr + 64 + 8, 0x00fffffe);
                    for (int i = 1; i <= 4; i++) MemoryUtil.memPutInt(visibilityPtr + i * 4L, 0);
                    uploads.tick();
                    cleaner.tick(nodes);
                    expectedMin.set(active == 8 ? -1 : active - 256);
                    int before = batches.get();
                    cleaner.clean(nodes, active);
                    downloads.flushWaitClear();
                    if (batches.get() != before + 1) throw new IllegalStateException("Removal readback callback missing");
                    downloads.download(cleaner.getVisibilityBuffer(), (ptr, size) -> {
                        int start = active == 8 ? 5 : active - 256;
                        for (int i = start; i < active; i++) {
                            if (MemoryUtil.memGetInt(ptr + i * 4L) != cleaner.getVisibilityId() + 60) {
                                throw new IllegalStateException("Selected node visibility was not deferred");
                            }
                        }
                    });
                    downloads.flushWaitClear();
                }
                try {
                    cleaner.updateIds(new IntOpenHashSet(new int[]{2048}));
                    throw new IllegalStateException("Out-of-range visibility ID accepted");
                } catch (IllegalArgumentException expected) {}
                System.out.println("Metal node cleaner OK: capacity-aware pressure thresholds, visibility batches, eligibility, 256 unique oldest nodes and removal readback");
            } finally {
                cleaner.free();
            }
        } finally {
            nodes.free();
            backend.shutdown();
        }
    }
}
