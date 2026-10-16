# Experimental pre-release: metal-26.3-experimental.1

**EXPERIMENTAL PRE-RELEASE. Native third-party notice coverage remains incomplete.**

EXPERIMENTAL / UNOFFICIAL / NOT an official Voxy release. This port adapts the native Apple Metal LOD renderer to Minecraft 26.3; Minecraft and the final IOSurface compositor still use OpenGL. Most implementation work was AI-assisted using OpenAI Codex.

Target: macOS Apple Silicon, JDK 25, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3 and Sodium mc26.3-0.9.3-alpha.1-fabric. Only Apple M3 / 8 GB / macOS 26.6 has current GPU component-test evidence. See [Prism installation](https://github.com/udmur1/voxy-mseries-support/blob/contribution/metal-26.3/docs/INSTALLATION.md), [test results](https://github.com/udmur1/voxy-mseries-support/blob/contribution/metal-26.3/docs/TESTING.md) and the [technical report](https://github.com/udmur1/voxy-mseries-support/blob/contribution/metal-26.3/TECHNICAL_REPORT.md).

No fresh gameplay, FPS, other-chip/OS or long-duration acceptance is claimed. Iris/VX and animated-water parity with the older fork are not established; TAA/VR are unsupported or unvalidated. Synchronous GPU submissions and memory pressure remain limitations. Use disposable instances/worlds and preserve backups. No production-readiness claim is made.

The local candidate JAR and `SHA256SUMS` are staged outside tracked source. Final artifact: `voxy-metal-0.2.20-metal.26.3.experimental.1-6748c7c.jar`, SHA-256 `52b88918d3cfa8b52c4475d67340bd32ab42a6101008fed58098873f38dffd39`. Clean build and 25 GPU/native tests passed with Metal API Validation; all six required arm64 native payloads were verified. Full artifact identity and limitations are in the testing report. The owner explicitly authorized publication to udmur1/voxy-mseries-support despite the remaining native notice issue. This does not establish complete third-party notice compliance. The owner reports Voxy/Metal redistribution permissions; original restrictive notices and third-party rights remain intact.

Credits: MCRcortex and Voxy contributors; srjefers and voxy-mseries-support contributors; all third-party dependency authors. No endorsement is implied.

The JAR was built and tested at source commit `6748c7c10da1abaf436486efd75fa23abe028f2e`. Later branch/tag commits update documentation only, including the owner's publication authorization. Embedded audit wording reflects the earlier pre-publication snapshot.
