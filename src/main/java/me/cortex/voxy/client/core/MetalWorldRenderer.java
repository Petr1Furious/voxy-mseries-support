package me.cortex.voxy.client.core;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.model.*;
import me.cortex.voxy.client.core.rendering.*;
import me.cortex.voxy.client.core.rendering.bounding.*;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.*;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.*;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.util.*;

/** Owns the world services and their Metal resources, including rollback during world creation. */
final class MetalWorldRenderer implements AutoCloseable {
    final RenderProperties properties;
    final WorldEngine world;
    final StreamedBoundStore visible;
    final ViewportSelector<MDICViewport> viewports;
    final RenderDistanceTracker distance;
    private MetalRenderBackend backend;
    private UploadStream uploads;
    private DownloadStream downloads;
    private ModelBakerySubsystem models;
    private RenderGenerationService generation;
    private AsyncNodeManager nodes;
    private NodeCleaner cleaner;
    private HierarchicalOcclusionTraverser traversal;
    private BasicSectionGeometryData geometry;
    private BoundRenderer bounds;
    private ColumnStreamedBoundStore columns;
    private MetalFrameRenderer frames;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private boolean closed;

    MetalWorldRenderer(WorldEngine world, ServiceManager services, RenderProperties properties) {
        this.world = world; this.properties = properties;
        try {
            backend = new MetalRenderBackend(); resources.add(backend::shutdown);
            uploads = new UploadStream(backend, 64L << 20); resources.add(uploads);
            downloads = new DownloadStream(backend, 4L << 20); resources.add(downloads);
            int mip = ((TextureAtlas)Minecraft.getInstance().getTextureManager().getTexture(
                    Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"))).maxMipLevel;
            var store = new ModelStore(backend, uploads, mip);
            try { models = new ModelBakerySubsystem(world.getMapper(), store); }
            catch (RuntimeException | Error failure) { store.free(); throw failure; }
            generation = new RenderGenerationService(world, models, services, false);
            int sections = 1 << 17;
            geometry = new BasicSectionGeometryData(backend, sections, 256L << 20); resources.add(geometry::free);
            nodes = new AsyncNodeManager(1 << 19, geometry, generation, backend, uploads);
            cleaner = new NodeCleaner(nodes, backend, uploads, downloads); resources.add(cleaner::free);
            traversal = new HierarchicalOcclusionTraverser(nodes, cleaner, generation, backend, uploads, downloads);
            resources.add(traversal::free); traversal.lateStageCompile(properties);
            visible = new StreamedBoundStore(backend, uploads); resources.add(visible::free);
            bounds = new BoundRenderer(properties, backend, uploads); resources.add(bounds::free);
            var light = Minecraft.getInstance().level.cardinalLighting();
            frames = new MetalFrameRenderer(backend, uploads, properties, Map.of(
                    "NO_SHADE_FACE_TINT", Float.toString(light.up()), "UP_FACE_TINT", Float.toString(light.up()),
                    "DOWN_FACE_TINT", Float.toString(light.down()), "Z_AXIS_FACE_TINT", Float.toString(light.north()),
                    "X_AXIS_FACE_TINT", Float.toString(light.east())), VoxyConfig.CONFIG.getSSAOMode());
            resources.add(frames);
            viewports = new ViewportSelector<>(() -> new MDICViewport(properties, sections, backend));
            resources.add(viewports::free);
            int min = Minecraft.getInstance().level.getMinSectionY() >> 5;
            int max = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> 5;
            if (VoxyCommon.IS_MINE_IN_ABYSS) { min = -8; max = 7; }
            distance = new RenderDistanceTracker(40, min, max, nodes::addTopLevel, nodes::removeTopLevel);
            distance.setRenderDistance((int)Math.ceil(VoxyConfig.CONFIG.sectionRenderDistance + 1));
            Arrays.stream(world.getMapper().getBiomeEntries()).forEach(models::addBiome);
            world.getMapper().setBiomeCallback(models::addBiome);
            world.setDirtyCallback(nodes::worldEvent);
            nodes.start();
            Logger.info("Voxy Metal world renderer created: 256 MiB geometry, 128 MiB CPU geometry cache");
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void render(Viewport<?> viewport, int depth, int color) {
        if (closed) throw new IllegalStateException("Metal world renderer is closed");
        if (!VoxyClient.disableSodiumChunkRender()) {
            if (VoxyClient.isFrexActive() && columns == null) columns = new ColumnStreamedBoundStore(backend, uploads);
            if (!VoxyClient.isFrexActive() && columns != null) { columns.free(); columns = null; }
            bounds.render(viewport, columns == null ? visible : columns);
        } else viewport.depthBoundingBuffer.clear(properties.inverseClearDepth());
        var mode = VoxyConfig.CONFIG.getFogMode();
        float[] fog = new float[4], fogColor = new float[4], fade = new float[4];
        float vanillaDistance = VoxyRenderSystem.getVanillaRenderDistance();
        if (mode.hasFog) {
            float start = viewport.fogParameters.environmentalStart(), end = viewport.fogParameters.environmentalEnd();
            if (Math.abs(end - start) > 1) {
                float inverse = 1 / (end - start), delta = -start * inverse;
                fog = new float[]{inverse, delta, Math.clamp(Math.max(vanillaDistance, 320) * (float)Math.sqrt(3) * inverse + delta, 0, 1), 0};
                fogColor = new float[]{viewport.fogParameters.red(), viewport.fogParameters.green(), viewport.fogParameters.blue(), viewport.fogParameters.alpha()};
            }
        }
        if (mode.hasFade) {
            float rd = VoxyConfig.CONFIG.sectionRenderDistance * 512 - 32;
            float start = Math.max(vanillaDistance, rd * .9f), end = Math.max(vanillaDistance, rd);
            if (end > start) fade = new float[]{1, -start / (end - start), 1 / (end - start), 0};
        }
        frames.render((MDICViewport)viewport, depth, color, LightMapHelper.getLightmapTextureId(),
                geometry.getGeometryBuffer(), geometry.getMetadataBuffer(), encoder -> models.getStore().bind(encoder, 3, 4, 0),
                () -> {
                    do {
                        downloads.tick(); nodes.tick(traversal.getNodeBuffer(), cleaner);
                        cleaner.tick(traversal.getNodeBuffer()); traversal.doTraversal(viewport);
                    } while (frexStillHasWork());
                }, fog, fogColor, fade, viewport.fogParameters.environmentalEnd() >= vanillaDistance);
        uploads.tick();
        if (Boolean.getBoolean("voxy.dev.world") && viewport.frameId % 240 == 0) {
            Logger.info("Metal geometry used MiB=" + (nodes.getUsedGeometryCapacity() >> 20)
                    + " cleanup passes=" + cleaner.getCleanupPasses() + " mesh tasks=" + generation.getTaskCount());
        }
        while (distance.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive());
        do { models.tick(900_000); } while (VoxyClient.isFrexActive() && !models.areQueuesEmpty());
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) return false;
        uploads.tick(); models.tick(100_000_000); backend.finish();
        return nodes.hasWork() || generation.getTaskCount() != 0 || !models.areQueuesEmpty();
    }

    void debug(List<String> out) {
        out.add("Metal Buf/Tex [#/MiB]: " + backend.getBufferCount() + "/" + (backend.getBufferTotalSize() >> 20)
                + ", " + backend.getTextureCount() + "/" + (backend.getTextureEstimatedTotalSize() >> 20));
        models.addDebugData(out); generation.addDebugData(out); nodes.addDebug(out);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        world.setDirtyCallback(null); world.getMapper().setBiomeCallback(null); world.getMapper().setStateCallback(null);
        var failures = new ArrayList<Throwable>();
        if (downloads != null) cleanup(downloads::flushWaitClear, failures);
        if (nodes != null) cleanup(nodes::stop, failures);
        if (generation != null) cleanup(generation::shutdown, failures);
        if (models != null) cleanup(models::shutdown, failures);
        if (columns != null) cleanup(columns::free, failures);
        for (int i = resources.size() - 1; i >= 0; i--) cleanup(resources.get(i), failures);
        resources.clear();
        if (backend != null) Logger.info("Metal world resources released: buffers=" + backend.getBufferCount()
                + ", textures=" + backend.getTextureCount());
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("Error releasing Metal world resources");
            failures.forEach(failure::addSuppressed); throw failure;
        }
    }

    private static void cleanup(AutoCloseable action, List<Throwable> failures) {
        try { action.close(); } catch (Exception failure) { failures.add(failure); }
    }
}
