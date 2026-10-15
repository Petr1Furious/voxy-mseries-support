# Experimental Voxy Metal port for Minecraft 26.3

Native Apple Metal renders Voxy LODs; Minecraft and the final IOSurface compositor use OpenGL. This is an experimental version port, not an official release.

| Component | Development target |
| --- | --- |
| Minecraft / Voxy baseline | 26.3 / 0.2.20-beta |
| Java | JDK 25, arm64 |
| Fabric Loader / API | 0.19.5 / 0.160.5+26.3 |
| Sodium | mc26.3-0.9.3-alpha.1-fabric |
| LWJGL | 3.4.3 |
| Validation host | Apple M3, 8 GB, macOS 26.6 (25G72) |

Other chips and OS versions are unverified. The native macOS 12 deployment target is not a tested minimum. Fabric metadata accepts broader ranges than the tested development versions.

## Build and test

Requires macOS arm64, JDK 25, CMake 3.20+, and Xcode command-line tools.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
./gradlew build --max-workers=2 --console=plain
MTL_DEBUG_LAYER=1 ./gradlew testMetalTraversal testMetalTerrainIOSurface testMetalFrameBridge testMetalFrameFlow --max-workers=2 --console=plain
```

`build` alone does not execute GPU smoke tests. See [testing instructions and verified results](docs/TESTING.md) for the full suite. Native compilation also caps parallel jobs at two. Outputs and shader caches are ignored; do not copy them into source control.

For manual testing use an isolated development save:

```sh
./gradlew runMetalClient -PmetalDevWorld --max-workers=2
```

This creates/uses `run-metal-263`, never a launcher installation. `-Dvoxy.backend=metal` selects Metal; `opengl` selects the legacy renderer; `auto` selects Metal on macOS aarch64. A native device probe is insufficient evidence of LOD rendering. Inspect actual world rendering and compare LOD on/off at the same camera.

## Known limitations

- Iris shader packs, VR, Intel Macs, other Apple chips, long sessions, and the legacy OpenGL path have no fresh acceptance test.
- This port does not retain the older fork's complete Iris/VX-contract compositor or animated-water implementation. Do not infer feature parity from the older fork's README.
- Indirect commands currently require CPU-visible command data and synchronous GPU submissions, limiting performance.
- Traversal TAA bindings are unsupported and fail explicitly. Full model atlas allocation is roughly 510 MiB; the world geometry heap is 256 MiB and CPU geometry cache 128 MiB.
- Optional Chunky preparation generates/imports a local world and persists its preparation state. Large areas can exhaust a 2 GiB heap. Use disposable saves and verify completed saves/imports before trusting READY state.
- No fresh gameplay, FPS comparison, or long-duration memory result is claimed for this contribution candidate.

## Contribution and credits

See [technical comparison](docs/METAL_26_3_PORT.md), [complete file inventory](docs/CHANGE_INVENTORY.md), [rights/dependency review](docs/RIGHTS_AND_DEPENDENCIES.md), and [local PR draft](docs/PR_DRAFT.md).

Original Voxy: [MCRcortex](https://github.com/MCRcortex/voxy). Native Metal/GPU contracts and IOSurface foundation: [srjefers/voxy-mseries-support](https://github.com/srjefers/voxy-mseries-support), including its contributors. Commit provenance is recorded in the technical comparison. This does not imply endorsement.

AI tools, including OpenAI Codex, assisted with port development, tests, review, documentation, and contribution preparation. AI-generated explanations are not evidence of correctness; the testing document separates executed checks from remaining manual acceptance. Human maintainers should review code and rights before integration.

[LICENSE.md](LICENSE.md) retains “All rights reserved. Do not redistribute.” No additional permission or new license is asserted. The local candidate is not cleared for redistribution; obtain authorization covering upstream and fork contributions before sharing source, patches, or binaries.
