package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.interop.*;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.*;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

/** Complete vanilla frame ordering; the render system supplies its real node/traversal work between opaque and temporal draws. */
public final class MetalFrameRenderer implements AutoCloseable {
    private final MetalRenderBackend backend;
    private final GlMetalFrameBridge bridge;
    private final MetalFrameDepth depthPass;
    private final GpuMDICDrawCalls commands;
    private final GpuMDICTerrain opaque, translucent;
    private final GpuSsao ssao;
    private IGpuTexture depth, color;
    private boolean closed;
    private int developmentCaptures;
    private final String captureSession = Long.toString(System.currentTimeMillis());
    private final java.util.Set<MDICViewport> prepared = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public MetalFrameRenderer(MetalRenderBackend backend, UploadStream uploads, RenderProperties properties,
                              Map<String, String> faceTints, SSAO.SSAOMode mode) {
        this.backend = backend;
        var owned = new ArrayList<AutoCloseable>();
        try {
            bridge = new GlMetalFrameBridge(backend, properties); owned.add(bridge);
            depthPass = new MetalFrameDepth(backend, properties); owned.add(depthPass);
            commands = new GpuMDICDrawCalls(backend, uploads, properties); owned.add(commands);
            opaque = new GpuMDICTerrain(backend, properties, faceTints, 0x8058, true); owned.add(opaque);
            translucent = new GpuMDICTerrain(backend, properties, faceTints, GpuTextureFormats.BGRA8, true); owned.add(translucent);
            ssao = new GpuSsao(backend, properties, mode); owned.add(ssao);
        } catch (RuntimeException | Error failure) {
            for (int i = owned.size() - 1; i >= 0; i--) {
                try { owned.get(i).close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    public void render(MDICViewport viewport, int vanillaDepth, int vanillaColor, int lightmap,
                       IGpuBuffer geometry, IGpuBuffer metadata, Consumer<RenderEncoder> models,
                       Runnable primaryWork, float[] fog, float[] fogColor, float[] fade) {
        render(viewport, vanillaDepth, vanillaColor, lightmap, geometry, metadata, models, primaryWork, fog, fogColor, fade, true);
    }

    public void render(MDICViewport viewport, int vanillaDepth, int vanillaColor, int lightmap,
                       IGpuBuffer geometry, IGpuBuffer metadata, Consumer<RenderEncoder> models,
                       Runnable primaryWork, float[] fog, float[] fogColor, float[] fade, boolean composite) {
        if (closed) throw new IllegalStateException("Metal frame renderer is closed");
        long benchmarkStart = Boolean.getBoolean("voxy.dev.lodBenchmark") ? System.nanoTime() : 0;
        resize(viewport, vanillaDepth, lightmap);
        bridge.copyInputs(vanillaDepth, lightmap);
        opaque.setVanillaDepth(bridge.inputDepth()); translucent.setVanillaDepth(bridge.inputDepth());
        depthPass.prepare(bridge.inputDepth(), depth, color);
        commands.updateScene(viewport);
        if (prepared.contains(viewport)) draw(opaque, GpuMDICTerrain.Layer.OPAQUE, viewport, geometry, models, color);
        viewport.hiZBuffer.buildMipChain(depth, viewport.width, viewport.height);
        // GPU uploads, node cleaning and hierarchical traversal are supplied by the owning render system.
        primaryWork.run();
        commands.build(viewport, metadata, depth);
        prepared.add(viewport);
        draw(opaque, GpuMDICTerrain.Layer.TEMPORAL, viewport, geometry, models, color);
        ssao.compute(viewport, bridge.colorTarget(), color, depth, bridge.inputDepth());
        draw(translucent, GpuMDICTerrain.Layer.TRANSLUCENT, viewport, geometry, models, bridge.colorTarget());
        depthPass.export(depth, bridge.depthTarget());
        if (composite) bridge.compositeToTextures(vanillaColor, vanillaDepth, viewport.MVP.invert(new Matrix4f()),
                new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView), fog, fogColor, fade);
        if (benchmarkStart != 0) me.cortex.voxy.tools.MetalLodBenchmark.frame(viewport, benchmarkStart, System.nanoTime() - benchmarkStart);
        else if (Boolean.getBoolean("voxy.dev.world") && viewport.frameId % 240 == 0 && developmentCaptures++ < 48) captureDevelopmentFrame(viewport);

    }

    private void captureDevelopmentFrame(MDICViewport viewport) {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
                || !client.gameDirectory.toPath().toAbsolutePath().normalize().getFileName().toString().equals("run-metal-263"))
            throw new IllegalStateException("Metal frame capture requires the isolated development client");
        byte[] pixels = backend.readPixelsRGBA8(bridge.colorTarget(), 0, 0, viewport.width, viewport.height);
        var image = new java.awt.image.BufferedImage(viewport.width, viewport.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int colored = 0;
        for (int y = 0; y < viewport.height; y++) for (int x = 0; x < viewport.width; x++) {
            int i = (y * viewport.width + x) * 4;
            int rgba = (pixels[i + 3] & 255) << 24 | (pixels[i] & 255) << 16 | (pixels[i + 1] & 255) << 8 | pixels[i + 2] & 255;
            if ((rgba >>> 24) != 0) colored++;
            image.setRGB(x, viewport.height - 1 - y, rgba);
        }
        long counts = ((me.cortex.voxy.client.core.metal.MetalBuffer)viewport.drawCountCallBuffer).getContentsPtr();
        me.cortex.voxy.common.Logger.info("Metal world frame=" + viewport.frameId + " opaque=" + org.lwjgl.system.MemoryUtil.memGetInt(counts + 12)
                + " translucent=" + org.lwjgl.system.MemoryUtil.memGetInt(counts + 16) + " temporal=" + org.lwjgl.system.MemoryUtil.memGetInt(counts + 20)
                + " LOD pixels=" + colored + " GPU buffers MiB=" + (backend.getBufferTotalSize() >> 20)
                + " GPU textures MiB=" + (backend.getTextureEstimatedTotalSize() >> 20));
        try {
            var directory = client.gameDirectory.toPath().resolve("screenshots/metal-native/" + captureSession);
            java.nio.file.Files.createDirectories(directory);
            javax.imageio.ImageIO.write(image, "png", directory.resolve("frame-" + viewport.frameId + ".png").toFile());
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private void draw(GpuMDICTerrain renderer, GpuMDICTerrain.Layer layer, MDICViewport viewport, IGpuBuffer geometry,
                      Consumer<RenderEncoder> models, IGpuTexture output) {
        renderer.render(layer, viewport, commands, geometry, models, bridge.lightmap(), RenderPassDesc.builder(viewport.width, viewport.height)
                .addColorAttachment(output, 0, RenderPassDesc.LoadAction.LOAD, RenderPassDesc.StoreAction.STORE, 0, 0, 0, 0)
                .depthAttachment(depth, 0, RenderPassDesc.LoadAction.LOAD, RenderPassDesc.StoreAction.STORE, 0).build());
    }

    private void resize(MDICViewport viewport, int vanillaDepth, int lightmap) {
        int[] source = GlMetalFrameBridge.textureSize(vanillaDepth), lighting = GlMetalFrameBridge.textureSize(lightmap);
        if (source[0] != viewport.width || source[1] != viewport.height) throw new IllegalArgumentException("Metal viewport must match the Minecraft target");
        if (depth != null && depth.getWidth() == viewport.width && depth.getHeight() == viewport.height) {
            bridge.resize(viewport.width, viewport.height, lighting[0], lighting[1]); return;
        }
        IGpuTexture replacementDepth = backend.createTexture(), replacementColor = backend.createTexture();
        try {
            replacementDepth.store(0x8cac, 1, viewport.width, viewport.height);
            replacementColor.store(0x8058, 1, viewport.width, viewport.height);
            bridge.resize(viewport.width, viewport.height, lighting[0], lighting[1]);
            backend.finish();
        } catch (RuntimeException | Error failure) { replacementColor.free(); replacementDepth.free(); throw failure; }
        if (depth != null) depth.free(); if (color != null) color.free();
        depth = replacementDepth; color = replacementColor;
    }

    @Override public void close() {
        if (closed) return;
        backend.finish(); ssao.close(); translucent.close(); opaque.close(); commands.close(); depthPass.close(); bridge.close();
        if (depth != null) depth.free(); if (color != null) color.free();
        prepared.clear(); closed = true;
    }
}
