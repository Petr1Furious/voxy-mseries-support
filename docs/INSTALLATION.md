# Prism Launcher installation and troubleshooting

**EXPERIMENTAL / UNOFFICIAL — NOT an official Voxy release.** These instructions prepare a separate test instance; this audit did not modify or retest an existing Prism installation. The owner authorized an experimental pre-release despite incomplete native notice coverage; this outstanding issue remains disclosed in the release notes.

Download: [metal-26.3-experimental.1](https://github.com/udmur1/voxy-mseries-support/releases/tag/metal-26.3-experimental.1). Use its JAR and `SHA256SUMS`; source-archive ZIP files are not installable mod JARs.

## Tested build target

Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3, Sodium mc26.3-0.9.3-alpha.1-fabric, arm64 JDK 25. Only Apple M3 / 8 GB / macOS 26.6 has the current component-test evidence. A macOS 12 native deployment target does not establish a supported minimum. The mod metadata accepts broader dependency ranges; other versions are unverified.

## Install in a disposable instance

1. In Prism, create a new Minecraft 26.3 instance. Keep your working instance untouched. In Edit → Version, install Fabric Loader 0.19.5.
2. Select an Apple Silicon JDK 25 in the instance's Java settings. Confirm the actual launch log shows Java 25 and arm64/aarch64. Do not use the system's default JDK 26 for this evaluation.
3. In Edit → Mods, install matching Fabric API and Sodium from their official distribution pages. Use the exact versions above for a comparable test; do not install an official Voxy JAR alongside this port. Mod Menu is optional. Iris, VR and shader packs are outside the validated configuration.
4. Once a candidate is authorized for use, check its SHA-256 against the accompanying `SHA256SUMS`, then add its JAR using Edit → Mods. The JAR must embed the Metal JNI dylib, compiler natives and storage natives; users should not separately copy dylibs into system folders.
5. Add these instance JVM arguments:

   ```text
   -Dvoxy.backend=metal --enable-native-access=ALL-UNNAMED
   ```

6. Start with a disposable new world and a small LOD distance. Validate distant terrain with LOD on/off at the same camera, then test reopening/resizing. Never import a private production world as the first acceptance test. Back up any world before later experimentation.

Prism's supported mod installation flow is described in its [official documentation](https://prismlauncher.org/wiki/getting-started/download-mods/). This procedure is an installation guide, not a claim that the freshly built candidate passed manual gameplay acceptance.

## Configuration

- `voxy.backend=metal` selects the Metal LOD renderer. `auto` selects it on macOS aarch64; `opengl` selects the legacy renderer. Vanilla rendering and composition still use OpenGL. Keep Minecraft's compatible OpenGL backend for this path; Vulkan interoperability is not validated.
- The Voxy configuration uses snake-case fields such as `section_render_distance`, `service_threads`, and `ssao_mode`. Prefer the in-game controls; if editing files, close the game first and keep a backup. No personal configuration file is shipped.
- `section_render_distance` is measured in 512-block units: 1.5 corresponds to 768 blocks / 48 Minecraft chunks. This was a historical evaluation setting, not a universal performance recommendation. Missing terrain must be generated/imported before a larger draw radius can display it.
- BASIC SSAO and modest distances are useful initial evaluation settings. The 8 GB machine shares memory between JVM, Metal, system and other apps. A larger Java heap reduces memory left for GPU/system use; this audit does not establish an optimal heap size.
- Optional Chunky preparation is a separate generation/import workload, not required for the rendering tests. Large fresh regions have historically exhausted a 2 GiB heap. Use disposable saves and check saved/imported completion; optional integration licensing also remains under review.
- `-Dvoxy.shaderCacheDir=<directory>` overrides the runtime shader cache. Development runs/tests use ignored `build/metal-shader-cache`; normal runtime defaults to the user's `.voxy/shader-cache`. Cache files are not release assets.

## Troubleshooting

| Symptom | Checks |
| --- | --- |
| Native library fails to load | Confirm macOS arm64 and JDK 25; inspect the JAR for `natives/macos-arm64/libvoxy_metal.dylib`; record the exact exception. A selected Metal renderer does not silently fall back to the GL world renderer. |
| Metal device detected but no distant terrain | Device detection alone is insufficient. Require the `Voxy Metal world renderer created` log and actual terrain evidence; confirm the world has generated/imported data. |
| OpenGL appears in F3/logs | Expected for vanilla Minecraft and the final compositor; it does not prove that LODs run on OpenGL. |
| Wrong Sodium/Fabric dependency | Match the exact 26.3 versions above; remove duplicate Voxy/Sodium JARs from the disposable instance. |
| Shader-pack, TAA or VR issue | Disable the unvalidated feature in the test instance. Metal traversal TAA bindings are explicitly unsupported; old-fork Iris/VX feature parity is not retained. |
| Memory pressure or generation failure | Stop optional preparation, preserve the world and logs, and use a smaller disposable workload. Do not blindly allocate most of the machine's 8 GB to Java. |
| Performance evaluation | Disable Metal validation for timing; compare identical camera, framebuffer, terrain data and settings. No fresh FPS claim is made here. |

For diagnostics in an isolated instance, add environment variable `MTL_DEBUG_LAYER=1`. It is for correctness checks, not FPS measurement. Share sanitized logs, exact versions, JAR checksum, framebuffer size and camera/settings; remove account/session tokens and personal paths. Do not share private worlds or launcher account files.

## Remove or roll back

Close Minecraft. In the disposable instance, remove/disable only this experimental JAR and restore that instance's backed-up configuration if needed. Keep world backups. No instructions require overwriting the original Metal source repository or the working launcher instance.
