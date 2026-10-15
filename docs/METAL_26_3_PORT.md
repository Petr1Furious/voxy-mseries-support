# Minecraft 26.3 Metal contribution review

## Provenance and comparison

Review date: 2026-10-16 (Asia/Tbilisi). Original local branch: `metal/macos-arm64` at `9d71428f`. Minecraft baseline: `MCRcortex/voxy` branch `263`, `61c1dee5077c73fced865025430c6ce7dc87537c`.

Relevant Metal fork target: `srjefers/voxy-mseries-support` branch `dev`, `c544ee8d3ee9d56788f517ca7157bc24956d5368`. A live read-only `git ls-remote` confirmed this HEAD; the local `metal-source/dev` object is identical. The fork's feature branch `feature/voxy-mseries-metal` is older (`6567c4e0`), so comparing only against it would omit subsequent fixes. There is no public Minecraft 26.3 branch in the checked remote branch list. The local `metal-source/port/minecraft-26.3` name points to the 1.21.11 snapshot and does not establish a published 26.3 port.

The branches share ancestor `ee7ec50d4428cf28253d628ac5e2ed3fbf67e7a0`. This is a rebuild on upstream 26.3, not a linear continuation of the Metal fork. Two-dot comparisons are essential: a three-dot PR diff also includes upstream 26.3 changes since that ancestor. The contribution branch starts at `61c1dee5`, preserving upstream authorship without replaying private launcher-history commits.

## Functional changes

Relative to upstream 26.3:

1. Introduce GPU contracts, runtime GLSL → SPIR-V → MSL compilation and the native Objective-C++/JNI Metal backend, buffers, textures, pipelines, fences, encoders and indirect command support.
2. Adapt staged upload/download streams, raw readback, fences, lazy GL defaults and geometry ownership to explicit Metal resources. Guard GL-only IDs and prevent cross-backend reuse.
3. Move node scatter/copies, visibility refresh, exact oldest-node cleanup selection, traversal and conservative Hi-Z reduction to Metal. Bound traversal queues, retain coarse parents on exhaustion and honor reverse-Z/clip conventions.
4. Add Metal MDIC viewport/bounds, visibility raster, preparation, opaque/translucent commands, prefix sorting, quad terrain, depth mask, lighting, fog and SSAO. Preserve the 26.3 software bakery and upload its model atlas/mips to Metal.
5. Share BGRA color and float depth via IOSurface, restore GL texture/pixel-pack/compositor state, preserve readback ordering and correct Metal attachment/storage contracts. Compose frames across temporal history and resize.
6. Select a dedicated Metal world renderer at the 26.3 Sodium render hook, own world services/resources and clean them on failure/shutdown. Keep legacy GL constructors/defaults; OpenGL regression coverage remains pending.
7. Limit Metal CPU geometry caching to 128 MiB. Scale geometry cleanup reserve to `min(256000000, capacity / 4)`, avoiding early eviction churn in the 256 MiB heap while retaining the old reserve on large heaps.
8. Add optional persisted Chunky preparation, import completion and save-failure/drain tracking, session lifecycle handling, development worlds, diagnostics, camera sweeps and benchmarks. These are additional behavior beyond the renderer and candidates for later separation.
9. Build native code from source with JDK 25 and two CMake jobs; retain macOS arm64 RocksDB JNI, align LWJGL to 3.4.3, bundle shader compiler/native dependencies, register 25 GPU smoke tasks and isolate Loom data/cache paths.

Relative to Metal fork dev, upstream 26.3 additionally changes game/Sodium/Fabric hooks, RenderPearl render/depth properties, session/world handling, software baking, geometry storage, bounding and shader ABIs. The older GLCompat/GL backend abstraction, old GPU bakery, IOSurface compositor, water animator, VX/Iris g-buffer/resolve/side-channel, Vulkan/MoltenVK loader and old mixins are absent or replaced. Their removal is material: shader-pack and animated-water parity is not established. Old fork documentation, bundled dylibs and tracked Gradle partial downloads are absent. The complete per-file inventory distinguishes baseline changes from local port changes.

## Candidate cleanup

The source behavior is preserved from the original branch. New functional packaging correction: `jar` now includes the actual `LICENSE.md` rather than nonexistent `LICENSE`. Ignore rules cover local caches, credentials directories, native output, logs and dumps. Generated screenshots/benchmark JSON, launcher installation histories and old machine-specific reports were excluded. The normal Gradle wrapper JAR and existing mod icon remain intentional build/source assets.

Smoke test and development classes remain in the main source set as in the original implementation. Splitting development harnesses and optional preparation from release packaging is future work; the candidate is not a release artifact.

## Integration recommendation

Use a separate experimental Minecraft 26.3 branch. A direct PR to `dev` is technically possible because ancestry is shared, but it would replace substantial 1.21.11 functionality and omit existing fork features. The owner now reports contribution/redistribution permissions obtained. Agree on a 26.3 target with maintainers and resolve the remaining third-party notice coverage before publication. Then review the version baseline, renderer, optional preparation and test tooling separately. The local PR draft records these limitations and grants no publishing authorization.
