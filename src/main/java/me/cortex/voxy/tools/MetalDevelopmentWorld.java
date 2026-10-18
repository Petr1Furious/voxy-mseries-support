package me.cortex.voxy.tools;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Explicit opt-in real-world acceptance run; never accesses a launcher instance. */
public final class MetalDevelopmentWorld {
    private static boolean started;
    private static int ticks, worldTicks, reloadTicks;
    private static String worldName;
    private static boolean reloadPending;
    private static boolean preparationReopened;
    private static int readyTicks;
    private static boolean preparationInterrupted;
    private MetalDevelopmentWorld() {}

    public static void tick(Minecraft client) {
        if (!Boolean.getBoolean("voxy.dev.world")) return;
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()
                || !client.gameDirectory.toPath().toAbsolutePath().normalize().getFileName().toString().equals("run-metal-263")) {
            throw new IllegalStateException("The Metal development world requires Loom's isolated run-metal-263 directory");
        }
        if (!started) {
            if (++ticks < 60 || client.gui.overlay() != null) return;
            started = true;
            client.options.pauseOnLostFocus = false;
            client.options.renderDistance().set(3);
            VoxyConfig.CONFIG.sectionRenderDistance = 2;
            VoxyConfig.CONFIG.serviceThreads = 2;
            if (Boolean.getBoolean("voxy.dev.lodBenchmark") || System.getProperty("voxy.dev.lodChunks") != null) {
                client.options.renderDistance().set(2);
                VoxyConfig.CONFIG.setFogMode(me.cortex.voxy.client.core.NormalRenderPipeline.FogMode.FADE);
                VoxyConfig.CONFIG.setSSAOMode(me.cortex.voxy.client.core.SSAO.SSAOMode.BASIC);
                int chunks = Integer.getInteger("voxy.dev.lodChunks", 64);
                if (chunks < 20 || chunks > 128) throw new IllegalArgumentException("Development LOD radius outside 20..128 chunks");
                VoxyConfig.CONFIG.sectionRenderDistance = chunks / 32f;
            }
            String existing = System.getProperty("voxy.dev.existingWorld");
            if (existing != null) {
                if (existing.isBlank() || !java.nio.file.Path.of(existing).getFileName().toString().equals(existing)
                        || existing.equals(".") || existing.equals("..")) throw new IllegalArgumentException("Expected a development save folder name");
                worldName = existing;
                Logger.info("Opening isolated copied Metal acceptance world " + worldName);
                client.createWorldOpenFlows().openWorld(worldName, () -> {});
                return;
            }
            String name = worldName = System.getProperty("voxy.dev.newWorldName", "Metal-acceptance-" + System.currentTimeMillis());
            Logger.info("Creating new Metal acceptance world " + name);
            client.createWorldOpenFlows().createFreshLevel(name,
                    new LevelSettings(name, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(263L, false, false), WorldPresets::createNormalWorldDimensions, client.gui.screen());
            return;
        }
        if (reloadPending && client.level == null) {
            if (++reloadTicks == 60) {
                reloadPending = false; reloadTicks = 0;
                Logger.info("Reopening the same Metal acceptance world " + worldName);
                client.createWorldOpenFlows().openWorld(worldName, () -> {});
            }
            return;
        }
        if (client.level == null || client.player == null || client.getConnection() == null || client.gui.overlay() != null) return;
        worldTicks++;
        if (System.getProperty("voxy.dev.existingWorld") != null) {
            if (MetalPerfBenchmark.ENABLED) {
                MetalPerfBenchmark.tick(client, worldTicks);
                return;
            }
            if (Boolean.getBoolean("voxy.dev.lodBenchmark")) {
                MetalLodBenchmark.tick(client, worldTicks);
                return;
            }
            if (worldTicks == 20) {
                client.getConnection().sendCommand("gamemode spectator");
                client.getConnection().sendCommand("tp @s -379 150 182 0 20");
                client.getConnection().sendCommand("time set noon");
                client.getConnection().sendCommand("gamerule advance_time false");
            }
            if (Boolean.getBoolean("voxy.dev.cameraSweep")) {
                if (worldTicks == 240) client.getConnection().sendCommand("tp @s -443 150 182 90 20");
                if (worldTicks == 480) client.getConnection().sendCommand("tp @s -443 150 246 180 20");
                if (worldTicks == 720) client.getConnection().sendCommand("tp @s -379 150 182 0 20");
            }
            if (worldTicks % 120 == 0) Screenshot.grab(client, false);
            if (worldTicks == 1000) { Logger.info("Copied-world Metal stability capture complete"); client.stop(); }
            return;
        }
        if (Integer.getInteger("voxy.dev.perfPrepare") != null) {
            // Generates the shared benchmark world: Chunky terrain around the benchmark cameras, imported into Voxy.
            if (worldTicks == 20) { client.getConnection().sendCommand("gamemode spectator"); client.getConnection().sendCommand("tp @s 0 120 0"); }
            if (worldTicks == 60) me.cortex.voxy.client.LodPreparation.arm(client, Integer.getInteger("voxy.dev.perfPrepare"));
            if (worldTicks > 60 && me.cortex.voxy.client.LodPreparation.ready(client) && ++readyTicks == 100) {
                Logger.info("Perf benchmark world prepared: " + worldName); client.stop();
            }
            return;
        }
        if (Boolean.getBoolean("voxy.dev.preparation")) {
            if (worldTicks == 20) { client.getConnection().sendCommand("gamemode spectator"); command(client, 0); }
            if (worldTicks == 60) me.cortex.voxy.client.LodPreparation.arm(client, 8);
            if (worldTicks == 85 && !me.cortex.voxy.client.LodPreparation.ready(client)) {
                preparationInterrupted = true; reloadPending = true; client.disconnectWithSavingScreen();
                Logger.info("Preparation test interrupting unfinished work for restart"); return;
            }
            if (worldTicks > 60 && me.cortex.voxy.client.LodPreparation.ready(client)) {
                if (++readyTicks == 80) {
                    if (!preparationReopened) {
                        Screenshot.grab(client, false); preparationReopened = true; reloadPending = true;
                        client.disconnectWithSavingScreen(); Logger.info("Preparation test reopening READY world"); readyTicks = 0;
                    } else { Logger.info("Preparation test PASS: READY persisted and no repeated generation; interrupted=" + preparationInterrupted); client.stop(); }
                }
            }
            if (worldTicks > 1800) throw new IllegalStateException("Preparation test timed out");
            return;
        }
        switch (worldTicks) {
            case 20 -> { client.getConnection().sendCommand("gamemode spectator"); command(client, 0); }
            case 320 -> command(client, 128);
            case 620 -> command(client, 256);
            case 920 -> command(client, 384);
            case 1220 -> command(client, 0);
            case 1400 -> { Screenshot.grab(client, false); VoxyConfig.CONFIG.enableRendering = false; client.levelExtractor.allChanged(); Logger.info("Metal acceptance LOD disabled for comparison"); }
            case 1420 -> { Screenshot.grab(client, false); VoxyConfig.CONFIG.enableRendering = true; client.levelExtractor.allChanged(); Logger.info("Metal acceptance LOD enabled"); }
            case 1520 -> { Screenshot.grab(client, false); client.getWindow().setWindowed(960, 600); Logger.info("Metal acceptance resize"); }
            case 1720 -> { Screenshot.grab(client, false); Logger.info("Metal acceptance capture complete"); }
            case 1800 -> { reloadPending = true; client.disconnectWithSavingScreen(); Logger.info("Metal acceptance world disconnected for reload"); }
            case 2100 -> { Screenshot.grab(client, false); Logger.info("Metal acceptance reload capture complete"); }
            case 2140 -> client.stop();
        }
    }

    private static void command(Minecraft client, int z) {
        client.getConnection().sendCommand("tp @s 0 150 " + z + " 0 30");
        Logger.info("Metal acceptance camera at z=" + z);
    }
}
