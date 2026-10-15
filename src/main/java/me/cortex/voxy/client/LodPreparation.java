package me.cortex.voxy.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.*;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.popcraft.chunky.ChunkyFabric;
import org.popcraft.chunky.api.ChunkyAPI;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** A per-world opt-in request survives interrupted preparation and stops after successful import. */
public final class LodPreparation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Run active;
    private static Object checkedServer;
    private static String checkedDimension;
    private static ChunkyAPI registeredApi;
    public enum Phase { PENDING, GENERATING, IMPORTING, READY, FAILED }
    public static final class State {
        public int version = 1;
        public int radiusChunks;
        public Double centerX, centerZ;
        public Phase phase = Phase.PENDING;
        public int importedChunks;
    }
    private static final class Run {
        final Minecraft client;
        final Object server;
        final WorldEngine engine;
        final Path file, region;
        final String dimension;
        final State state;
        final ChunkyAPI api;
        final long failedSaves;
        final LerpingBossEvent bar = new LerpingBossEvent(UUID.randomUUID(), Component.literal("Voxy: подготовка LOD"), 0,
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS, false, false, false);
        volatile boolean generated;
        volatile boolean imported;
        volatile float progress;
        volatile boolean ownsGeneration;
        boolean importStarted, flushing;
        Run(Minecraft client, WorldEngine engine, Path file, Path region, String dimension, State state, ChunkyAPI api) {
            this.client = client; this.server = client.getSingleplayerServer(); this.engine = engine;
            this.file = file; this.region = region; this.dimension = dimension; this.state = state; this.api = api;
            this.failedSaves = VoxyCommon.getInstance().getFailedSaveCount();
        }
    }
    private LodPreparation() {}

    public static Path requestPath(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null || client.level == null) throw new IllegalStateException("LOD preparation needs a local world");
        return DimensionType.getStorageFolder(client.level.dimension(), server.getWorldPath(LevelResource.ROOT))
                .resolve("voxy-lod-preparation.json");
    }

    public static void arm(Minecraft client, int radiusChunks) {
        if (radiusChunks < 1 || radiusChunks > 128) throw new IllegalArgumentException("Radius must be 1–128 chunks");
        Path file = requestPath(client);
        if (Files.exists(file)) throw new IllegalStateException("Preparation request already exists: " + file);
        State state = new State(); state.radiusChunks = radiusChunks;
        save(file, state); checkedServer = null;
    }

    public static boolean ready(Minecraft client) {
        return Files.exists(requestPath(client)) && read(requestPath(client)).phase == Phase.READY;
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null || client.getSingleplayerServer() == null
                || IVoxyRenderSystemHolder.getNullable() == null) return;
        String dimension = client.level.dimension().identifier().toString();
        if (active == null) {
            if (checkedServer == client.getSingleplayerServer() && dimension.equals(checkedDimension)) return;
            checkedServer = client.getSingleplayerServer(); checkedDimension = dimension;
            Path file = requestPath(client);
            if (!Files.exists(file)) return;
            try {
                State state = read(file);
                if (state.phase == Phase.READY || state.phase == Phase.FAILED) return;
                if (!FabricLoader.getInstance().isModLoaded("chunky")) throw new IllegalStateException("Chunky is required for LOD preparation");
                start(client, file, dimension, state);
            } catch (RuntimeException failure) {
                if (active != null) fail(active, failure);
                else { Logger.error("Cannot start LOD preparation", failure); message(client, "Не удалось запустить подготовку LOD: " + failure.getMessage()); }
            }
        }
        Run run = active;
        if (run == null) return;
        if (run.server != client.getSingleplayerServer() || !run.dimension.equals(dimension)
                || run.engine != WorldIdentifier.ofEngine(client.level)) { stop(); return; }
        try {
            if (run.state.phase == Phase.GENERATING) {
                run.bar.setProgress(run.progress / 100f);
                run.bar.setName(Component.literal("Voxy: генерация LOD " + Math.round(run.progress) + "% — " + run.state.radiusChunks + " чанка"));
                if (run.generated) { run.state.phase = Phase.IMPORTING; save(run.file, run.state); }
            }
            if (run.state.phase == Phase.IMPORTING && !run.importStarted && !run.flushing) {
                run.flushing = true;
                var server = client.getSingleplayerServer();
                server.execute(() -> {
                    try {
                        server.saveEverything(true, true, true);
                        client.execute(() -> {
                            if (active != run) return;
                            run.flushing = false; run.importStarted = true;
                            beginImport(run);
                        });
                    } catch (RuntimeException failure) { client.execute(() -> fail(run, failure)); }
                });
            }
            if (run.imported && VoxyCommon.getInstance().getIngestService().getTaskCount() == 0
                    && VoxyCommon.getInstance().getPendingSaveCount() == 0) {
                if (VoxyCommon.getInstance().getFailedSaveCount() != run.failedSaves) throw new IllegalStateException("Voxy storage reported a save failure");
                run.engine.storage.flush();
                run.state.phase = Phase.READY; save(run.file, run.state);
                message(client, "Voxy: LOD подготовлен на " + run.state.radiusChunks + " чанка. Импортировано " + run.state.importedChunks + " чанков.");
                Logger.info("LOD preparation READY radius=" + run.state.radiusChunks + " imported=" + run.state.importedChunks + " file=" + run.file);
                removeBar(run); active = null;
            }
        } catch (RuntimeException failure) { fail(run, failure); }
    }

    private static void start(Minecraft client, Path file, String dimension, State state) {
        var fabric = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
                .filter(ChunkyFabric.class::isInstance).map(ChunkyFabric.class::cast).findFirst().orElseThrow();
        var api = fabric.getChunky().getApi();
        if (api.isRunning(dimension)) throw new IllegalStateException("A Chunky task is already running in this dimension");
        if (state.centerX == null) { state.centerX = client.player.getX(); state.centerZ = client.player.getZ(); }
        var run = new Run(client, WorldIdentifier.ofEngine(client.level), file, file.getParent().resolve("region"), dimension, state, api);
        if (api != registeredApi) {
            registeredApi = api;
            api.onGenerationProgress(event -> { Run current = active; if (current != null && current.api == api && current.ownsGeneration && event.world().equals(current.dimension)) current.progress = event.progress(); });
            api.onGenerationComplete(event -> { Run current = active; if (current != null && current.api == api && current.ownsGeneration && event.world().equals(current.dimension)) current.generated = true; });
        }
        active = run; client.gui.hud.getBossOverlay().events.put(run.bar.getId(), run.bar);
        if (state.phase != Phase.IMPORTING) {
            boolean resume = state.phase == Phase.GENERATING;
            state.phase = Phase.GENERATING; save(file, state);
            run.ownsGeneration = true;
            if (!(resume && api.continueTask(dimension)) && !api.startTask(dimension, "circle", state.centerX, state.centerZ,
                    state.radiusChunks * 16.0, state.radiusChunks * 16.0, "concentric")) throw new IllegalStateException("Chunky refused the preparation task");
            message(client, "Voxy: разовая подготовка LOD на " + state.radiusChunks + " чанка. Оставьте мир открытым без паузы.");
            Logger.info("LOD preparation GENERATING radius=" + state.radiusChunks + " center=" + state.centerX + "," + state.centerZ);
        }
    }

    private static void beginImport(Run run) {
        if (!Files.isDirectory(run.region)) { fail(run, new IllegalStateException("Region directory is missing")); return; }
        var instance = VoxyCommon.getInstance();
        boolean started = instance.getImportManager().makeAndRunIfNone(run.engine, () -> {
            var importer = new WorldImporter(run.engine, run.client.level, instance.getServiceManager(), instance.savingServiceRateLimiter) {
                @Override public void runImport(IUpdateCallback updates, ICompletionCallback completion) {
                    super.runImport((finished, total) -> {
                        updates.onUpdate(finished, total);
                        run.client.execute(() -> { if (active == run) { run.bar.setName(Component.literal("Voxy: импорт LOD " + finished + "/" + total)); run.bar.setProgress(finished / (float)Math.max(1, total)); } });
                    }, count -> {
                        completion.onCompletion(count);
                        run.client.execute(() -> {
                            if (active != run) return;
                            if (!wasSuccessful()) { fail(run, new IllegalStateException("LOD import did not complete successfully")); return; }
                            run.state.importedChunks = count; run.imported = true;
                        });
                    });
                }
            };
            importer.importRegionDirectoryAsync(run.region.toFile()); return importer;
        });
        if (!started) { run.importStarted = false; message(run.client, "Voxy: ожидание другого импорта"); }
    }

    public static State read(Path file) {
        try {
            State state = JSON.fromJson(Files.readString(file), State.class);
            if (state == null || state.version != 1 || state.phase == null || state.radiusChunks < 1 || state.radiusChunks > 128
                    || (state.centerX == null) != (state.centerZ == null)
                    || state.centerX != null && (!Double.isFinite(state.centerX) || !Double.isFinite(state.centerZ)))
                throw new IllegalArgumentException("Invalid LOD preparation request");
            return state;
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private static void save(Path file, State state) {
        try {
            Path temporary = Files.createTempFile(file.getParent(), "voxy-preparation-", ".tmp");
            Files.writeString(temporary, JSON.toJson(state) + "\n");
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private static void fail(Run run, RuntimeException failure) {
        if (active != run) return;
        run.state.phase = Phase.FAILED;
        try { save(run.file, run.state); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        Logger.error("LOD preparation failed", failure); message(run.client, "Voxy: подготовка LOD остановлена: " + failure.getMessage()); stop();
    }
    public static void stop() {
        Run run = active; active = null; checkedServer = null; checkedDimension = null;
        if (run != null) {
            if (run.ownsGeneration && !run.generated && run.api.isRunning(run.dimension)) run.api.pauseTask(run.dimension);
            removeBar(run);
        }
    }
    private static void removeBar(Run run) { run.client.gui.hud.getBossOverlay().events.remove(run.bar.getId()); }
    private static void message(Minecraft client, String text) { client.gui.hud.getChat().addClientSystemMessage(Component.literal(text)); }
}
