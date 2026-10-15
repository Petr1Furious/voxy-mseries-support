package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static org.lwjgl.opengl.GL43C.*;

/** Executes the current cleaner shaders through GL or explicit GPU compute encoders. */
final class NodeCleanerGpuPasses implements AutoCloseable {
    private static final Map<String, String> SORT = Map.of("WORK_SIZE", "64", "ELEMS_PER_THREAD", "8",
            "OUTPUT_SIZE", "256", "VISIBILITY_BUFFER_BINDING", "1", "OUTPUT_BUFFER_BINDING", "2", "NODE_DATA_BINDING", "3");
    private static final Map<String, String> TRANSFORM = Map.of("OUTPUT_SIZE", "256", "MIN_ID_BUFFER_BINDING", "0",
            "NODE_BUFFER_BINDING", "1", "OUTPUT_BUFFER_BINDING", "2", "VISIBILITY_BUFFER_BINDING", "3");
    private static final Map<String, String> UPDATE = Map.of("VISIBILITY_BUFFER_BINDING", "0", "LIST_BUFFER_BINDING", "1");
    private static final String ROOT = "voxy:lod/hierarchical/cleaner/";
    private final RenderBackend backend;
    private final Shader glSort, glTransform, glUpdate;
    private final IGpuPipeline sort, transform, update, merge;
    private final IGpuBuffer candidatesA, candidatesB;
    private final int maxNodeCount;

    NodeCleanerGpuPasses(RenderBackend backend, int maxNodeCount) {
        this.backend = backend;
        this.maxNodeCount = maxNodeCount;
        Shader s = null, t = null, u = null;
        IGpuPipeline gs = null, gt = null, gu = null, gm = null;
        IGpuBuffer a = null, b = null;
        try {
            if (backend == null) {
                s = compileGl("sort_visibility.comp", SORT);
                t = compileGl("result_transformer.comp", TRANSFORM);
                u = compileGl("batch_visibility_set.comp", UPDATE);
            } else {
                gs = compileGpu("select_candidates.comp", Map.of("NODE_DATA_BINDING", "3"), 64);
                var transformDefines = new java.util.HashMap<>(TRANSFORM);
                transformDefines.put("CANDIDATE_PAIRS", "1");
                gt = compileGpu("result_transformer.comp", transformDefines, 256);
                gm = compileGpu("merge_candidates.comp", Map.of(), 128);
                long scratchSize = (long) (maxNodeCount / 512) * 256 * 8;
                a = backend.createBuffer(scratchSize, 0, false);
                b = backend.createBuffer(scratchSize, 0, false);
                gu = compileGpu("batch_visibility_set.comp", UPDATE, 128);
            }
        } catch (RuntimeException | Error failure) {
            if (s != null) s.free();
            if (t != null) t.free();
            if (u != null) u.free();
            if (gs != null) gs.close();
            if (gt != null) gt.close();
            if (gu != null) gu.close();
            if (gm != null) gm.close();
            if (a != null) a.free();
            if (b != null) b.free();
            throw failure;
        }
        this.glSort = s; this.glTransform = t; this.glUpdate = u;
        this.sort = gs; this.transform = gt; this.update = gu; this.merge = gm;
        this.candidatesA = a; this.candidatesB = b;
    }

    private static Shader compileGl(String name, Map<String, String> defines) {
        var builder = Shader.make(PrintfDebugUtil.PRINTF_processor);
        defines.forEach(builder::define);
        return builder.add(ShaderType.COMPUTE, ROOT + name).compile();
    }

    private IGpuPipeline compileGpu(String name, Map<String, String> defines, int localSize) {
        String source = PrintfDebugUtil.PRINTF_processor.process(ShaderType.COMPUTE, ShaderLoader.parse(ROOT + name));
        return this.backend.createComputePipeline(new ComputePipelineDesc(source, defines,
                null, null, localSize, 1, 1, ROOT + name));
    }

