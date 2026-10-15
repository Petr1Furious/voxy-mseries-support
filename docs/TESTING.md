# Build and GPU validation

## Verified on 2026-10-16

Host: Apple M3 / 8 GB / macOS 26.6 (25G72). JDK: Eclipse Temurin 25.0.4.1, arm64. Gradle wrapper: 9.6.0; resolved Loom: 1.16.3. Code/test snapshot: `1700a5da` (subsequent changes are documentation only).

A separate worktree with no copied Gradle/native outputs was built successfully. Then the following forced run passed in 34 seconds: **33 tasks executed, 25 GPU smoke tasks passed**. All 25 GPU JVMs logged `Metal API Validation Enabled`. No FAILED/AssertionError/Metal failure marker appeared. `test` itself reports NO-SOURCE and provides no GPU coverage.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
MTL_DEBUG_LAYER=1 ./gradlew build \
  testMetalRenderBackend testMetalTriangle testMetalCompute testMetalVertexBuffer \
  testMetalIcb testIOSurfaceBridge testIOSurfaceGl testVoxy26Compute \
  testMetalStreams testMetalGeometry testMetalNodeUploads testMetalModelTextures \
  testMetalNodeCleaner testMetalRawReadback testMetalHiZ testMetalTraversal \
  testMetalViewport testMetalMDIC testMetalTerrain testMetalBounds \
  testMetalTerrainIOSurface testMetalFrameBridge testMetalSsao testMetalMDICCapacity \
  testMetalFrameFlow \
  --no-build-cache --rerun-tasks --max-workers=2 --console=plain
```

The forced run executed Java compilation, CMake/native build and packaging. CMake incremental state was created by the preceding fresh-worktree build. For an independent fresh native build, use a new worktree; `clean` only removes Gradle output, not `native/metal/build`. Native jobs are capped at two and smoke JVM heaps at 512 MiB. Shader caches stay under ignored `build/metal-shader-cache`.

Coverage includes backend lifecycle, triangle/compute/ICB pixels, vertex buffers, IOSurface byte sharing and CGL readback, production shader compilation/preparation, streams and raw readback, geometry ownership, node uploads/cleanup, model textures/mips, conservative Hi-Z, hierarchy/frustum/occlusion, viewports, bounds, MDIC commands/capacity, terrain lighting/depth/translucency, SSAO, and final frame pixels across temporal history and resize. Individual fixtures cover multiple clip/reverse-Z combinations; these are component tests, not proof of all in-game scenes.

The local JAR `voxy-0.2.20-beta-1700a5d.jar` has SHA-256 `2038d130dbb849f2979b759a5673fdc517d13a2c50796674bd694c48856ef0d3`. Its embedded native library matches the rebuilt resource byte-for-byte; the root license is present. Build timestamps/commit metadata mean later builds can have different names and hashes. This artifact has no new manual gameplay acceptance and is not cleared for redistribution.

Expected warnings observed: LWJGL deprecated Unsafe use, Gradle deprecations, XZ version semver warning, and standalone test harness `Running voxy without minecraft` messages. These occurred in successful tests and are not suppressed here.

Raw build/test logs remain local outside tracked source because they contain host paths. No prior FPS, launcher log, screenshot or benchmark JSON is presented as fresh validation.

## Manual acceptance still required

1. Launch `runMetalClient -PmetalDevWorld --max-workers=2` with JDK 25; use only the isolated development directory and disposable saves.
2. Check the Metal world-renderer log, actual distant terrain and LOD on/off visual difference at an identical camera. A device probe alone is insufficient.
3. Check world reopen, dimension changes, resize, cutout/translucent terrain, underwater/fog, chunk boundaries and missing terrain requests.
4. Test optional preparation only on a disposable local save; inspect completed saves/import state and failure recovery. Large generation workloads may exhaust the heap.
5. Measure FPS and process memory separately without Metal validation, at fixed framebuffer/camera/settings, with a controlled no-Voxy comparison. No such result was collected for this candidate.
6. Independently validate the legacy GL renderer, Iris/VR, other Apple chips/OS versions and long-duration resource stability before claiming support.

When reporting failures, record exact versions, commit/JAR hash, framebuffer dimensions, camera, render distances, shader state and sanitized logs. Never attach account files or token-bearing launcher command lines.
