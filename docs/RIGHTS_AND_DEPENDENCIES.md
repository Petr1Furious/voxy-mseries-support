# Rights, notices and dependency review

Review date: 2026-10-16. This is a local evidence review, not a grant of rights or complete transitive legal clearance.

## Project rights

The checked `LICENSE.md` blobs in upstream 26.3 and Metal fork `c544ee8d` retain the same notice: “All rights reserved. Do not redistribute.” The Fabric metadata declares All-Rights-Reserved. Imported native/GPU additions have no separately established permissive grant. The owner now reports that relevant rights holders have granted redistribution permissions covering this port. Preserve the original notice and credits; this report does not reproduce the agreement or infer a new license for recipients. Public visibility and GitHub fork ancestry do not establish that permission.

The contribution fixes the missing root notice in local JARs: Gradle previously read nonexistent `LICENSE`; it now packages `LICENSE.md` as `LICENSE.md_voxy`. Its bytes were checked against the source. This correction does not resolve redistribution authorization.

## Direct dependencies

Versions and bundle status below come from `build.gradle`, `gradle.properties`, cached POMs and the freshly built local JAR. Prior launcher reports were not used as fresh artifact evidence.

| Dependency | Role / version | Checked evidence and outstanding obligations |
| --- | --- | --- |
| LWJGL lmdb/zstd/shaderc/spvc | Bundled Java/native modules, 3.4.3 | Cached LWJGL POM: BSD-3-Clause. Preserve its license and applicable native notices; bindings do not license bundled native implementations. |
| shaderc / SPIRV-Cross | Native compiler components through LWJGL | Project licenses are Apache-2.0; exact native revisions, compiler subcomponents and full notice set are not reconstructed. |
| LMDB / Zstandard | Native storage/compression through LWJGL | Project licenses are OpenLDAP Public License / BSD. Exact embedded revisions and notices require verification. |
| Jedis | Bundled, 5.1.0 | Cached POM: MIT; include copyright/permission notice before distribution. |
| Commons Pool | Bundled, 2.12.0 | Nested JAR includes Apache license and NOTICE files; preserve both. |
| RocksDB JNI | Filtered bundled JAR, 10.2.1 | Cached POM lists Apache-2.0 and GPLv2 alternatives. Verify Apache route and native third-party notices. Filtering retains macOS arm64 JNI but also other platform binaries. |
| lz4-java | Bundled, 1.8.0 | Cached POM: Apache-2.0; native notice coverage remains to be verified. |
| XZ for Java | Bundled, 1.10 | Cached POM: 0BSD. Do not generalize this to unrelated compression code. |
| SQLite JDBC | Development runtime, 3.49.1.0 | Cached POM: Apache-2.0; absent from nested JAR inventory. |
| Fabric Loader / Fabric API | External, 0.19.5 / 0.160.5+26.3 | Apache-2.0 project licensing; not nested. Retain required notices if later distributing their code. |
| Sodium | External, mc26.3-0.9.3-alpha.1-fabric | PolyForm Shield 1.0.0; required notices and noncompete terms need review. A separate platform or free distribution does not by itself settle applicability. No compatibility/legal clearance is claimed. |
| Chunky | Optional preparation integration, Modrinth version ID 4Eotm6ov | GPLv3 project license. Compile-only use and direct API integration do not establish compatibility with Voxy's restrictive license; combined-work/source obligations remain unresolved. Not nested. |
| Mod Menu / Lithium / Iris | Development dependencies | MIT / LGPL-3.0-only / LGPL-3.0-only according to prior cached metadata review. Not nested; source/notice obligations need checking for any pack. |
| Vivecraft / Flashback / Nvidium / glsl-transformer | Compile-only compatibility integrations; see pinned build properties | Not nested in this candidate. Their complete license and combined-work review is still outstanding; do not describe compile-only dependencies as license-exempt. |
| Minecraft / Apple frameworks | External game and system APIs | No game JAR, worlds, credentials or Apple framework binaries are included. Separate game/SDK terms remain applicable. |

## Actual packaging findings

The tested artifact contains 18 nested JARs: five storage/support JARs (Jedis, Commons Pool, RocksDB JNI, lz4-java, XZ), twelve LWJGL JARs, and one generated `dummyprovider.jar` metadata shim. The shim is not Sodium's implementation. LWJGL LMDB/Zstd include Java plus macOS arm64/Linux x64/Windows x64 native variants; shaderc/spvc include Java plus macOS arm64 natives. Unused platform binaries and development/test classes remain packaging cleanup candidates.

Only Commons Pool's nested JAR exposes standalone LICENSE/NOTICE entries by filename. Other JARs lacking such entries require a distribution notice bundle with exact upstream texts and native provenance. POM metadata alone is insufficient. The current local packaging now includes verified notice files under `META-INF/third-party`, with provenance in `third-party/SOURCES.json`. This addresses the standalone files collected so far but does not establish complete native/transitive coverage. No binaries were uploaded.

## Source links

- [Pinned Metal fork license](https://github.com/srjefers/voxy-mseries-support/blob/c544ee8d3ee9d56788f517ca7157bc24956d5368/LICENSE.md) (local Git blob inspected; remote raw fetch unavailable).
- [Sodium license](https://github.com/CaffeineMC/sodium/blob/dev/LICENSE.md), [Chunky license](https://github.com/pop4959/Chunky/blob/master/LICENSE) (project pages checked during preparation; moving branches are not substitutes for exact artifact review).
- [LWJGL](https://www.lwjgl.org/license), [shaderc](https://github.com/google/shaderc/blob/main/LICENSE), [SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross/blob/main/LICENSE), [LMDB](https://github.com/LMDB/lmdb/blob/mdb.master/libraries/liblmdb/LICENSE), [Zstandard](https://github.com/facebook/zstd/blob/dev/LICENSE).

The owner explicitly authorized experimental publication despite the remaining missing third-party notices/native provenance. Preserve that limitation in release documentation. Owner-reported Voxy/Metal permissions are accepted separately from dependency obligations. No license text was relaxed or replaced.

## Current release gate

The original no-notices artifact findings above are historical baseline evidence. New local builds package collected texts for LWJGL, LMDB, Zstandard, shaderc, SPIRV-Cross, SPIRV-Tools, glslang, Jedis, Commons Pool, RocksDB/LevelDB, lz4-java and XZ. Exact sources and hashes are tracked. The actual macOS SPIRV-Cross binary embeds commit `6328376`; SPIRV-Tools embeds `b707790a`, and glslang identifies 16.4.0. Their corresponding texts were collected.

Still unresolved: full compiler stack dependency/copyright coverage (including the precise SPIRV-Headers source revision and notices), and complete transitive native notices inside RocksDB/compression payloads. The optional direct GPL/LGPL integration questions remain documented above. A generic Apache/BSD text is not proof that all required third-party notices are retained. The owner subsequently explicitly authorized publication despite this issue. It remains open and no complete third-party compliance claim is made.
