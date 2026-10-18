package me.cortex.voxy.client.core.util;

/**
 * VOXY_FRAME_TIMING=1 (or -Dvoxy.dev.perf=true) — per-stage CPU cost probe for the Metal path.
 *
 * Accumulators are render-thread only (no atomics needed: every producer
 * and reporter run on the render thread). ENABLED is a static final read once
 * at class load: when disabled the JIT folds every call-site guard to a no-op.
 */
public final class FrameTiming {
    public static final boolean ENABLED = "1".equals(System.getenv("VOXY_FRAME_TIMING")) || Boolean.getBoolean("voxy.dev.perf");

    /** Whole Metal world frame (MetalFrameRenderer.render) on the CPU. */
    public static long voxyFrameNs;
    public static long voxyFrames;
    /** Blocking command-buffer completion waits (MetalRenderBackend.submit) and their count. */
    public static long submitWaitNs;
    public static long submitCount;
    /** glFinish calls in the GL/Metal frame handoff. */
    public static long glFinishNs;
    public static long glFinishCount;
    /** The per-draw indirect JNI loop (MetalRenderEncoder.drawIndexedIndirect). */
    public static long jniDrawLoopNs;
    /** Draws issued through that loop, including empty padding draws. */
    public static long jniDrawCount;
    /** Generated (non-empty) terrain draws, from MetalDrawBounds' count history. */
    public static long liveDraws;
    /** Sections in the traversal render list, i.e. boxes drawn by the visibility raster. */
    public static long sections;
    /** Layers whose generated draws exceeded the encoded bound, so some draws were skipped for a frame. */
    public static final long[] drawOverflows = new long[3];

    /** GPU execution time of Voxy command buffers, keyed by the code that submitted them. */
    public static final java.util.Map<String, long[]> gpuNsBySubmitter = new java.util.TreeMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance();

    /** Blocking finish() calls that had GPU work to wait for, keyed by caller. */
    public static final java.util.Map<String, Long> waits = new java.util.TreeMap<>();

    /** -Dvoxy.dev.perfSplit=true: commit after every frame stage so GPU time is attributed per stage. */
    public static final boolean SPLIT = ENABLED && Boolean.getBoolean("voxy.dev.perfSplit");
    private static String stage;

    /** Profiling only: ends the current command buffer and labels it with the stage that encoded it. */
    public static void split(me.cortex.voxy.client.core.gpu.RenderBackend backend, String label) {
        if (!SPLIT) return;
        stage = label;
        backend.submit();
        stage = null;
    }

    /** First caller outside the Metal backend and the stream helpers. */
    public static String submitter() {
        if (stage != null) return stage;
        return WALKER.walk(frames -> frames.map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
                .filter(name -> !name.startsWith("MetalRenderBackend.") && !name.startsWith("FrameTiming."))
                .findFirst().orElse("?"));
    }

    public static void gpu(String submitter, long nanos) {
        long[] entry = gpuNsBySubmitter.computeIfAbsent(submitter, k -> new long[2]);
        entry[0] += nanos; entry[1]++;
    }

    private FrameTiming() {}

    public static void reset() {
        voxyFrameNs = 0;
        voxyFrames = 0;
        submitWaitNs = 0;
        submitCount = 0;
        glFinishNs = 0;
        glFinishCount = 0;
        jniDrawLoopNs = 0;
        jniDrawCount = 0;
    }
}
