package me.cortex.voxy.tools;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.hierachical.GpuHierarchicalTraversal;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/** Real tree traversal with indirect child dispatches, mesh requests, visibility and HiZ rejection. */
public final class MetalTraversalSmokeTest {
    public static void main(String[] args) {
        MetalRenderBackend backend = new MetalRenderBackend();
        IGpuBuffer scene = backend.createBuffer(1024), requests = backend.createBuffer(512), render = backend.createBuffer(1028);
        IGpuBuffer nodes = backend.createBuffer(4096), meta = backend.createBuffer(GpuHierarchicalTraversal.ITERATIONS * 16L);
        IGpuBuffer top = backend.createBuffer(256), queueA = backend.createBuffer(1024), queueB = backend.createBuffer(1024);
        IGpuBuffer visibility = backend.createBuffer(1024), stats = backend.createBuffer(1024);
        var buffers = new GpuHierarchicalTraversal.Buffers(scene, requests, render, nodes, meta, top, queueA, queueB, visibility, stats);
        try (var uploads = new UploadStream(backend, 32768)) {
            for (boolean reverse : new boolean[]{false, true}) {
                for (boolean zero : new boolean[]{false, true}) {
                    var properties = new RenderProperties(zero, reverse, false);
                    var hiz = new HiZBuffer(properties, backend);
                    var source = backend.createTexture().storeUploadable(0x822e, 1, 64, 64);
                    var depth = new MemoryBuffer(64 * 64 * 4);
                    try (var traversal = new GpuHierarchicalTraversal(backend, properties, true, 256);
                         var bounded = new GpuHierarchicalTraversal(backend, properties, true, 1)) {
                        for (String mode : new String[]{"children", "requestParent", "requestLeaf", "frustum", "hiz", "distance", "empty", "queueFull", "manyRoots"}) {
                            for (IGpuBuffer buffer : new IGpuBuffer[]{scene, requests, render, meta, top, queueA, queueB, visibility, stats}) buffer.zero();
                            nodes.fill(-1);
                            long scenePtr = uploads.uploadTo(scene);
                            MemoryUtil.memSet(scenePtr, 0, scene.size());
                            new Matrix4f().m00(1f / 256).m11(1f / 256).m22(reverse ? -0.001f : 0.001f)
                                    .m32(reverse ? 0.7f : 0.2f).getToAddress(scenePtr);
                            MemoryUtil.memPutInt(scenePtr + 76, (64 << 16) | 64);
                            MemoryUtil.memPutFloat(scenePtr + 92, 0.0001f);
                            MemoryUtil.memPutInt(scenePtr + 192, 256);
                            MemoryUtil.memPutInt(scenePtr + 196, 7);
                            MemoryUtil.memPutInt(scenePtr + 200, 50);
                            MemoryUtil.memPutFloat(scenePtr + 204, -1);
                            if (mode.equals("frustum")) {
                                MemoryUtil.memPutFloat(scenePtr + 96, 1);
                                MemoryUtil.memPutFloat(scenePtr + 108, -1000);
                            }
                            if (mode.equals("distance")) {
                                MemoryUtil.memPutInt(scenePtr + 64, -10);
                                MemoryUtil.memPutFloat(scenePtr + 204, 1);
                            }
                            long nodePtr = uploads.uploadTo(nodes);
                            MemoryUtil.memSet(nodePtr, 255, nodes.size());
                            writeNode(nodePtr, 1, 1, 10 | (4 << 24), mode.equals("requestParent") ? 0x00ffffff : 2);
                            writeNode(nodePtr, 2, 0, mode.equals("requestLeaf") ? 0x00ffffff : 20, 0x00ffffff);
                            writeNode(nodePtr, 3, 0, 30, 0x00ffffff);
                            int topCount = mode.equals("empty") ? 0 : mode.equals("manyRoots") ? 64 : 1;
                            long topPtr = uploads.uploadTo(top);
                            MemoryUtil.memSet(topPtr, 0, top.size());
                            MemoryUtil.memPutInt(topPtr, 1);
                            if (mode.equals("manyRoots")) {
                                for (int i = 0; i < 64; i++) {
                                    writeNode(nodePtr, i, 1, 10 | (4 << 24), 64 + i * 2);
                                    MemoryUtil.memPutInt(topPtr + i * 4L, i);
                                }
                                for (int i = 0; i < 128; i++) writeNode(nodePtr, 64 + i, 0, 1000 + i, 0x00ffffff);
                            }
                            long metaPtr = uploads.uploadTo(meta);
                            for (int i = 0; i < GpuHierarchicalTraversal.ITERATIONS; i++) {
                                MemoryUtil.memPutInt(metaPtr + i * 16L, i == 0 ? (topCount + 31) / 32 : 0);
                                MemoryUtil.memPutInt(metaPtr + i * 16L + 4, 1);
                                MemoryUtil.memPutInt(metaPtr + i * 16L + 8, 1);
                                MemoryUtil.memPutInt(metaPtr + i * 16L + 12, i == 0 ? topCount : 0);
                            }
                            uploads.commit();
                            float depthValue = mode.equals("hiz") ? (reverse ? 0.95f : 0.05f) : properties.clearDepth();
                            for (int i = 0; i < 4096; i++) MemoryUtil.memPutFloat(depth.address + i * 4L, depthValue);
                            source.uploadSubImage2D(0, 0, 0, 64, 64, 0x1903, 0x1406, depth.address);
                            hiz.buildMipChain(source, 64, 64);
                            (mode.equals("queueFull") ? bounded : traversal).traverse(buffers, hiz.getTexture(), topCount);
                            uploads.tick();
                            backend.finish();
                            check(buffers, mode);
                        }
                    } finally {
                        depth.free();
                        source.free();
                        hiz.free();
                    }
                }
            }
            System.out.println("Metal traversal OK: indirect children, mesh requests, real HiZ/frustum/distance culling, statistics and bounded-queue fallback");
        } finally {
            for (IGpuBuffer buffer : new IGpuBuffer[]{scene, requests, render, nodes, meta, top, queueA, queueB, visibility, stats}) buffer.free();
            backend.shutdown();
        }
    }

