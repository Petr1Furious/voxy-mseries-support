package me.cortex.voxy.tools;

import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.system.MemoryUtil;
import me.cortex.voxy.client.core.metal.MetalBuffer;

import java.nio.file.Files;
import java.util.*;

/** Isolated comparisons with warmup, mirrored phase order, and real frame intervals. */
public final class MetalLodBenchmark {
    private record Setting(int radius, int subdivision, boolean reducedWindow) {}
    private static final String PROFILE = System.getProperty("voxy.dev.benchmarkProfile", "distance");
    private static final Setting[] SETTINGS = settings();
    private static final List<Map<String, Object>> results = new ArrayList<>();
    private static final List<Double> intervals = new ArrayList<>(), metalTimes = new ArrayList<>();
    private static final List<Integer> opaqueCounts = new ArrayList<>(), translucentCounts = new ArrayList<>();
    private static long phaseStart, lastFrame;
    private static int phase = -1, width, height;
    private static boolean collecting;
    private static double[] camera;
    private static org.joml.Matrix4f modelView;
    private static int initialWindowWidth, initialWindowHeight;

    private MetalLodBenchmark() {}

    public static void tick(Minecraft client, int ticks) {
        if (ticks < 20) return;
        // Automated cameras produce no mouse input. Keep the game's AFK limiter
        // from silently capping later phases at 30 FPS.
        client.getFramerateLimitTracker().onInputReceived();
        // The automated scene must not capture normal desktop mouse movement.
        client.mouseHandler.releaseMouse();
        if (phase == -1) {
            initialWindowWidth = client.getWindow().getScreenWidth();
            initialWindowHeight = client.getWindow().getScreenHeight();
            Logger.info("Benchmark initial framebuffer=" + client.getWindow().getWidth() + "x" + client.getWindow().getHeight()
                    + " window=" + initialWindowWidth + "x" + initialWindowHeight
                    + " pixel density=" + client.getWindow().getPixelDensity());
            client.getConnection().sendCommand("gamemode spectator");
            client.getConnection().sendCommand("tp @s -379 150 182 0 20");
            client.getConnection().sendCommand("time set noon");
            client.getConnection().sendCommand("gamerule advance_time false");
            phase = 0;
            startPhase();
        }
        if (phase >= SETTINGS.length) return;
        double seconds = (System.nanoTime() - phaseStart) / 1e9;
        collecting = seconds >= 8 && seconds < 16;
        if (seconds < 16) return;
        if (intervals.size() < 30) throw new IllegalStateException("Insufficient benchmark frames");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", PROFILE);
        result.put("radiusChunks", SETTINGS[phase].radius);
        result.put("vanillaChunks", client.options.renderDistance().get());
        result.put("subdivision", VoxyConfig.CONFIG.subDivisionSize);
        result.put("fogMode", VoxyConfig.CONFIG.getFogMode().name());
        result.put("ssaoMode", VoxyConfig.CONFIG.getSSAOMode().name());
        result.put("camera", camera);
        result.put("width", width); result.put("height", height);
        result.put("samples", intervals.size());
        result.put("meanFrameMs", intervals.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        result.put("medianFrameMs", percentile(intervals, 0.5));
        result.put("p95FrameMs", percentile(intervals, 0.95));
        result.put("medianMetalMs", percentile(metalTimes, 0.5));
        result.put("opaqueCommandsMean", opaqueCounts.stream().mapToInt(Integer::intValue).average().orElseThrow());
        result.put("translucentCommandsMean", translucentCounts.stream().mapToInt(Integer::intValue).average().orElseThrow());
        results.add(result); Logger.info("Metal LOD benchmark phase=" + phase + " " + result);
        collecting = false;
        Screenshot.grab(client, false);
        if (++phase < SETTINGS.length) { startPhase(); return; }
        try {
            var directory = client.gameDirectory.toPath().resolve("lod-benchmark");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("report-" + PROFILE + "-" + System.currentTimeMillis() + ".json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(results) + "\n");
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        Logger.info("Metal LOD benchmark completed: " + PROFILE);
        client.stop();
    }

    private static void startPhase() {
        Setting setting = SETTINGS[phase];
        VoxyConfig.CONFIG.sectionRenderDistance = setting.radius / 32f;
        VoxyConfig.CONFIG.subDivisionSize = setting.subdivision;
        if (PROFILE.equals("resolution") && phase > 0 && setting.reducedWindow != SETTINGS[phase - 1].reducedWindow) {
            // Resize the existing SDL window without toggling its fullscreen/high-DPI mode.
            int targetWidth = setting.reducedWindow ? Math.round(initialWindowWidth * (1280f / 1800)) : initialWindowWidth;
            int targetHeight = setting.reducedWindow ? Math.round(initialWindowHeight * (1280f / 1800)) : initialWindowHeight;
            if (!org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(Minecraft.getInstance().getWindow().handle(), targetWidth, targetHeight))
                throw new IllegalStateException("Cannot resize benchmark window: " + org.lwjgl.sdl.SDLError.SDL_GetError());
        }
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null) throw new IllegalStateException("Benchmark requires a real renderer");
        renderer.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
        intervals.clear(); metalTimes.clear(); opaqueCounts.clear(); translucentCounts.clear();
        lastFrame = 0; width = 0; height = 0; collecting = false; phaseStart = System.nanoTime();
        Logger.info("Metal LOD benchmark warming " + setting);
    }

    public static void frame(MDICViewport viewport, long start, long elapsed) {
        if (phase < 0 || phase >= SETTINGS.length) return;
        if (collecting && lastFrame != 0) {
            if (Minecraft.getInstance().getFramerateLimitTracker().getThrottleReason()
                    != com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason.NONE)
                throw new IllegalStateException("Minecraft throttled the benchmark frame rate");
            if (width != 0 && (width != viewport.width || height != viewport.height))
                throw new IllegalStateException("Viewport resized during benchmark");
            if (camera == null) {
                camera = new double[]{viewport.cameraX, viewport.cameraY, viewport.cameraZ};
                modelView = new org.joml.Matrix4f(viewport.modelView);
            }
            if (Math.abs(viewport.cameraX - camera[0]) > 0.05 || Math.abs(viewport.cameraY - camera[1]) > 0.05
                    || Math.abs(viewport.cameraZ - camera[2]) > 0.05 || !viewport.modelView.equals(modelView, 0.0001f))
                throw new IllegalStateException("Camera changed: expected=" + Arrays.toString(camera)
                        + " actual=" + viewport.cameraX + "," + viewport.cameraY + "," + viewport.cameraZ
                        + " viewRotationChanged=" + !viewport.modelView.equals(modelView, 0.0001f));
            width = viewport.width; height = viewport.height;
            intervals.add((start - lastFrame) / 1e6); metalTimes.add(elapsed / 1e6);
            long counts = ((MetalBuffer) viewport.drawCountCallBuffer).getContentsPtr();
            opaqueCounts.add(MemoryUtil.memGetInt(counts + 12));
            translucentCounts.add(MemoryUtil.memGetInt(counts + 16));
        }
        lastFrame = start;
    }

    private static double percentile(List<Double> values, double fraction) {
        var sorted = new ArrayList<>(values); Collections.sort(sorted);
        return sorted.get((int) Math.floor((sorted.size() - 1) * fraction));
    }

    private static Setting[] settings() {
        return switch (PROFILE) {
            case "distance" -> new Setting[]{new Setting(64, 64, false), new Setting(48, 64, false),
                    new Setting(48, 64, false), new Setting(64, 64, false)};
            case "detail" -> java.util.stream.IntStream.of(64, 80, 96, 96, 80, 64)
                    .mapToObj(detail -> new Setting(48, detail, false)).toArray(Setting[]::new);
            case "resolution" -> new Setting[]{new Setting(48, 64, false), new Setting(48, 64, true),
                    new Setting(48, 64, true), new Setting(48, 64, false)};
            default -> throw new IllegalArgumentException("Unknown Metal benchmark profile: " + PROFILE);
        };
    }
}
