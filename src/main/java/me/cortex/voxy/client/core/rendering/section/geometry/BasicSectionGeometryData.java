package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTransferBackend;
import me.cortex.voxy.client.core.gpu.GpuTransferBackend;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;

import static org.lwjgl.opengl.ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB;
import static org.lwjgl.opengl.ARBSparseBuffer.glBufferPageCommitmentARB;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;

public class BasicSectionGeometryData implements IGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;
    private final IGpuBuffer sectionMetadataBuffer;
    private final IGpuBuffer geometryBuffer;
    private final GpuTransferBackend backend;
    public final boolean isExternalGeometryBuffer;

    private final int maxSectionCount;
    private int currentSectionCount;

    public BasicSectionGeometryData(int maxSectionCount, GlBuffer geometryBuffer) {
        validateCapacity(maxSectionCount, geometryBuffer.size());
        this.backend = GlTransferBackend.INSTANCE;
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        //8 Cause a quad is 8 bytes
        if ((geometryBuffer.size()%8)!=0) {
            throw new IllegalStateException();
        }
        this.geometryBuffer = geometryBuffer;
        this.isExternalGeometryBuffer = true;
    }

    public BasicSectionGeometryData(int maxSectionCount, long geometryCapacity) {
        validateCapacity(maxSectionCount, geometryCapacity);
        this.backend = GlTransferBackend.INSTANCE;
        this.isExternalGeometryBuffer = false;
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        //8 Cause a quad is 8 bytes
        if ((geometryCapacity%8)!=0) {
            throw new IllegalStateException();
        }
        long start = System.currentTimeMillis();
        String msg = "Creating and zeroing " + (geometryCapacity/(1024*1024)) + "MB geometry buffer";
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            msg += " driver states " + (Capabilities.INSTANCE.getFreeDedicatedGpuMemory()/(1024*1024)) + "MB of free memory";
        }
        Logger.info(msg);
        Logger.info("if your game crashes/exits here without any other log message, try manually decreasing the geometry capacity");
        glGetError();//Clear any errors
        GlBuffer buffer = null;
        if (!(Capabilities.INSTANCE.isNvidia&&ThreadUtils.isWindows&&Capabilities.INSTANCE.sparseBuffer)) {//This hack makes it so it doesnt crash on renderdoc
            buffer = new GlBuffer(geometryCapacity, false);//Only do this if we are not on nvidia
            //TODO: FIXME: TEST, see if the issue is that we are trying to zero the entire buffer, try only zeroing increments
            // or dont zero it at all
        } else {
            Logger.info("Running on nvidia, using workaround sparse buffer allocation");
        }
        int error = glGetError();
        if (error != GL_NO_ERROR || buffer == null) {
            if ((buffer == null || error == GL_OUT_OF_MEMORY) && Capabilities.INSTANCE.sparseBuffer) {
                if (buffer != null) {
                    Logger.error("Failed to allocate geometry buffer, attempting workaround with sparse buffers");
                    buffer.free();
                }
                buffer = new GlBuffer(geometryCapacity, GL_SPARSE_STORAGE_BIT_ARB);
                //buffer.zero();
                error = glGetError();
                if (error != GL_NO_ERROR) {
                    buffer.free();
                    throw new IllegalStateException("Unable to allocate geometry buffer using workaround, got gl error " + error);
                }
            } else {
                throw new IllegalStateException("Unable to allocate geometry buffer, got gl error " + error);
            }
        }
        this.geometryBuffer = buffer;
        long delta = System.currentTimeMillis() - start;
        Logger.info("Successfully allocated the geometry buffer in " + delta + "ms");
    }

    /** Allocate geometry using the selected GPU backend, without GL capability queries. */
    public BasicSectionGeometryData(RenderBackend backend, int maxSectionCount, long geometryCapacity) {
        validateCapacity(maxSectionCount, geometryCapacity);
        this.backend = backend;
        this.maxSectionCount = maxSectionCount;
        this.isExternalGeometryBuffer = false;
        this.sectionMetadataBuffer = backend.createBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        try {
            this.geometryBuffer = backend.createBuffer(geometryCapacity, 0, false);
        } catch (RuntimeException | Error failure) {
            this.sectionMetadataBuffer.free();
            throw failure;
        }
    }

    /** Metadata is owned here; the caller retains ownership of the supplied geometry buffer. */
    public BasicSectionGeometryData(RenderBackend backend, int maxSectionCount, IGpuBuffer geometryBuffer) {
        geometryBuffer.assertNotFreed();
        validateCapacity(maxSectionCount, geometryBuffer.size());
        this.backend = backend;
        this.maxSectionCount = maxSectionCount;
        this.isExternalGeometryBuffer = true;
        this.geometryBuffer = geometryBuffer;
        this.sectionMetadataBuffer = backend.createBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
    }

    private static void validateCapacity(int maxSectionCount, long capacity) {
        if (maxSectionCount <= 0 || capacity <= 0 || capacity % 8 != 0) {
            throw new IllegalArgumentException("Geometry requires positive section capacity and an 8-byte-aligned buffer");
        }
    }

    private long sparseCommitment = 0;//Tracks the current range of the allocated sparse buffer
    public void ensureAccessable(int maxElementAccess) {
        long size = (Integer.toUnsignedLong(maxElementAccess)*8L+65535L)&~65535L;
        //If we are a sparse buffer, ensure the memory upto the requested size is allocated
        if (this.geometryBuffer.isSparse()) {
            if (this.sparseCommitment < size) {//if we try to access memory outside the allocation range, allocate it
                glBindBuffer(GL_ARRAY_BUFFER, this.geometryBuffer.id());
                size += 65536L*1024;//increase size by 64mb to prevent driver allocation thrashing
                glBufferPageCommitmentARB(GL_ARRAY_BUFFER, this.sparseCommitment, size-this.sparseCommitment, true);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
                //Logger.info("Resizing sparse: " + this.sparseCommitment + ", " + (size-this.sparseCommitment));
                this.sparseCommitment = size;
            }
        }
    }

    public IGpuBuffer getGeometryBuffer() {
        return this.geometryBuffer;
    }

    public IGpuBuffer getMetadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    @Override
    public int getSectionCount() {
        return this.currentSectionCount;
    }

    public void setSectionCount(int count) {
        if (count < 0 || count > this.maxSectionCount) throw new IllegalArgumentException("Section count exceeds capacity");
        this.currentSectionCount = count;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {//In bytes
        return this.geometryBuffer.size();
    }

    @Override
    public void free() {
        if (this.backend != GlTransferBackend.INSTANCE) {
            this.backend.finish();
            this.sectionMetadataBuffer.free();
            if (!this.isExternalGeometryBuffer) this.geometryBuffer.free();
            return;
        }
        this.sectionMetadataBuffer.free();

        long gpuMemory = 0;
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            glFinish();
            gpuMemory = Capabilities.INSTANCE.getFreeDedicatedGpuMemory();
        }
        if (this.geometryBuffer.isSparse()) {
            glBindBuffer(GL_ARRAY_BUFFER, this.geometryBuffer.id());
            glBufferPageCommitmentARB(GL_ARRAY_BUFFER, 0, this.sparseCommitment, false);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }

        glFinish();

        if (!this.isExternalGeometryBuffer) {
            this.geometryBuffer.free();
            glFinish();
            if (Capabilities.INSTANCE.canQueryGpuMemory) {
                long releaseSize = (long) (this.geometryBuffer.size() * 0.75);//if gpu memory usage drops by 75% of the expected value assume we freed it
                if (this.geometryBuffer.isSparse()) {//If we are using sparse buffers, use the commited size instead
                    releaseSize = (long) (this.sparseCommitment * 0.75);
                }
                if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                    Logger.info("Attempting to wait for gpu memory to release");
                    long start = System.currentTimeMillis();

                    long TIMEOUT = 400;

                    while (System.currentTimeMillis() - start < TIMEOUT) {//Wait up to 2.5 seconds for memory to release
                        glFinish();
                        if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory > releaseSize) break;
                    }
                    if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                        Logger.warn("Failed to wait for gpu memory to be freed, this could indicate an issue with the driver");
                    }
                }
            }
        }
    }

    @Override
    public long getMaxCapacity() {
        return this.geometryBuffer.size();
    }
}