    void update(UploadStream uploads, long offset, int count, int frame, IGpuBuffer visibility) {
        if (this.backend == null) {
            requireGl(visibility);
            this.glUpdate.bind();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, visibility.id());
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, uploads.getRawBufferId(), offset, uploads.alignAllocation(count * 4));
            glUniform1ui(0, count);
            glUniform1ui(1, frame);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute((count + 127) / 128, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        } else {
            try (MemoryStack stack = MemoryStack.stackPush(); var encoder = this.backend.beginComputePass()) {
                var parameters = stack.calloc(16);
                parameters.putInt(0, count).putInt(4, frame);
                encoder.setPipeline(this.update);
                encoder.setBuffer(0, visibility, 0);
                encoder.setBuffer(1, uploads.getBuffer(), offset, count * 4L);
                encoder.setBytes(4, MemoryUtil.memAddress(parameters), 16);
                encoder.dispatch((count + 127) / 128, 1, 1);
            }
        }
    }

    void clean(IGpuBuffer nodes, IGpuBuffer visibility, IGpuBuffer output, int groups, int frame) {
        if (this.backend == null) {
            requireGl(nodes); requireGl(visibility); requireGl(output);
            this.glSort.bind();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, visibility.id());
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, output.id());
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, nodes.id());
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(groups, 1, 1);
            this.glTransform.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, output.id(), 0, 1024);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodes.id());
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 2, output.id(), 1024, 2048);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, visibility.id());
            glUniform1ui(0, frame);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        } else {
            try (MemoryStack stack = MemoryStack.stackPush(); var encoder = this.backend.beginComputePass()) {
                var parameters = stack.calloc(16);
                parameters.putInt(0, groups * 512).putInt(4, this.maxNodeCount - 2);
                encoder.setPipeline(this.sort);
                encoder.setBuffer(1, visibility, 0);
                encoder.setBuffer(2, this.candidatesA, 0);
                encoder.setBuffer(3, nodes, 0);
                encoder.setBytes(4, MemoryUtil.memAddress(parameters), 16);
                encoder.dispatch(groups, 1, 1);
            }
            IGpuBuffer input = this.candidatesA, merged = this.candidatesB;
            while (groups > 1) {
                int nextGroups = (groups + 1) / 2;
                try (MemoryStack stack = MemoryStack.stackPush(); var encoder = this.backend.beginComputePass()) {
                    var parameters = stack.calloc(16);
                    parameters.putInt(0, groups);
                    encoder.setPipeline(this.merge);
                    encoder.setBuffer(0, input, 0);
                    encoder.setBuffer(2, merged, 0);
                    encoder.setBytes(4, MemoryUtil.memAddress(parameters), 16);
                    encoder.dispatch(nextGroups, 1, 1);
                }
                IGpuBuffer previous = input;
                input = merged;
                merged = previous;
                groups = nextGroups;
            }
            // Separate encoders provide an execution boundary before the transformer reads the sorted IDs.
            try (MemoryStack stack = MemoryStack.stackPush(); var encoder = this.backend.beginComputePass()) {
                var parameters = stack.calloc(16);
                parameters.putInt(0, frame);
                encoder.setPipeline(this.transform);
                encoder.setBuffer(0, input, 0);
                encoder.setBuffer(1, nodes, 0);
                encoder.setBuffer(2, output, 1024);
                encoder.setBuffer(3, visibility, 0);
                encoder.setBytes(4, MemoryUtil.memAddress(parameters), 16);
                encoder.dispatch(1, 1, 1);
            }
        }
    }

    private static void requireGl(IGpuBuffer buffer) {
        if (!(buffer instanceof GlBuffer)) throw new IllegalArgumentException("GL cleaner requires GL buffers");
    }

    @Override public void close() {
        if (this.backend == null) {
            this.glSort.free(); this.glTransform.free(); this.glUpdate.free();
        } else {
            this.backend.finish();
            this.sort.close(); this.transform.close(); this.update.close(); this.merge.close();
            this.candidatesA.free(); this.candidatesB.free();
        }
    }
}
