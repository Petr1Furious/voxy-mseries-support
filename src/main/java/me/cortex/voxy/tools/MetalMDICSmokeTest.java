package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.GpuMDICDrawCalls;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/** Runs the current visibility raster and MDIC compute chain, then checks real indirect commands. */
public final class MetalMDICSmokeTest {
    public static void main(String[] args) {
        var backend = new MetalRenderBackend();
        int buffers = MetalBuffer.getCount(), textures = MetalTexture.getCount();
        try {
            for (boolean reverse : new boolean[]{false, true}) {
                for (boolean zero : new boolean[]{false, true}) {
                    var properties = new RenderProperties(zero, reverse, false);
                    var viewport = new MDICViewport(properties, 16, backend);
                    var metadata = backend.createBuffer(16 * 32);
                    try (var uploads = new UploadStream(backend, 2 << 20);
                         var calls = new GpuMDICDrawCalls(backend, uploads, properties)) {
                        viewport.setScreenSize(128, 64).setProjection(new Matrix4f()
                                .m00(0.0125f).m11(0.5f).m22(0.1f).m30(-0.8f)
                                .m32(zero ? 0.5f : 0), 0.1f, 200).update();
                        long ptr = uploads.upload(metadata, 0, metadata.size());
                        MemoryUtil.memSet(ptr, 0, metadata.size());
                        for (int i = 0; i < 3; i++) {
                            // Level 0 section position x = 0, 1, 3; 1 translucent + 2 double-sided quads.
                            MemoryUtil.memPutInt(ptr + i * 32L + 4, (i == 2 ? 3 : i) << 4);
                            MemoryUtil.memPutInt(ptr + i * 32L + 12, (i + 1) * 10);
                            MemoryUtil.memPutInt(ptr + i * 32L + 16, (2 << 16) | 1);
                        }
                        ptr = uploads.upload(viewport.indirectLookupBuffer, 0, 16);
                        MemoryUtil.memPutInt(ptr, 3);
                        MemoryUtil.memPutInt(ptr + 4, 2);
                        MemoryUtil.memPutInt(ptr + 8, 0);
                        MemoryUtil.memPutInt(ptr + 12, 1);
                        MemoryUtil.memSet(uploads.upload(viewport.visibilityBuffer, 0, viewport.visibilityBuffer.size()),
                                0, viewport.visibilityBuffer.size());
                        uploads.commit();
                        backend.finish();
                        long indexPtr = ((MetalBuffer) calls.getIndices().getBuffer()).getContentsPtr();
                        int[] firstQuad = {1, 2, 0, 1, 3, 2};
                        for (int i = 0; i < 6; i++) {
                            if (MemoryUtil.memGetInt(indexPtr + 4L * i) != firstQuad[i]) throw new AssertionError("Quad index winding changed");
                        }
                        for (int i = 0; i < 36; i++) {
                            int index = Short.toUnsignedInt(MemoryUtil.memGetShort(indexPtr + calls.getIndices().getCubeOffset() + i * 2L));
                            if (index >= 8) throw new AssertionError("Cube index expansion");
                        }
                        viewport.depthBoundingBuffer.clear(properties.clearDepth());
                        viewport.frameId = 7;
                        calls.build(viewport, metadata, viewport.depthBoundingBuffer.getGpuDepthTex()); backend.finish();
                        checkCounts(viewport, 3, 3, 3);
                        checkCommands(viewport, true);
                        long visibility = ((MetalBuffer) viewport.visibilityBuffer).getContentsPtr();
                        for (int i = 0; i < 3; i++) {
                            if (MemoryUtil.memGetInt(visibility + 4L * i) != 7) throw new AssertionError("Visible cube did not rasterize");
                        }
                        long stats = ((MetalBuffer) calls.getStatistics()).getContentsPtr();
                        if (MemoryUtil.memGetInt(stats) != 3 || MemoryUtil.memGetInt(stats + 20) != 9) throw new AssertionError("MDIC statistics");
                        viewport.frameId = 8;
                        calls.build(viewport, metadata, viewport.depthBoundingBuffer.getGpuDepthTex()); backend.finish();
                        checkCounts(viewport, 3, 3, 0);
                        checkCommands(viewport, false);
                        // Last frame's temporal commands are stale: padding must turn them into empty draws.
                        if (viewport.metalDraws.bound(2) < 3) throw new AssertionError("Temporal bound below history");
                        for (int i = 0; i < 3; i++) {
                            long command = ((MetalBuffer) viewport.drawCallBuffer).getContentsPtr() + GpuMDICDrawCalls.TEMPORAL_OFFSET + i * 20L;
                            if (MemoryUtil.memGetInt(command) != 0 || MemoryUtil.memGetInt(command + 4) != 0) throw new AssertionError("Stale temporal command not padded");
                        }
                        for (int i = 0; i < 3; i++) {
                            if (MemoryUtil.memGetInt(visibility + 4L * i) != (8 | 0x80000000)) throw new AssertionError("Previous-frame visibility bit");
                        }
                        // All cube fragments lie behind this actual depth attachment.
                        viewport.depthBoundingBuffer.clear(properties.inverseClearDepth());
                        viewport.frameId = 9;
                        calls.build(viewport, metadata, viewport.depthBoundingBuffer.getGpuDepthTex()); backend.finish();
                        checkCounts(viewport, 0, 0, 0);
                        for (int i = 0; i < 3; i++) {
                            if (MemoryUtil.memGetInt(visibility + 4L * i) != (8 | 0x80000000)) throw new AssertionError("Occluded fragments wrote visibility");
                        }
                        MemoryUtil.memPutInt(uploads.upload(viewport.indirectLookupBuffer, 0, 4), 0);
                        uploads.commit();
                        viewport.frameId = 10;
                        calls.build(viewport, metadata, viewport.depthBoundingBuffer.getGpuDepthTex()); backend.finish();
                        checkCounts(viewport, 0, 0, 0);
                        if (MemoryUtil.memGetInt(((MetalBuffer) viewport.drawCountCallBuffer).getContentsPtr()) != 0) throw new AssertionError("Empty dispatch");
                    } finally {
                        metadata.free();
                        viewport.delete();
                    }
                    if (MetalBuffer.getCount() != buffers || MetalTexture.getCount() != textures) throw new AssertionError("MDIC resource leak");
                }
            }
            System.out.println("Metal MDIC OK: depth-tested indirect cube raster, temporal visibility, exact commands, translucent sorting and empty lists");
        } finally {
            backend.shutdown();
        }
    }

