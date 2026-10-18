# Metal frame-rate work

Measured on 2026-10-16 on an Apple M1 Pro (16 GB), macOS, 2560×1600 framebuffer, 12 vanilla chunks, Voxy defaults (LOD distance 16, subdivision 64, fade fog, basic SSAO). The world is seed 263 with a 64-chunk Chunky radius imported into Voxy (~13k chunks of LOD).

## Results

Each Voxy-on value is the range of two measurements, before and after the Voxy-off phases (ON, OFF, ON ordering). Frame rate is wall-clock frames over 12 s after warm-up.

| Build | Horizon camera | Overview camera | Voxy off |
| --- | --- | --- | --- |
| Before (blocking submits, `glFinish` hand-off) | 64–72 fps | 63–67 fps | 368–444 fps |
| Asynchronous submit | 71–76 fps | 67–72 fps | — |
| + IOSurface hand-off without CPU waits | 89–91 fps | 78–79 fps | — |
| + Batched native draw loop, early opaque commit | **107–111 fps** | **91–92 fps** | 376–433 fps |

p99 frame time at the horizon camera fell from 20.3 ms to 8.5–8.8 ms.

With 5 ms of extra render-thread CPU work per frame (a stand-in for a busy server or many entities), the gain is smaller: 62–70 → 63–72 fps. The remaining limit is described below.

### Second round (2026-10-18): no CPU wait for draw commands

Same scene, same build each time, alternating `-Dvoxy.metal.syncDraws=true` (the previous wait) with the new default:

| Build | Horizon camera | Overview camera | Voxy render-thread time |
| --- | --- | --- | --- |
| Wait for command generation | 110–117 fps | 92–93 fps | 6.0–7.3 ms |
| No wait (GPU-padded draw bounds) | 116–120 fps | 92–94 fps | 1.5 ms |
| Wait, + 5 ms CPU load | 70–75 fps | 66–68 fps | 6.1–7.5 ms |
| No wait, + 5 ms CPU load | 71–75 fps | 66–69 fps | 1.3–1.5 ms |

Voxy no longer stalls the render thread, but frame rate barely moves: the time now goes to the OpenGL buffer swap (see "Remaining costs").

## What changed

1. **`MetalRenderBackend.submit()` no longer waits.** It commits the command buffer and tracks it, as `glFlush` does on the GL backend. Staging memory is already guarded by fences. `finish()` remains the explicit CPU wait, and GPU errors are reported when the buffers retire. Per-frame waits fell from 7 to 1.
2. **Removed redundant waits.** These were in `GpuMDICTerrain.render` (the command buffers are complete when `GpuMDICDrawCalls.build` returns) and in the middle of `build`. The section-capacity check now reads the count after the final wait.
3. **GL↔Metal hand-off without CPU waits.** The hand-off follows Apple's IOSurface pattern: `glFlush` before Metal reads GL output, `waitUntilScheduled` before GL reads Metal output. The two `glFinish` calls and two `waitUntilCompleted` calls per frame are gone. `-Dvoxy.metal.syncInterop=true` restores the old blocking behaviour.
4. **One JNI call per draw layer.** Previously each indirect draw cost two JNI crossings (~5.5k draws per frame); now a single native loop encodes them all.
5. **The opaque pass is committed as soon as it is encoded**, so the GPU starts the frame's largest pass while the CPU encodes Hi-Z and traversal.
6. **No CPU readback of the generated draw commands.** The port assumed Metal's indirect draws do not pass `baseInstance` to `[[base_instance]]`, so the CPU read every command and pushed the value per draw. `testMetalBaseInstanceProbe` shows that they do (M1 Pro, macOS 26), so the terrain shader reads `gl_BaseInstance` directly. Metal still has no draw count from a buffer: `MetalDrawBounds` encodes, per layer, 1.5× the largest count of the last 8 frames plus 1,024 draws, and `metal_pad.comp` turns commands between the generated count and that bound into empty draws. A viewport's first build waits for exact counts; `-Dvoxy.metal.syncDraws=true` waits every frame.

## Correctness checks

All 27 GPU smoke tasks pass with `MTL_DEBUG_LAYER=1`. Tests that read GPU results after `submit()` now call `finish()`; they relied on the old blocking submit.

`testMetalBaseInstanceProbe` draws 8 indirect commands with distinct `baseInstance` values and checks each reaches the shader. `testMetalMDIC` now also checks that stale commands past the generated count are padded to empty draws.

