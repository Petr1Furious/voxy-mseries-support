# Proposed pre-release: metal-26.3-experimental.1

**LOCAL DRAFT — PUBLICATION BLOCKED BY INCOMPLETE THIRD-PARTY NOTICE COVERAGE.**

EXPERIMENTAL / UNOFFICIAL / NOT an official Voxy release. This port adapts the native Apple Metal LOD renderer to Minecraft 26.3; Minecraft and the final IOSurface compositor still use OpenGL. Most implementation work was AI-assisted using OpenAI Codex.

Target: macOS Apple Silicon, JDK 25, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3 and Sodium mc26.3-0.9.3-alpha.1-fabric. Only Apple M3 / 8 GB / macOS 26.6 has current GPU component-test evidence. See [Prism installation](INSTALLATION.md), [test results](TESTING.md) and the [technical report](../TECHNICAL_REPORT.md).

No fresh gameplay, FPS, other-chip/OS or long-duration acceptance is claimed. Iris/VX and animated-water parity with the older fork are not established; TAA/VR are unsupported or unvalidated. Synchronous GPU submissions and memory pressure remain limitations. Use disposable instances/worlds and preserve backups. No production-readiness claim is made.

The local candidate JAR and `SHA256SUMS` are staged outside tracked source. Final artifact: `voxy-metal-0.2.20-metal.26.3.experimental.1-6748c7c.jar`, SHA-256 `52b88918d3cfa8b52c4475d67340bd32ab42a6101008fed58098873f38dffd39`. Clean build and 25 GPU/native tests passed with Metal API Validation; all six required arm64 native payloads were verified. Full artifact identity and limitations are in the testing report. No binary should be uploaded until required notices are resolved and the owner confirms the exact account/repository destinations. The owner reports Voxy/Metal redistribution permissions; original restrictive notices and third-party rights remain intact.

Credits: MCRcortex and Voxy contributors; srjefers and voxy-mseries-support contributors; all third-party dependency authors. No endorsement is implied.
