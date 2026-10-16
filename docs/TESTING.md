# Build and GPU validation

## Final pre-release candidate verified on 2026-10-16

Status: **EXPERIMENTAL / UNOFFICIAL candidate; publication explicitly authorized by the owner despite incomplete third-party native notice coverage**. The owner reports Voxy/Metal redistribution permissions separately.

Host: Apple M3 / 8 GB / macOS 26.6 (25G72), arm64 Temurin JDK 25.0.4.1. Tested snapshot: `6748c7c10da1abaf436486efd75fa23abe028f2e`. Later commits record these results and do not change executable code or the tested JAR.

Fresh native and Gradle output directories were moved aside into ignored backup storage before running `clean build` plus every registered GPU smoke task, using JDK 25, `MTL_DEBUG_LAYER=1`, `--no-build-cache --rerun-tasks --max-workers=2 --console=plain`. Result: **BUILD SUCCESSFUL in 36s; 34 actionable tasks, all 34 executed; all 25 GPU/native smoke tasks passed**. All 25 test JVMs logged Metal API Validation Enabled. No build/AssertionError/Metal failure marker was found. Full commands and task coverage are listed in the earlier run below; prepend `clean` for this run.

The original build output and native build state were preserved. Both JNI CMake compilation and Java compilation ran afresh; this result is not a cached compilation or only a native probe.

Local artifact: `voxy-metal-0.2.20-metal.26.3.experimental.1-6748c7c.jar`.

```text
SHA-256 52b88918d3cfa8b52c4475d67340bd32ab42a6101008fed58098873f38dffd39
```

Candidate JAR, `SHA256SUMS` and a detailed verification manifest are staged under ignored `build/release-candidate`. Raw logs remain outside tracked source because they contain host paths. The tested JAR and `SHA256SUMS` were subsequently published in the [experimental pre-release](https://github.com/udmur1/voxy-mseries-support/releases/tag/metal-26.3-experimental.1). Raw logs and native-inspection working files were not uploaded.

JAR checks passed: experimental version/name metadata; embedded source commit; root license bytes identical to `LICENSE.md`; all 15 collected third-party notice texts match their recorded SHA-256; Metal JNI matches the fresh resource byte-for-byte. Mach-O inspection confirmed arm64 for every required native payload below.

| Library | Location |
| --- | --- |
| libvoxy_metal.dylib | Root JAR `natives/macos-arm64` |
| libshaderc.dylib | Nested LWJGL shaderc macOS arm64 JAR |
| libspirv-cross.dylib | Nested LWJGL spvc macOS arm64 JAR |
| liblwjgl_lmdb.dylib | Nested LWJGL LMDB macOS arm64 JAR |
| liblwjgl_zstd.dylib | Nested LWJGL Zstd macOS arm64 JAR |
| librocksdbjni-osx-arm64.jnilib | Filtered nested RocksDB JNI JAR |

Minecraft/Loom supplies runtime LWJGL core/GL/GLFW. This verification does not claim the Voxy JAR contains the complete Minecraft runtime. The tests use the compiled runtime classpath and freshly built native library; ZIP inspection establishes that the same JNI bytes are in the JAR. It is not a launcher gameplay acceptance test.

The warning/limitation and manual-acceptance notes below still apply. No fresh FPS, full RAM measurement, launcher installation or gameplay result is claimed. Native notice completeness remains unresolved despite passing validation; the owner explicitly waived the previous publication stop. No complete compliance claim is made.

## Earlier contribution validation (historical)

## Verified on 2026-10-16

Host: Apple M3 / 8 GB / macOS 26.6 (25G72). JDK: Eclipse Temurin 25.0.4.1, arm64. Gradle wrapper: 9.6.0; resolved Loom: 1.16.3. Historical code/test snapshot: `1700a5da`; this is not the final packaging/version snapshot above.

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
