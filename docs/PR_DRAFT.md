# Local pull request draft — do not publish yet

Proposed title: **Experimental Minecraft 26.3 Metal renderer port with GPU regression coverage**

Proposed base: `srjefers/voxy-mseries-support:experimental/minecraft-26.3`. Proposed head: `udmur1/voxy-mseries-support:contribution/metal-26.3`. The head is published; the proposed upstream base does not yet exist.

Read-only review on 2026-10-16 confirmed that the experimental upstream branch does not exist and the authenticated contributor has pull-only access. An upstream maintainer must create/approve the target; no contributor command should create or modify upstream branches. Recommended starting point: upstream Voxy 26.3 commit `61c1dee5077c73fced865025430c6ce7dc87537c`. If maintainers choose another base, recompute the full PR diff and integration conflicts before publication. Current fork `dev` targets 1.21.11; this candidate must not be presented as a small fix to `dev`.

## Problem and result

Voxy 26.3 relies on rendering/storage APIs and shader layouts that differ from the older Apple Metal fork. This ports the native Metal/GPU foundation onto the 26.3 source, preserving software model baking and introducing a dedicated Metal world renderer with IOSurface/OpenGL final composition.

## Changes

- Native JNI backend, runtime shader compilation and explicit GPU resources/transfers.
- Metal geometry/model uploads, node cleanup and bounded hierarchy traversal, conservative Hi-Z, MDIC bounds/commands/terrain, depth masks, lighting/fog and SSAO.
- World lifecycle integration, frame history/resize and GL state preservation; bounded memory and proportional cleanup reserve for small geometry heaps.
- Optional persisted Chunky generation/import preparation and isolated development tooling; maintainers may prefer a follow-up PR for this scope.
- 25 actual GPU smoke tests with CPU/pixel readback checks, JDK 25/two-worker build instructions and corrected root license packaging.

## Verified tests

On 2026-10-16, Apple M3 / 8 GB / macOS 26.6, Temurin JDK 25.0.4.1: final clean build and all 25 GPU/native tasks passed on snapshot `6748c7c10da1abaf436486efd75fa23abe028f2e`, with both previous Gradle and native build state moved aside. Flags included `MTL_DEBUG_LAYER=1 --no-build-cache --rerun-tasks --max-workers=2`. Result: 34 tasks executed, build successful in 36s; all 25 GPU JVMs confirmed Metal API Validation. Coverage includes production shader/command execution, terrain and final GL pixels, depth/IOSurface sharing, hierarchy/cleanup, atlas/mips, SSAO and maximal draw capacity. JAR inspection verified all six required macOS arm64 native payloads, root license and the 15 collected notice texts. See [test results](https://github.com/udmur1/voxy-mseries-support/blob/contribution/metal-26.3/docs/TESTING.md) and the [technical report](https://github.com/udmur1/voxy-mseries-support/blob/contribution/metal-26.3/TECHNICAL_REPORT.md) for exact artifact identity and remaining limits. Native third-party notice coverage remains incomplete; the owner explicitly authorized experimental publication with this limitation. Subsequent documentation commits do not constitute a new gameplay acceptance run.

## Limitations and integration risks

No fresh gameplay/FPS/long-session acceptance, no other-chip/OS coverage and no new GL/Iris/VR regression acceptance. The older fork's VX/Iris compositor and animated-water implementation are not preserved as equivalent features. Synchronous indirect-command submission limits performance. Traversal TAA bindings remain unsupported. Optional preparation can exceed a 2 GiB heap. Main-source test/development classes and extra native platforms remain packaging cleanup work.

The PR diff against 1.21.11 `dev` includes major upstream 26.3 changes and removals. Review the complete two-dot inventory and agree on version-branch ownership before integration. Prefer an experimental branch over replacing current `dev`.

## Rights and credits

Voxy: MCRcortex and upstream contributors. Metal/GPU/IOSurface foundation: srjefers/voxy-mseries-support and its contributors, snapshot `c544ee8d`. This port adapts that work; credits and the original restrictive notice are retained.

AI-assisted development: Most implementation work was AI-assisted using OpenAI Codex, including code, tests, documentation and contribution preparation. Human review remains necessary; executed tests are explicitly separated from unverified claims.

`LICENSE.md` prohibits redistribution. The owner reports permission from the relevant Voxy/Metal rights holders for redistribution. This does not relicense upstream code. Native third-party notice coverage, Sodium terms and optional GPL/LGPL integration obligations remain under review; the owner subsequently explicitly authorized experimental publication despite the unresolved native notice issue. This draft grants no rights. The [experimental pre-release](https://github.com/udmur1/voxy-mseries-support/releases/tag/metal-26.3-experimental.1) contains the tested JAR and checksum. Links were [shared with srjefers](https://github.com/srjefers/voxy-mseries-support/issues/23#issuecomment-6212634176); a compatible upstream target is awaiting agreement.
