package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import org.lwjgl.system.MemoryUtil;


//Has a base index buffer of 16380 quads, and also a 1 cube byte index buffer at the end
public class SharedIndexBuffer implements AutoCloseable {
    public static final int CUBE_INDEX_OFFSET = (1<<16)*6*2;
    private static final class Quads { static final SharedIndexBuffer VALUE = new SharedIndexBuffer(); }
    private static final class Bytes { static final SharedIndexBuffer VALUE = new SharedIndexBuffer(true); }
    private static final class Cubes { static final SharedIndexBuffer VALUE = new SharedIndexBuffer(true, true); }
    public static SharedIndexBuffer getInstance() { return Quads.VALUE; }
    public static SharedIndexBuffer getByteInstance() { return Bytes.VALUE; }
    public static SharedIndexBuffer getByteCubesInstance() { return Cubes.VALUE; }

    private final IGpuBuffer indexBuffer;
    private final RenderBackend backend;

    /** Metal supports UInt16/UInt32 indices, so cube indices follow the quad data as UInt16. */
    public SharedIndexBuffer(RenderBackend backend, UploadStream uploads) {
        this.backend = java.util.Objects.requireNonNull(backend);
        this.indexBuffer = backend.createBuffer(getCubeOffset() + 36 * 2L);
        try {
            var quads = generateQuadIndicesInt(1 << 16);
            var cube = generateCubeIndexBuffer();
            try {
                long ptr = uploads.upload(this.indexBuffer, 0, this.indexBuffer.size());
                MemoryUtil.memSet(ptr, 0, this.indexBuffer.size());
                quads.cpyTo(ptr);
                for (int i = 0; i < 36; i++) {
                    MemoryUtil.memPutShort(ptr + getCubeOffset() + i * 2L,
                            (short) Byte.toUnsignedInt(MemoryUtil.memGetByte(cube.address + i)));
                }
                uploads.commit();
            } finally {
                quads.free();
                cube.free();
            }
        } catch (RuntimeException | Error failure) {
            backend.finish();
            this.indexBuffer.free();
            throw failure;
        }
    }

    public SharedIndexBuffer() {
        this.backend = null;
        this.indexBuffer = new GlBuffer((1<<16)*6*2 + 6*2*3);
        var quadIndexBuff = generateQuadIndicesShort(16380);
        var cubeBuff = generateCubeIndexBuffer();

        long ptr = UploadStream.getInstance().upload(this.indexBuffer, 0, this.indexBuffer.size());
        quadIndexBuff.cpyTo(ptr);
        cubeBuff.cpyTo((1<<16)*2*6 + ptr);

        quadIndexBuff.free();
        cubeBuff.free();
        UploadStream.getInstance().commit();
    }

    private SharedIndexBuffer(boolean type2) {
        this.backend = null;
        this.indexBuffer = new GlBuffer((1<<8)*6 + 6*2*3);
        var quadIndexBuff = generateQuadIndicesByte(63);
        var cubeBuff = generateCubeIndexBuffer();

        long ptr = UploadStream.getInstance().upload(this.indexBuffer, 0, this.indexBuffer.size());
        quadIndexBuff.cpyTo(ptr);
        cubeBuff.cpyTo((1<<8)*6 + ptr);

        quadIndexBuff.free();
        cubeBuff.free();
    }

    private SharedIndexBuffer(boolean type2, boolean type3) {
        this.backend = null;
        this.indexBuffer = new GlBuffer(6*2*3*(256/8));
        var cubeBuff = generateByteCubesIndexBuffer(256/8);

        cubeBuff.cpyTo(UploadStream.getInstance().upload(this.indexBuffer, 0, this.indexBuffer.size()));
        UploadStream.getInstance().commit();
        cubeBuff.free();
    }

    private static MemoryBuffer generateCubeIndexBuffer() {
        var buffer = new MemoryBuffer(6*2*3);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, 6*2*3 );

        //Bottom face
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 1);

        //top face
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 7);

        //north face
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 4);

        //south face
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 7);

        //west face
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 6);

        //east face
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 7);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 5);

        return buffer;
    }

    private static MemoryBuffer generateByteCubesIndexBuffer(int cnt) {
        var buffer = new MemoryBuffer((long) cnt *6*2*3);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, buffer.size);

        for (int i = 0; i < cnt; i++) {
            int j = i*8;

            //Bottom face
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));

            //top face
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));

            //north face
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));

            //south face
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));

            //west face
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));

            //east face
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
        }

        return buffer;
    }

    public static MemoryBuffer generateQuadIndicesByte(int quadCount) {
        if ((quadCount*4) >= 1<<8) {
            throw new IllegalArgumentException("Quad count to large");
        }
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutByte(ptr + (0), (byte) (i + 1));
            MemoryUtil.memPutByte(ptr + (1), (byte) (i + 2));
            MemoryUtil.memPutByte(ptr + (2), (byte) (i + 0));
            MemoryUtil.memPutByte(ptr + (3), (byte) (i + 1));
            MemoryUtil.memPutByte(ptr + (4), (byte) (i + 3));
            MemoryUtil.memPutByte(ptr + (5), (byte) (i + 2));

            ptr += 6;
        }

        return buffer;
    }
    public static MemoryBuffer generateQuadIndicesShort(int quadCount) {
        if ((quadCount*4) >= 1<<16) {
            throw new IllegalArgumentException("Quad count to large");
        }
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 2);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutShort(ptr + (0*2), (short) (i + 1));
            MemoryUtil.memPutShort(ptr + (1*2), (short) (i + 2));
            MemoryUtil.memPutShort(ptr + (2*2), (short) (i + 0));
            MemoryUtil.memPutShort(ptr + (3*2), (short) (i + 1));
            MemoryUtil.memPutShort(ptr + (4*2), (short) (i + 3));
            MemoryUtil.memPutShort(ptr + (5*2), (short) (i + 2));

            ptr += 6 * 2;
        }

        return buffer;
    }

    public static MemoryBuffer generateQuadIndicesInt(int quadCount) {
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 4);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutInt(ptr + (0*4), (i + 1));
            MemoryUtil.memPutInt(ptr + (1*4), (i + 2));
            MemoryUtil.memPutInt(ptr + (2*4), i);
            MemoryUtil.memPutInt(ptr + (3*4), (i + 1));
            MemoryUtil.memPutInt(ptr + (4*4), (i + 3));
            MemoryUtil.memPutInt(ptr + (5*4), (i + 2));
            ptr += 6 * 4;
        }
        return buffer;
    }

    public int getCubeOffset() { return this.backend == null ? CUBE_INDEX_OFFSET : (1 << 16) * 6 * 4; }
    public int getIndexType() { return this.backend == null ? 0 : 1; } // RenderEncoder UInt16 / UInt32

    public IGpuBuffer getBuffer() { return this.indexBuffer; }

    @Override
    public void close() {
        if (this.backend != null) this.backend.finish();
        this.indexBuffer.free();
    }

    public int id() {
        if (this.backend != null) throw new IllegalStateException("GPU indices must bind through an encoder");
        return this.indexBuffer.id();
    }
}
