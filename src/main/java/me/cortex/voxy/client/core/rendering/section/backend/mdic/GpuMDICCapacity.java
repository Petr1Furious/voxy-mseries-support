package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;

/** Every queued section can emit six directional groups, a double-sided group and one translucent group. */
public final class GpuMDICCapacity {
    private GpuMDICCapacity() {}
    public static final int SECTIONS = HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE;
    public static final int OPAQUE = Math.multiplyExact(SECTIONS, 7);
    public static final int TEMPORAL = OPAQUE;
    public static final int TRANSLUCENT = SECTIONS;
    public static final int TEMPORAL_BASE = OPAQUE + TRANSLUCENT;
    public static final long DRAW_BYTES = 20L * (OPAQUE + TRANSLUCENT + TEMPORAL);
}
