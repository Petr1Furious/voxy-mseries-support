package me.cortex.voxy.tools;

import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.FrameTiming;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.util.*;

/**
 * Voxy on/off frame-rate comparison at fixed cameras, for the isolated development client only.
 * Frame durations come from Minecraft's own per-frame measurement, so Voxy-disabled phases are
 * measured identically. Phases run ON, OFF, ON so drift and thermal effects show up as a mismatch
 * between the two ON results.
 */
public final class MetalPerfBenchmark {
    public static final boolean ENABLED = Boolean.getBoolean("voxy.dev.perf");
    private record Camera(String name, String teleport) {}
    private record Phase(Camera camera, boolean voxy, int warmupSeconds) {}
    private static final Camera[] CAMERAS = {
            new Camera("horizon", "tp @s 0 120 0 45 8"),
            new Camera("overview", "tp @s 0 220 0 -135 30"),
    };
    private static final int MEASURE_SECONDS = Integer.getInteger("voxy.dev.perfSeconds", 12);
    private static final List<Phase> PHASES = phases();
    private static final long[] frames = new long[1 << 20];
    private static final List<Map<String, Object>> results = new ArrayList<>();
    private static int frameCount, phase = -1;
    private static long phaseStart, collectStart, collectEnd;
    private static boolean collecting;
    private static long voxyNs, voxyFrames, submitNs, submits, glFinishNs, glFinishes, drawNs, draws, liveDraws, sections;
    private static long[] overflows = new long[3];

    private MetalPerfBenchmark() {}

    private static List<Phase> phases() {
        var list = new ArrayList<Phase>();
        for (boolean voxy : new boolean[]{true, false, true}) {
            for (int i = 0; i < CAMERAS.length; i++) {
                list.add(new Phase(CAMERAS[i], voxy, i == 0 ? (voxy ? 30 : 10) : 8));
            }
        }
        return list;
    }

    /** Emulates a heavier client (entities, a busy server) by spinning this long on the render thread every frame. */
    private static final long CPU_LOAD_NS = (long) (Double.parseDouble(System.getProperty("voxy.dev.perfCpuLoadMs", "0")) * 1e6);

    /** Turns the camera this many degrees every frame, so newly visible sections stream in continuously. */
    private static final float SPIN_DEGREES = Float.parseFloat(System.getProperty("voxy.dev.perfSpinDeg", "0"));

    /** Every rendered frame, from Minecraft's frame-time logger. */
    public static void frame(long nanos) {
        if (CPU_LOAD_NS > 0) { long end = System.nanoTime() + CPU_LOAD_NS; while (System.nanoTime() < end) Thread.onSpinWait(); }
        var player = Minecraft.getInstance().player;
        if (SPIN_DEGREES != 0 && phase >= 0 && player != null) {
            float yaw = player.getYRot() + SPIN_DEGREES;
            player.setYRot(yaw); player.yRotO = yaw;
        }
        if (collecting && frameCount < frames.length) frames[frameCount++] = nanos;
    }

    public static void tick(Minecraft client, int ticks) {
        if (ticks < 20) return;
        // No mouse input during automated runs: keep the AFK limiter from capping frame rate.
        client.getFramerateLimitTracker().onInputReceived();
        client.mouseHandler.releaseMouse();
        if (phase == -1) {
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
            client.options.renderDistance().set(Integer.getInteger("voxy.dev.perfVanillaChunks", 12));
            VoxyConfig.CONFIG.sectionRenderDistance = 16;
            VoxyConfig.CONFIG.subDivisionSize = Float.parseFloat(System.getProperty("voxy.dev.perfSubdiv", "64"));
            int width = Integer.getInteger("voxy.dev.perfWidth", 1280), height = Integer.getInteger("voxy.dev.perfHeight", 800);
            if (!org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(client.getWindow().handle(), width, height))
                throw new IllegalStateException("Cannot resize benchmark window: " + org.lwjgl.sdl.SDLError.SDL_GetError());
            client.getConnection().sendCommand("gamemode spectator");
            client.getConnection().sendCommand("time set noon");
            client.getConnection().sendCommand("gamerule advance_time false");
            client.getConnection().sendCommand("gamerule advance_weather false");
            phase = 0;
            startPhase(client);
            return;
        }
        if (phase >= PHASES.size()) return;
        double seconds = (System.nanoTime() - phaseStart) / 1e9;
        int warmup = PHASES.get(phase).warmupSeconds;
        if (!collecting && seconds >= warmup && seconds < warmup + MEASURE_SECONDS) {
            collecting = true; frameCount = 0; collectStart = System.nanoTime();
            voxyNs = FrameTiming.voxyFrameNs; voxyFrames = FrameTiming.voxyFrames;
            submitNs = FrameTiming.submitWaitNs; submits = FrameTiming.submitCount;
            glFinishNs = FrameTiming.glFinishNs; glFinishes = FrameTiming.glFinishCount;
            drawNs = FrameTiming.jniDrawLoopNs; draws = FrameTiming.jniDrawCount; liveDraws = FrameTiming.liveDraws; sections = FrameTiming.sections; overflows = FrameTiming.drawOverflows.clone();
            FrameTiming.gpuNsBySubmitter.clear(); FrameTiming.waits.clear();
        }
        if (seconds < warmup + MEASURE_SECONDS) return;
        collecting = false; collectEnd = System.nanoTime();
        net.minecraft.client.Screenshot.grab(client, false);
        if (client.getFramerateLimitTracker().getThrottleReason()
                != com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason.NONE)
            throw new IllegalStateException("Minecraft throttled the benchmark frame rate");
        results.add(summarize(client, PHASES.get(phase)));
        if (++phase < PHASES.size()) { startPhase(client); return; }
        try {
            var directory = client.gameDirectory.toPath().resolve("perf-benchmark");
            Files.createDirectories(directory);
            var file = directory.resolve("report-" + System.getProperty("voxy.dev.perfLabel", "run") + "-" + System.currentTimeMillis() + ".json");
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(results) + "\n");
            Logger.info("Metal perf benchmark report " + file);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        Logger.info("Metal perf benchmark completed");
        client.stop();
    }

