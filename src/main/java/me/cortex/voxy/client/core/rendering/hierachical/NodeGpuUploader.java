package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static org.lwjgl.opengl.GL30C.glUniform1ui;
import static org.lwjgl.opengl.GL42C.GL_UNIFORM_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.*;

/** Encodes the current async node manager's batched geometry and metadata updates. */
public final class NodeGpuUploader implements AutoCloseable {
    private static final Map<String, String> SCATTER_BINDINGS = Map.of("INPUT_BUFFER_BINDING", "0",
            "OUTPUT_BUFFER1_BINDING", "1", "OUTPUT_BUFFER2_BINDING", "2");
    private static final Map<String, String> COPY_BINDINGS = Map.of("INPUT_HEADER_BUFFER_BINDING", "0",
            "INPUT_DATA_BUFFER_BINDING", "1", "OUTPUT_BUFFER_BINDING", "2");
    private final RenderBackend backend;
    private final Shader glScatter;
    private final Shader glCopy;
    private final IGpuPipeline scatter;
    private final IGpuPipeline copy;

    public NodeGpuUploader() {
        this.backend = null;
        this.scatter = null;
        this.copy = null;
        this.glScatter = compileGl("voxy:util/scatter.comp", SCATTER_BINDINGS);
        try {
            this.glCopy = compileGl("voxy:util/memcpy.comp", COPY_BINDINGS);
        } catch (RuntimeException | Error failure) {
            this.glScatter.free();
            throw failure;
        }
    }

    public NodeGpuUploader(RenderBackend backend) {
        this.backend = backend;
        this.glScatter = null;
        this.glCopy = null;
        this.scatter = compileGpu(backend, "voxy:util/scatter.comp", SCATTER_BINDINGS, 128);
        try {
            this.copy = compileGpu(backend, "voxy:util/memcpy.comp", COPY_BINDINGS, 256);
        } catch (RuntimeException | Error failure) {
            this.scatter.close();
            throw failure;
        }
    }

    private static Shader compileGl(String name, Map<String, String> bindings) {
        var builder = Shader.make();
        bindings.forEach(builder::define);
        return builder.add(ShaderType.COMPUTE, name).compile();
    }

    private static IGpuPipeline compileGpu(RenderBackend backend, String name, Map<String, String> bindings, int size) {
        return backend.createComputePipeline(new ComputePipelineDesc(ShaderLoader.parse(name), bindings,
                null, null, size, 1, 1, name));
    }

    public void copy(UploadStream uploads, long headerOffset, long headerSize, long dataOffset, long dataSize,
                     IGpuBuffer destination, int jobs) {
        if (jobs <= 0) throw new IllegalArgumentException("Copy requires at least one job");
        checkRange(uploads, headerOffset, headerSize);
        checkRange(uploads, dataOffset, dataSize);
        if (headerSize < jobs * 16L) throw new IllegalArgumentException("Copy header is too small");
        destination.assertNotFreed();
        if (this.backend == null) {
            if (!(destination instanceof GlBuffer)) throw new IllegalArgumentException("GL copy requires a GL destination");
            int staging = uploads.getRawBufferId();
            this.glCopy.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, staging, headerOffset, headerSize);
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, staging, dataOffset, dataSize);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, destination.id());
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(jobs, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        } else {
            try (var encoder = this.backend.beginComputePass()) {
                encoder.setPipeline(this.copy);
                encoder.setBuffer(0, uploads.getBuffer(), headerOffset, headerSize);
                encoder.setBuffer(1, uploads.getBuffer(), dataOffset, dataSize);
                encoder.setBuffer(2, destination, 0);
                encoder.dispatch(jobs, 1, 1);
            }
        }
    }

    public void scatter(UploadStream uploads, long offset, long size, IGpuBuffer nodes, IGpuBuffer metadata, int writes) {
        if (writes <= 0 || size < ((writes + 3L) / 4) * 80) throw new IllegalArgumentException("Invalid scatter batch");
        checkRange(uploads, offset, size);
        nodes.assertNotFreed();
        metadata.assertNotFreed();
        if (this.backend == null) {
            if (!(nodes instanceof GlBuffer) || !(metadata instanceof GlBuffer)) {
                throw new IllegalArgumentException("GL scatter requires GL destinations");
            }
            int staging = uploads.getRawBufferId();
            this.glScatter.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, staging, offset, size);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodes.id());
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, metadata.id());
            glUniform1ui(0, writes);
            glMemoryBarrier(GL_UNIFORM_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute((writes + 127) / 128, 1, 1);
            glMemoryBarrier(GL_UNIFORM_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        } else {
            try (MemoryStack stack = MemoryStack.stackPush(); var encoder = this.backend.beginComputePass()) {
                var parameters = stack.calloc(16);
                parameters.putInt(0, writes);
                encoder.setPipeline(this.scatter);
                encoder.setBuffer(0, uploads.getBuffer(), offset, size);
                encoder.setBuffer(1, nodes, 0);
                encoder.setBuffer(2, metadata, 0);
                encoder.setBytes(3, MemoryUtil.memAddress(parameters), 16);
                encoder.dispatch((writes + 127) / 128, 1, 1);
            }
        }
    }

    private static void checkRange(UploadStream uploads, long offset, long size) {
        if (offset < 0 || size <= 0 || offset > uploads.getBuffer().size()
                || size > uploads.getBuffer().size() - offset) {
            throw new IllegalArgumentException("Staging range out of bounds");
        }
    }

    @Override public void close() {
        if (this.backend == null) {
            this.glScatter.free();
            this.glCopy.free();
        } else {
            this.backend.finish();
            this.scatter.close();
            this.copy.close();
        }
    }
}
