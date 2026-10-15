package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.client.core.gpu.*;

import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.opengl.GL11C.glFlush;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL45C.glCopyNamedBufferSubData;

/** Real GL transfer implementation; does not select or initialize a Metal device. */
public final class GlTransferBackend implements GpuTransferBackend {
    public static final GlTransferBackend INSTANCE = new GlTransferBackend();

    private GlTransferBackend() {}

    @Override public IGpuPersistentBuffer createPersistentBuffer(long size, int flags) {
        return new GlPersistentMappedBuffer(size, flags);
    }
    @Override public IGpuFence createFence() { return new GlFence(); }
    @Override public int getBufferOffsetAlignment() { return Capabilities.INSTANCE.ssboBindingAlignment; }
    @Override public void memoryBarrier(int flags) { glMemoryBarrier(flags); }
    @Override public void finish() { glFinish(); }
    @Override public void submit() { glFlush(); }

    @Override public void copyBufferSubData(IGpuPersistentBuffer src, IGpuBuffer dst,
                                          long srcOffset, long dstOffset, long size) {
        if (!(src instanceof GlPersistentMappedBuffer) || !(dst instanceof GlBuffer)) {
            throw new IllegalArgumentException("GL transfers require GL buffers");
        }
        src.assertNotFreed();
        dst.assertNotFreed();
        glCopyNamedBufferSubData(src.id(), dst.id(), srcOffset, dstOffset, size);
    }

    @Override public void copyBufferSubData(IGpuBuffer src, IGpuPersistentBuffer dst,
                                          long srcOffset, long dstOffset, long size) {
        if (!(src instanceof GlBuffer) || !(dst instanceof GlPersistentMappedBuffer)) {
            throw new IllegalArgumentException("GL transfers require GL buffers");
        }
        src.assertNotFreed();
        dst.assertNotFreed();
        glCopyNamedBufferSubData(src.id(), dst.id(), srcOffset, dstOffset, size);
    }
}
