# Experimental unofficial Voxy Metal 26.3 — technical release audit

**EXPERIMENTAL / UNOFFICIAL / NOT an official Voxy release.** Audit date: 2026-10-16 (Asia/Tbilisi). This document records local preparation and verified evidence; it does not announce a public release.

## Provenance and scope

Upstream Minecraft 26.3 baseline: MCRcortex/voxy `61c1dee5077c73fced865025430c6ce7dc87537c`. Imported Metal foundation: srjefers/voxy-mseries-support `dev` at `c544ee8d3ee9d56788f517ca7157bc24956d5368`, targeting Minecraft 1.21.11. The original working port was `metal/macos-arm64` at `9d71428f`. The contribution is maintained separately as `contribution/metal-26.3`; original source commits, original Metal fork and Prism installation are preserved.

The pre-release audit compared tracked Java/native/rendering shader source bytes against the working port: no executable-source difference was found. Packaging/metadata changes identify experimental builds and include root/third-party notices; documentation changes replace private installation history. No benchmark screenshots, private worlds, generated caches, dylibs or evaluation JSON are tracked. The Gradle wrapper JAR and existing mod icon are intentional source/build assets. A path/secret-pattern scan found no matching credentials or personal absolute paths in the tracked tree; this is a bounded scan, not a universal absence guarantee.

The [full file inventory](docs/CHANGE_INVENTORY.md) separates the upstream 26.3 baseline from local port changes. The [architecture comparison](docs/METAL_26_3_PORT.md) explains why this is a version port rather than a small patch to fork `dev`. Historical performance/launcher evidence from private reports is not presented as fresh candidate acceptance.

## Architecture and integration

| Layer | Implementation and verification boundary |
| --- | --- |
| Backend | JNI Objective-C++ Metal device/resources/pipelines/fences, explicit GPU interfaces and GLSL → SPIR-V → MSL compilation through LWJGL shaderc/SPIRV-Cross. |
| Storage/services | Explicit upload/download/readback streams, owned/borrowed geometry, software-bakery atlas uploads, async node copies/scatter, cleanup candidate selection and save/import completion tracking. |
| Visibility | Conservative Hi-Z, bounded hierarchy queues, coarse-parent fallback on capacity exhaustion, frustum/distance/occlusion rejection and reverse-Z/clip-range adaptation. |
| Rendering | MDIC viewport/bounds, opaque/temporal/translucent commands, sorting, terrain textures/lighting/fog, vanilla depth mask and SSAO. |
| Interop | Shared IOSurface BGRA color/float depth, lightmap transfer, GL state restoration, final GL pixel composition and resize/history lifecycle. |
| World entry | `VoxyClient` selects/probes Metal; `VoxyRenderSystem` creates `MetalWorldRenderer` and routes `renderOpaque` into its frame renderer. Its world/service resources are explicitly released. The 26.3 Sodium hook supplies the existing render-system entry. |

The static integration path is present and fresh GPU tests execute production shaders/frame components. This is stronger than compilation or device detection, but does not replace a fresh Minecraft world acceptance run. Vanilla Minecraft and final composition continue to use OpenGL. A Metal selection failure is not documented as a successful fallback.

The world geometry heap is 256 MiB; CPU geometry cache is bounded to 128 MiB. A full model atlas is roughly 510 MiB. The cleaner reserve is `min(256000000, capacity / 4)`, preventing early eviction churn in the small heap while retaining the old reserve on large heaps. These figures are code/allocation facts, not a measurement of total gameplay process memory.

## Build and verification

Use macOS arm64, JDK 25, CMake 3.20+ and Xcode command-line tools. Gradle workers and native compilation jobs are capped at two. [Testing instructions](docs/TESTING.md) record the exact fresh-run snapshot, tasks and results; [installation instructions](docs/INSTALLATION.md) cover Prism in a separate test instance. `build` alone does not run the GPU smoke suite.

