# Local pull request draft — do not publish yet

Proposed title: **Experimental Minecraft 26.3 Metal renderer port with GPU regression coverage**

Proposed target: a maintainer-approved experimental Minecraft 26.3 branch. Current fork `dev` targets 1.21.11; this candidate is based on upstream `263` (`61c1dee5`) and must not be presented as a small fix to `dev`.

## Problem and result

Voxy 26.3 relies on rendering/storage APIs and shader layouts that differ from the older Apple Metal fork. This ports the native Metal/GPU foundation onto the 26.3 source, preserving software model baking and introducing a dedicated Metal world renderer with IOSurface/OpenGL final composition.

## Changes

- Native JNI backend, runtime shader compilation and explicit GPU resources/transfers.
- Metal geometry/model uploads, node cleanup and bounded hierarchy traversal, conservative Hi-Z, MDIC bounds/commands/terrain, depth masks, lighting/fog and SSAO.
- World lifecycle integration, frame history/resize and GL state preservation; bounded memory and proportional cleanup reserve for small geometry heaps.
- Optional persisted Chunky generation/import preparation and isolated development tooling; maintainers may prefer a follow-up PR for this scope.
- 25 actual GPU smoke tests with CPU/pixel readback checks, JDK 25/two-worker build instructions and corrected root license packaging.

## Verified tests

On 2026-10-16, Apple M3 / 8 GB / macOS 26.6, Temurin JDK 25.0.4.1: fresh-worktree build passed, followed by forced uncached `build` plus all 25 GPU tasks with `MTL_DEBUG_LAYER=1`. Result: 33 tasks executed, build successful; all 25 GPU JVMs confirmed Metal API Validation. Coverage includes production shader/command execution, terrain and final GL pixels, depth/IOSurface sharing, hierarchy/cleanup, atlas/mips, SSAO and maximal draw capacity. See `docs/TESTING.md` for exact commands and artifact identity. Root license/native embedding checks passed.

## Limitations and integration risks

No fresh gameplay/FPS/long-session acceptance, no other-chip/OS coverage and no new GL/Iris/VR regression acceptance. The older fork's VX/Iris compositor and animated-water implementation are not preserved as equivalent features. Synchronous indirect-command submission limits performance. Traversal TAA bindings remain unsupported. Optional preparation can exceed a 2 GiB heap. Main-source test/development classes and extra native platforms remain packaging cleanup work.

The PR diff against 1.21.11 `dev` includes major upstream 26.3 changes and removals. Review the complete two-dot inventory and agree on version-branch ownership before integration. Prefer an experimental branch over replacing current `dev`.

## Rights and credits

Voxy: MCRcortex and upstream contributors. Metal/GPU/IOSurface foundation: srjefers/voxy-mseries-support and its contributors, snapshot `c544ee8d`. This port adapts that work; credits and the original restrictive notice are retained.

AI-assisted development: OpenAI Codex and AI tools helped implement/review code, tests and documentation and prepare this contribution. Human review remains necessary; executed tests are explicitly separated from unverified claims.

`LICENSE.md` prohibits redistribution. Permission for contributing modified source/patches and any later binaries must be resolved with relevant rights holders; native third-party notices, Sodium terms and optional GPL/LGPL integrations are not cleared. This draft is local, grants no rights, and includes no uploaded artifacts.
