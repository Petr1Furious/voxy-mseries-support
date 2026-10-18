package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.util.FrameTiming;
import org.lwjgl.system.MemoryUtil;

/**
 * How many draws the CPU encodes per layer on Metal, which has no draw count from a buffer.
 * Waiting for command generation to read the exact counts would stall the CPU each frame, so the
 * bounds come from the counts of recent frames plus headroom. metal_pad.comp turns commands between
 * the generated count and the bound into empty draws. If a count exceeds its bound, the extra draws
 * are skipped for that frame; the next bounds grow to cover it.
 *
 * The first build has no history and waits for exact counts, as does every build with
 * {@code -Dvoxy.metal.syncDraws=true}.
 */
public final class MetalDrawBounds {
    private static final boolean SYNC = Boolean.getBoolean("voxy.metal.syncDraws");
    private static final int SLOTS = 8;
    private static final int[] CAPACITY = {GpuMDICCapacity.OPAQUE, GpuMDICCapacity.TRANSLUCENT, GpuMDICCapacity.TEMPORAL};
    private final IGpuBuffer history;
    private final long counts;
    private final int[][] encoded = new int[SLOTS][3];
    private final int[] bounds = new int[3];
    private int slot;
    private boolean primed;

    MetalDrawBounds(RenderBackend backend) {
        this.history = backend.createBuffer(SLOTS * 16L, 0, true);
        this.counts = ((MetalBuffer) history).getContentsPtr();
    }

    IGpuBuffer history() { return history; }

    /** Draw bound of the most recent build for OPAQUE (0), TRANSLUCENT (1) or TEMPORAL (2). */
    public int bound(int layer) { return bounds[layer]; }

    /** Chooses the bounds for the next build and returns the history slot it records into. */
    int next() {
        slot = (slot + 1) % SLOTS;
        // The slot being reused was written SLOTS builds ago; anything over its bounds was not drawn.
        for (int layer = 0; layer < 3; layer++) {
            if (Integer.toUnsignedLong(count(slot, layer)) > encoded[slot][layer]) FrameTiming.drawOverflows[layer]++;
        }
        int opaque = 0, translucent = 0, temporal = 0, sections = 0;
        for (int i = 0; i < SLOTS; i++) {
            opaque = Math.max(opaque, count(i, 0));
            translucent = Math.max(translucent, count(i, 1));
            temporal = Math.max(temporal, count(i, 2));
            sections = Math.max(sections, count(i, 3));
        }
        if (Integer.toUnsignedLong(sections) > GpuMDICCapacity.SECTIONS) {
            throw new IllegalStateException("Traversal render list exceeds MDIC section capacity");
        }
        bounds[0] = headroom(opaque, 0);
        bounds[1] = headroom(translucent, 1);
        bounds[2] = headroom(temporal, 2);
        System.arraycopy(bounds, 0, encoded[slot], 0, 3);
        if (FrameTiming.ENABLED) {
            FrameTiming.liveDraws += count(previous(), 0) + count(previous(), 1) + count(previous(), 2);
            FrameTiming.sections += count(previous(), 3);
        }
        return slot;
    }

    /** True when the build just encoded must wait and use its exact counts. */
    boolean needsExactCounts() { return SYNC || !primed; }

    /** Uses the finished build's counts: commands below each count are valid even past the padded bound. */
    void exact(long countBuffer) {
        for (int layer = 0; layer < 3; layer++) {
            bounds[layer] = (int) Math.min(Integer.toUnsignedLong(MemoryUtil.memGetInt(countBuffer + 12 + layer * 4L)), CAPACITY[layer]);
        }
        encoded[slot][0] = bounds[0]; encoded[slot][1] = bounds[1]; encoded[slot][2] = bounds[2];
        primed = true;
    }

    private int previous() { return (slot + SLOTS - 1) % SLOTS; }

    private int count(int slot, int index) { return MemoryUtil.memGetInt(counts + slot * 16L + index * 4L); }

    private static int headroom(int recent, int layer) {
        long bound = Integer.toUnsignedLong(recent) * 3 / 2 + 1024;
        return (int) Math.min(bound, CAPACITY[layer]);
    }

    void free() { history.free(); }
}