If a layer's count exceeds its bound, the extra draws are skipped for one frame. The benchmark counts this per layer. With the camera turning 2° and 6° per frame (about 230°/s and 700°/s) there were none in ~9,400 measured frames; a 1.25× + 256 bound did overflow occasionally at 6°/frame. A teleport can still overflow for a frame while new LOD loads.

Screenshots with and without the readback differ in 1.6–2.9% of pixels, nearly all by a few levels; two screenshots of one run at the same camera differ in 23% (water, clouds).

`testMetalInteropOrdering` is new. It pushes 240 frames per depth convention through GL → Metal → GL with a deliberately slow Metal pass. Each frame carries a unique colour and depth. The previous frame is read back only after the next frame's Metal work is in flight. With all hand-off synchronization removed, it fails on the first frame. With the shipped hand-off it passed 6 consecutive runs.

In-game screenshots before and after match at both cameras.

## Measured but not kept

- **Attributes on the provoking vertex only, as on GL.** This was correct (Metal uses the first vertex), but made no difference, because of SIMD divergence.
- **Quads as indexed triangle strips with primitive restart.** 15–17% slower. Apple GPUs reuse vertices within indexed triangle lists.
- **Hi-Z compute test instead of the visibility raster.** The raster (`cull/raster.*`) draws each section's box against full-resolution depth. Testing the same boxes against the Hi-Z pyramid in compute is nearly free, but marks 23–30% more draws visible, and the opaque pass costs more than the raster saved: 93–104 vs 104–120 fps (horizon). Skipping redundant `visibilityData` writes in the raster made no difference either.
- **Tighter temporal draw bound.** Halves encoded draws (13.7k → 7.6k) and the draw loop (≈1 → 0.5 ms), but frame rate is unchanged and fast turns overflow; empty draws are cheap on the GPU.
- **GPU-encoded indirect command buffers**, to remove the last wait. Shaders used by ICBs cannot use directly bound textures, and the terrain shaders sample the atlas, lightmap and depth. This requires moving the backend to argument-buffer texture bindings first.

## Remaining costs

- **The OpenGL buffer swap waits for the GPU to finish the frame.** A sample of the render thread shows ~40% of its time in `CGLFlushDrawable` → `GLDContextRec::flushContext` (a semaphore wait). macOS's OpenGL does not let the CPU start the next frame while the GPU finishes this one, even with Voxy disabled (5 ms of CPU load costs vanilla the full 5 ms). Frame time is therefore about Minecraft's CPU work plus the frame's GPU time, and removing CPU work from Voxy mostly moves the wait into the swap. Avoiding this would need presenting through Metal instead of the OpenGL swap.
- **GPU time per frame**, now the limit: about 8.5 ms at the horizon camera and 11 ms at the overview camera. The opaque terrain pass is the largest part (3.5–4.5 ms, vertex-bound, ~1.4M quads); the visibility raster is also significant (see above).
- **LOD subdivision is measured in framebuffer pixels.** Retina displays therefore get roughly twice the LOD detail per axis of a standard display. Subdivision 128 (about 64 points on a 2× display) drew 29% fewer quads: 95–104 fps horizon, 83 fps overview, measured before step 4. This is a user setting, not a default change.

## Reproducing

```sh
# Once: generate the benchmark world (needs Chunky, pulled in by the property).
./gradlew runMetalClient -PmetalDevPerfPrepare=64 -PmetalDevHeap=4g
# Keep a copy of run-metal-263/saves/perf-bench and restore it before every run.
caffeinate -dimsu ./gradlew runMetalClient -PmetalDevExistingWorld=perf-bench -PmetalDevPerf=<label> -PmetalDevHeap=3g
```

The report is written to `run-metal-263/perf-benchmark/`. Optional flags:

- `-PmetalDevPerfSplit` commits after every frame stage, for per-stage GPU time (profiling only).
- `-PmetalDevJvmArgs="-Dvoxy.dev.perfSpinDeg=6 -Dvoxy.dev.perfCpuLoadMs=5 -Dvoxy.dev.perfSubdiv=128 -Dvoxy.dev.perfVanillaChunks=12 -Dvoxy.dev.perfWidth=1280 -Dvoxy.dev.perfHeight=800"`

Keep the display awake (`caffeinate`): if it sleeps, Minecraft blocks in the buffer swap.