The audit preserves earlier ignored artifacts before moving aside native build state and rebuilding. JAR verification checks the root license, notice bundle, expanded Fabric metadata, exact embedded Metal dylib, Mach-O arm64 architecture and macOS arm64 shaderc/SPIRV-Cross/LMDB/Zstd/RocksDB JNI payloads. SHA-256 identifies the evaluated JAR; commit/timestamp metadata can make later rebuilds differ. Runtime LWJGL core/OpenGL/GLFW are supplied by Minecraft/Loom rather than all being nested in Voxy.

Final local candidate: `voxy-metal-0.2.20-metal.26.3.experimental.1-6748c7c.jar`, built from `6748c7c10da1abaf436486efd75fa23abe028f2e`. Clean Java/JNI build and all 25 GPU/native tests passed with Metal API Validation; 34 Gradle tasks executed in 36s. All six required native payloads are arm64, root license/15 collected notice hashes match, and embedded JNI matches the fresh resource. SHA-256: `52b88918d3cfa8b52c4475d67340bd32ab42a6101008fed58098873f38dffd39`. This JAR remains local and is not cleared for redistribution because required notice completeness remains unresolved.

## Compatibility and limitations

Development target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3, Sodium mc26.3-0.9.3-alpha.1-fabric, LWJGL 3.4.3, arm64 JDK 25. Current GPU validation host: Apple M3 / 8 GB / macOS 26.6 (25G72). No other Apple-chip/OS compatibility is established; macOS 12 is a deployment target, not a tested minimum.

The older fork's complete Iris/VX g-buffer/compositor/side-channel and animated-water implementation are not retained as equivalent features. Iris/shader packs, VR, traversal TAA, legacy OpenGL regressions and long-session stability require further work or acceptance. Indirect command handling still uses CPU-visible command memory and synchronous submissions, limiting performance. Optional Chunky generation/import can exceed a 2 GiB heap. Development/test classes remain in the main source set and some unused non-macOS native payloads remain nested; these are packaging limitations.

No fresh gameplay, controlled FPS comparison, full RAM measurement or production-readiness claim is made. Remaining manual checks include actual LOD on/off at fixed camera, world/dimension reopen, resize, underwater/fog/cutout/translucent boundaries and long-duration resource stability. See the testing checklist.

## Rights, attribution and publication gate

The owner states that relevant rights holders have granted redistribution permissions for this port. The original root license and attribution remain intact; no permissive relicense or sublicense is inferred, and the agreement itself is not reproduced. Third-party obligations remain separate. [Dependency review](docs/RIGHTS_AND_DEPENDENCIES.md) and the [notice bundle](third-party/README.md) list exact collected evidence and unresolved native/transitive coverage. Partial notices do not clear a binary release. Publication must stop while required coverage remains unresolved.

Active GitHub account was confirmed through API as `udmur1`; upstream access is pull-only. Proposed destination is a fork at `udmur1/voxy-mseries-support`, branch `contribution/metal-26.3`, with a proposed pre-release tag `metal-26.3-experimental.1`. These destinations are proposals pending owner confirmation, not published URLs. No suitable Minecraft 26.3 target exists in srjefers' checked branch list. Recommend that the upstream owner create/approve `experimental/minecraft-26.3` from the agreed 26.3 baseline, then review a draft cross-fork PR. Do not force this port into 1.21.11 `dev` and do not modify upstream branches. The [PR draft](docs/PR_DRAFT.md) is local only.

Credits: MCRcortex and Voxy contributors for the original mod; srjefers and all contributors to voxy-mseries-support for the Metal/GPU/IOSurface foundation; dependency authors retain their notices. This work implies no endorsement from upstream authors.

**Most implementation work was AI-assisted using OpenAI Codex**, including implementation, debugging, tests, documentation and release preparation. Human review remains necessary. Statements about results are restricted to executed checks; AI-generated explanations are not correctness evidence.
