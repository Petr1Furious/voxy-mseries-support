package me.cortex.voxy.client.core.gpu;

/** Buffer streaming operations shared by the existing GL renderer and Metal. */
public interface GpuTransferBackend {
    IGpuPersistentBuffer createPersistentBuffer(long size, int flags);
    IGpuFence createFence();
    int getBufferOffsetAlignment();
    void memoryBarrier(int flags);
    void copyBufferSubData(IGpuPersistentBuffer src, IGpuBuffer dst, long srcOffset, long dstOffset, long size);
    void copyBufferSubData(IGpuBuffer src, IGpuPersistentBuffer dst, long srcOffset, long dstOffset, long size);
    /** Submit pending transfers and wait for completion before reusing CPU staging memory. */
    void finish();
    void submit();
}