    private static void checkCounts(MDICViewport viewport, int opaque, int translucent, int temporal) {
        long ptr = ((MetalBuffer) viewport.drawCountCallBuffer).getContentsPtr();
        if (MemoryUtil.memGetInt(ptr + 12) != opaque || MemoryUtil.memGetInt(ptr + 16) != translucent
                || MemoryUtil.memGetInt(ptr + 20) != temporal) {
            throw new AssertionError("Unexpected draw counts: " + MemoryUtil.memGetInt(ptr + 12) + ","
                    + MemoryUtil.memGetInt(ptr + 16) + "," + MemoryUtil.memGetInt(ptr + 20));
        }
        if (MemoryUtil.memGetInt(ptr + 24) != 36 || MemoryUtil.memGetInt(ptr + 32) != 0) throw new AssertionError("Metal cube indirect ABI");
    }

    private static void checkCommands(MDICViewport viewport, boolean temporal) {
        long commands = ((MetalBuffer) viewport.drawCallBuffer).getContentsPtr();
        int[] sectionByDraw = {2, 0, 1};
        boolean[] found = new boolean[3];
        for (int i = 0; i < 3; i++) {
            long ptr = commands + i * 20L;
            int draw = MemoryUtil.memGetInt(ptr + 16);
            if (draw < 0 || draw >= 3 || found[draw]) throw new AssertionError("Opaque baseInstance");
            found[draw] = true;
            checkCommand(ptr, 12, ((sectionByDraw[draw] + 1) * 10 + 1) * 4, draw);
        }
        if (temporal) {
            boolean[] temporalFound = new boolean[3];
            for (int i = 0; i < 3; i++) {
                long ptr = commands + GpuMDICDrawCalls.TEMPORAL_OFFSET + i * 20L;
                int draw = MemoryUtil.memGetInt(ptr + 16);
                if (draw < 0 || draw >= 3 || temporalFound[draw]) throw new AssertionError("Temporal baseInstance");
                temporalFound[draw] = true;
                checkCommand(ptr, 12, ((sectionByDraw[draw] + 1) * 10 + 1) * 4, draw);
            }
        }
        // Sorted by section distance: x=3, x=1, x=0, despite shuffled traversal input.
        int[] sortedDraws = {0, 2, 1};
        for (int i = 0; i < 3; i++) {
            int draw = sortedDraws[i];
            checkCommand(commands + GpuMDICDrawCalls.TRANSLUCENT_OFFSET + i * 20L,
                    6, (sectionByDraw[draw] + 1) * 40, draw);
        }
    }

    private static void checkCommand(long ptr, int count, int vertex, int instance) {
        if (MemoryUtil.memGetInt(ptr) != count || MemoryUtil.memGetInt(ptr + 4) != 1
                || MemoryUtil.memGetInt(ptr + 8) != 0 || MemoryUtil.memGetInt(ptr + 12) != vertex
                || MemoryUtil.memGetInt(ptr + 16) != instance) throw new AssertionError("Indirect command ABI");
    }
}
