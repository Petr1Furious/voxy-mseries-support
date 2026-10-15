# Voxy Metal for Minecraft 26.3 — EXPERIMENTAL / UNOFFICIAL

Native Apple Metal renders Voxy LODs; Minecraft and the final IOSurface compositor use OpenGL. This is an experimental version port, **NOT an official Voxy release**.

**Publication status: local candidate only.** The owner reports that redistribution permissions have been obtained from relevant Voxy/Metal rights holders. Third-party native notice coverage remains incomplete, so no public binary release is cleared by this audit.

| Component | Development target |
| --- | --- |
| Minecraft / Voxy baseline | 26.3 / upstream 0.2.20-beta |
| Experimental mod version | 0.2.20-metal.26.3.experimental.1 |
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

See [technical report](TECHNICAL_REPORT.md), [Prism installation and troubleshooting](docs/INSTALLATION.md), [technical comparison](docs/METAL_26_3_PORT.md), [complete file inventory](docs/CHANGE_INVENTORY.md), [rights/dependency review](docs/RIGHTS_AND_DEPENDENCIES.md), and [local PR draft](docs/PR_DRAFT.md).

Original Voxy: [MCRcortex](https://github.com/MCRcortex/voxy). Native Metal/GPU contracts and IOSurface foundation: [srjefers/voxy-mseries-support](https://github.com/srjefers/voxy-mseries-support), including its contributors. Commit provenance is recorded in the technical comparison. This does not imply endorsement.

**Most implementation work was AI-assisted using OpenAI Codex**, including port development, tests, review, documentation, and contribution preparation. AI-generated explanations are not evidence of correctness; the testing document separates executed checks from remaining manual acceptance. Human maintainers should review code and rights before integration.

[LICENSE.md](LICENSE.md) retains the original restrictive notice. The owner reports that the relevant rights holders granted redistribution permission; this statement does not relicense upstream code or grant additional rights to recipients. The exact agreement is not reproduced here. See [rights and dependency status](docs/RIGHTS_AND_DEPENDENCIES.md) and [bundled notices](third-party/README.md). Missing third-party notice coverage still blocks publication.