    private static void startPhase(Minecraft client) {
        var next = PHASES.get(phase);
        if (VoxyConfig.CONFIG.enableRendering != next.voxy) {
            VoxyConfig.CONFIG.enableRendering = next.voxy;
            client.levelExtractor.allChanged();
        }
        client.getConnection().sendCommand(next.camera.teleport);
        collecting = false; frameCount = 0; phaseStart = System.nanoTime();
        Logger.info("Metal perf benchmark warming " + next);
    }

    private static Map<String, Object> summarize(Minecraft client, Phase phase) {
        if (frameCount < 30) throw new IllegalStateException("Insufficient benchmark frames: " + frameCount);
        long[] sorted = Arrays.copyOf(frames, frameCount);
        Arrays.sort(sorted);
        long total = 0; for (long frame : sorted) total += frame;
        long voxyCount = FrameTiming.voxyFrames - voxyFrames;
        var result = new LinkedHashMap<String, Object>();
        result.put("camera", phase.camera.name);
        result.put("voxy", phase.voxy);
        result.put("framebuffer", client.getWindow().getWidth() + "x" + client.getWindow().getHeight());
        result.put("vanillaChunks", client.options.renderDistance().get());
        result.put("cpuLoadMs", CPU_LOAD_NS / 1e6);
        result.put("spinDegreesPerFrame", SPIN_DEGREES);
        result.put("frames", frameCount);
        // Wall-clock rate: Minecraft's per-frame duration can exclude GPU time that is not waited on.
        result.put("fps", Math.round(frameCount / ((collectEnd - collectStart) / 1e9) * 10) / 10.0);
        result.put("cpuFrameFps", Math.round(frameCount / (total / 1e9) * 10) / 10.0);
        result.put("medianMs", ms(sorted[frameCount / 2]));
        result.put("p95Ms", ms(sorted[(int) ((frameCount - 1) * 0.95)]));
        result.put("p99Ms", ms(sorted[(int) ((frameCount - 1) * 0.99)]));
        if (voxyCount > 0) {
            result.put("voxyCpuMsPerFrame", ms((FrameTiming.voxyFrameNs - voxyNs) / voxyCount));
            result.put("submitWaitsPerFrame", Math.round((FrameTiming.submitCount - submits) * 10.0 / voxyCount) / 10.0);
            result.put("submitWaitMsPerFrame", ms((FrameTiming.submitWaitNs - submitNs) / voxyCount));
            result.put("glFinishPerFrame", Math.round((FrameTiming.glFinishCount - glFinishes) * 10.0 / voxyCount) / 10.0);
            result.put("glFinishMsPerFrame", ms((FrameTiming.glFinishNs - glFinishNs) / voxyCount));
            result.put("drawsPerFrame", (FrameTiming.jniDrawCount - draws) / voxyCount);
            result.put("liveDrawsPerFrame", (FrameTiming.liveDraws - liveDraws) / voxyCount);
            result.put("sectionsPerFrame", (FrameTiming.sections - sections) / voxyCount);
            result.put("drawOverflows", Map.of("opaque", FrameTiming.drawOverflows[0] - overflows[0],
                    "translucent", FrameTiming.drawOverflows[1] - overflows[1], "temporal", FrameTiming.drawOverflows[2] - overflows[2]));
            result.put("drawLoopMsPerFrame", ms((FrameTiming.jniDrawLoopNs - drawNs) / voxyCount));
            var gpu = new LinkedHashMap<String, Object>();
            long gpuTotal = 0;
            for (var entry : FrameTiming.gpuNsBySubmitter.entrySet()) {
                gpu.put(entry.getKey(), ms(entry.getValue()[0] / voxyCount) + "ms x" + Math.round(entry.getValue()[1] * 10.0 / voxyCount) / 10.0);
                gpuTotal += entry.getValue()[0];
            }
            result.put("gpuMsPerFrame", ms(gpuTotal / voxyCount));
            result.put("gpuBySubmitter", gpu);
            var waits = new LinkedHashMap<String, Object>();
            FrameTiming.waits.forEach((caller, n) -> waits.put(caller, Math.round(n * 100.0 / voxyCount) / 100.0));
            result.put("finishPerFrameByCaller", waits);
        }
        Logger.info("Metal perf benchmark " + result);
        return result;
    }

    private static double ms(long nanos) { return Math.round(nanos / 1e4) / 100.0; }
}
