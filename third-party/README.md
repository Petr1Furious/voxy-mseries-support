# Third-party notices — partial, release blocked

This directory preserves verbatim license/notice texts from fixed upstream versions. `SOURCES.json` records source URLs, Git blob IDs when available and SHA-256 hashes. Commons Pool texts were extracted from the exact nested 2.12.0 JAR. Gradle includes this directory in `META-INF/third-party` in local builds.

Coverage: LWJGL 3.4.3 BSD notice; LMDB, Zstandard and shaderc license files distributed in the LWJGL 3.4.3 source tag; Jedis 5.1.0; lz4-java 1.8.0; XZ for Java 1.10; RocksDB 10.2.1 Apache/GPL alternative texts and LevelDB notice; Commons Pool 2.12.0 license and NOTICE. Including alternative texts does not assert that the combined port is GPL licensed. The Apache licensing route for RocksDB still requires its native third-party coverage to be checked.

**This is not a complete distribution notice bundle.** Exact native dependency revisions and full copyright/notice texts for the prebuilt shaderc/SPIRV-Cross compiler stack, and transitive components inside RocksDB/lz4/native libraries, have not been reconstructed. The generic shaderc Apache text is not a substitute for glslang/SPIRV-Headers/other compiler notices. SPIRV-Cross notice coverage is still missing. Do not infer completeness from the presence of license text or a successful build.

The owner reports permission from the relevant Voxy/Metal rights holders for this contribution. That permission is separate from these third-party obligations. Public release remains blocked until missing native provenance/notices and the direct optional GPL/LGPL integration questions in `docs/RIGHTS_AND_DEPENDENCIES.md` are resolved.
