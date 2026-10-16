# Publication status — 2026-10-16

## Published

- Account confirmed by GitHub API: **udmur1**, ID 343017051.
- Fork: https://github.com/udmur1/voxy-mseries-support (parent: srjefers/voxy-mseries-support).
- Published/default branch: contribution/metal-26.3.
- Experimental pre-release: https://github.com/udmur1/voxy-mseries-support/releases/tag/metal-26.3-experimental.1
- Shared with srjefers: https://github.com/srjefers/voxy-mseries-support/issues/23#issuecomment-6212634176

The owner explicitly authorized the fork, source push, JAR pre-release and sharing the link, including proceeding despite incomplete native notice coverage. No complete third-party notice-compliance claim is made. The relevant Voxy/Metal redistribution permissions were reported obtained by the owner; original notices remain preserved.

The released JAR was built/tested at source commit 6748c7c10da1abaf436486efd75fa23abe028f2e. Release tag metal-26.3-experimental.1 points to documentation-updated commit f684d18e87ede7df8a758c35f09f5a3369274408. Later branch commits update publication documentation without changing the tested runtime code or release assets.

JAR SHA-256: `52b88918d3cfa8b52c4475d67340bd32ab42a6101008fed58098873f38dffd39`. GitHub reported the same SHA-256 for the uploaded 20,706,611-byte asset. SHA256SUMS was also uploaded; the release is public, prerelease=true and draft=false.

## Contribution strategy

No PR was opened against the incompatible 1.21.11 dev branch. The checked upstream branch list contains no Minecraft 26.3 target. The comment to srjefers proposes experimental/minecraft-26.3 for a follow-up PR; wait for an agreed compatible base. Upstream access remains pull-only. No upstream branch was modified.

## Preserved state and remaining limitations

No force-push, history rewrite, repository deletion or working installation change occurred. Existing origin and metal-source remotes remain unchanged; contribution-fork points explicitly to the new personal fork. GitHub Actions is disabled in the personal fork, preventing inherited build/upload workflows from running on these pushes. The personal fork's default branch is contribution/metal-26.3 so readers see the experimental 26.3 port.

Native/transitive notice coverage remains incomplete and disclosed in third-party/README.md and the release notes. No fresh manual gameplay, FPS, long-duration, Iris/VR or other-chip acceptance was performed. Original source/Metal checkouts and the working Prism installation were preserved. Commit identity history is retained, including earlier local-hostname emails; GitHub authentication does not rewrite authorship.

See docs/TESTING.md for the fresh clean build, 25 GPU/native test results and exact native packaging checks.
