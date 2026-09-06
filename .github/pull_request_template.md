<!--
Read CONTRIBUTING.md and every document imported by AGENTS.md before completing this template.
Delete instructional comments, but keep every heading. Use "Not applicable" where appropriate.
-->

## Summary

<!-- What changed, and why is it needed? Keep this understandable without reading the diff. -->

## Scope

- Base branch:
- Minecraft version(s):
- Loader(s):
- Shared change or version-only change:
- Feature modules/API bindings affected:
- Matrix targets affected and any explicit exclusions:
- Related issue:

## Implementation notes

<!--
Identify canonical files, active overlays, compatibility adapters, protocol/storage boundaries, or
release-matrix rows affected by this change. Explain important tradeoffs and anything intentionally
left unchanged.
-->

## Validation

List every command run and its result:

```text
command -> result
```

- [ ] `git diff --check` passes.
- [ ] The smallest relevant unit/build check passes.
- [ ] The aggregate build was run when build, loader, overlay, resource, or release behavior changed.
- [ ] I documented checks that were not run and why.

## Repository safety

- [ ] I edited canonical sources or active overlays, not generated output.
- [ ] I checked every active overlay that replaces an affected canonical file.
- [ ] I did not commit jars, Minecraft runtime files, screenshots, caches, secrets, or IDE output.
- [ ] I did not weaken assertions, bounds, validation, or CI gates to make the change pass.
- [ ] Any support or loader change starts in `release/release-matrix.json`.
- [ ] I checked the affected module consumers, runtime bindings, and matrix targets, and documented
      each intentional exclusion.
- [ ] When behavior, build instructions, source layout, or compatibility facts changed, the README
      remains accurate for the affected matrix targets; generated README blocks were not hand-edited.
- [ ] Any inspection or edit of a different branch used a separate ephemeral worktree, and no
      dirty or user-owned worktree was force-removed.

## AI assistance

- Material AI assistance: <!-- None, implementation, tests, review, documentation, etc. -->
- Tool/model, if used:
- What I reviewed manually:

<!-- Never paste private prompts, credentials, tokens, or user data. -->

## License acknowledgement

- [ ] I have read [LICENSE](../LICENSE) and agree that this contribution is submitted under its
      contribution terms.
