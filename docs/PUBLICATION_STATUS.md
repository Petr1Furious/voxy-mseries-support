# Publication status — 2026-10-16

The owner explicitly authorized creation of udmur1/voxy-mseries-support, upload of contribution/metal-26.3, an experimental JAR pre-release, and sharing its link with srjefers. Publication was not yet performed at this documentation snapshot. No upstream branch, original source checkout or working Prism installation was modified. The contribution branch has only additive local commits; no history was rewritten.

## Confirmed account and remotes

GitHub API authenticated as **udmur1**, ID 343017051. Upstream srjefers/voxy-mseries-support grants pull=true, push=false, maintain=false and admin=false. Its default branch is dev (Minecraft 1.21.11); no Minecraft 26.3 target exists in the checked branch list. The proposed personal fork returned 404 during this audit.

Existing remotes remain unchanged: origin → https://github.com/MCRcortex/voxy.git; metal-source → the original local Metal repository. Do not use an implicit `git push` to origin.

## Proposed destinations — not yet created

- Fork repository: https://github.com/udmur1/voxy-mseries-support
- Source branch: contribution/metal-26.3
- Proposed pre-release tag: metal-26.3-experimental.1
- Proposed release URL after publication: https://github.com/udmur1/voxy-mseries-support/releases/tag/metal-26.3-experimental.1
- Proposed PR base, only if created/approved by the upstream owner: srjefers/voxy-mseries-support:experimental/minecraft-26.3. Recommended baseline is 61c1dee5077c73fced865025430c6ce7dc87537c; if another base is selected, recompute integration diff/conflicts.

The shared Git ancestry makes a fork appropriate even though the architecture/version differs. Keep the experimental version branch isolated. Do not open an incompatible PR against 1.21.11 dev and do not create/modify upstream branches. A future separate repository is unnecessary unless maintainers or GitHub fork-network constraints require it.

## Blocking conditions

The owner explicitly reports Voxy/Metal redistribution permissions obtained; no repeat confirmation of that statement is requested. However, exact full compiler/native/transitive notice coverage remains unresolved (see third-party/README.md and docs/RIGHTS_AND_DEPENDENCIES.md). The local JAR contains the verified notices collected so far, not a certified complete bundle. The owner subsequently explicitly waived this stop and authorized publication despite the outstanding notice issue. No complete third-party compliance claim is made.

The owner confirmed account udmur1 and destination udmur1/voxy-mseries-support in the publication instruction. Recheck authentication before publication. Fork/source/release operations must use explicit repository and ref names; no force push, tag overwrite or upstream writes. Release assets should be the single verified JAR and SHA256SUMS, not a wildcard over native-inspection files. Do not launch the inherited manual-artifact workflow.

Git commit identity currently includes a local machine hostname in its email. History is preserved as requested; changing the GitHub authentication account does not change existing commit authors. Resolve desired future author metadata separately without rewriting these commits.

The code/test candidate is 6748c7c10da1abaf436486efd75fa23abe028f2e; subsequent result-recording changes affect documentation and notice-formatting attributes only. See docs/TESTING.md for the checksum and precise build/GPU results. No new manual gameplay or performance acceptance was performed.