    private static void writeNode(long ptr, int id, int lod, int meshAndFlags, int children) {
        MemoryUtil.memPutInt(ptr + id * 16L, lod << 28);
        MemoryUtil.memPutInt(ptr + id * 16L + 4, 0);
        MemoryUtil.memPutInt(ptr + id * 16L + 8, meshAndFlags);
        MemoryUtil.memPutInt(ptr + id * 16L + 12, children);
    }

    private static long ptr(IGpuBuffer buffer) { return ((MetalBuffer) buffer).getContentsPtr(); }

    private static void check(GpuHierarchicalTraversal.Buffers buffers, String mode) {
        int[] expected = mode.equals("children") ? new int[]{20, 30} : mode.equals("requestParent") || mode.equals("queueFull")
                ? new int[]{10} : mode.equals("requestLeaf") ? new int[]{30} : new int[0];
        if (mode.equals("manyRoots")) {
            expected = new int[128];
            for (int i = 0; i < 128; i++) expected[i] = 1000 + i;
        }
        int count = MemoryUtil.memGetInt(ptr(buffers.renderList()));
        if (count != expected.length) throw new IllegalStateException(mode + ": render count=" + count);
        var actual = new IntOpenHashSet();
        for (int i = 0; i < count; i++) actual.add(MemoryUtil.memGetInt(ptr(buffers.renderList()) + 4L + i * 4L));
        if (!actual.equals(new IntOpenHashSet(expected))) throw new IllegalStateException(mode + ": wrong meshes " + actual);
        int requestCount = mode.equals("requestParent") || mode.equals("requestLeaf") ? 1 : 0;
        if (MemoryUtil.memGetInt(ptr(buffers.requests())) != requestCount) throw new IllegalStateException(mode + ": request count mismatch");
        if (requestCount != 0) {
            int nodeId = mode.equals("requestParent") ? 1 : 2;
            int expectedPosition = mode.equals("requestParent") ? 1 << 28 : 0;
            if (MemoryUtil.memGetInt(ptr(buffers.requests()) + 8) != expectedPosition
                    || MemoryUtil.memGetInt(ptr(buffers.requests()) + 12) != 0
                    || (MemoryUtil.memGetInt(ptr(buffers.nodes()) + nodeId * 16L + 8) & (1 << 24)) == 0) {
                throw new IllegalStateException(mode + ": request position/flag mismatch");
            }
        }
        boolean descended = mode.equals("children") || mode.equals("requestLeaf") || mode.equals("manyRoots");
        int childCount = mode.equals("manyRoots") ? 128 : descended ? 2 : 0;
        if (MemoryUtil.memGetInt(ptr(buffers.metadata()) + 16) != ((childCount + 31) / 32)
                || MemoryUtil.memGetInt(ptr(buffers.metadata()) + 28) != childCount) {
            throw new IllegalStateException(mode + ": child indirect dispatch metadata mismatch");
        }
        if (mode.equals("children")) {
            if (MemoryUtil.memGetInt(ptr(buffers.visibility()) + 8) != 7 || MemoryUtil.memGetInt(ptr(buffers.visibility()) + 12) != 7
                    || MemoryUtil.memGetInt(ptr(buffers.statistics())) != 2 || MemoryUtil.memGetInt(ptr(buffers.statistics()) + 4) != 1) {
                throw new IllegalStateException("Visibility/statistics were not updated");
            }
        }
    }
}
