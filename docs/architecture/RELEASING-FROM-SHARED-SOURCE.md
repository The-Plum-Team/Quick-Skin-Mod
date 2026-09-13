# Releases from shared source

`release/release-matrix.json` owns supported Minecraft targets, loader artifacts and runtime pins.
All targets build from the same source branch. Each target retains its independent release tag,
`mc<TARGET>-v<MOD_VERSION>`, and marketplace publication identities. The aggregate
`build-v<MOD_VERSION>` bundle is for verification and cannot be published as a target release.

For example, local validation of the declared 1.21.8 target is:

```sh
python3 scripts/release/release_identity.py --target 1.21.8
python3 scripts/release/build_matrix.py --target 1.21.8 --clean
python3 scripts/release/verify_release.py --target 1.21.8
python3 scripts/release/build_matrix.py --target 1.21.8 --rerun-tasks
python3 scripts/release/verify_reproducibility.py --target 1.21.8
python3 scripts/release/rehearse_publication.py --stage build/release
python3 e2e/orchestrator.py --target 1.21.8 --list
```

Run Gradle through the serial coordinator. A target run is explicitly partial; omit `--target`
from the build/stage commands for the complete matrix build bundle. Derived target views never
replace the authoritative matrix and retain its full SHA-256 identity in staged evidence.

The **Release** workflow has two entry paths:

- A manual dispatch from `master` requires `minecraft_target`. It builds, rebuilds, compares and
  runs the target's full packaged release scenarios and the offline publication/recovery simulation.
  Require this rehearsal on the intended source before tagging. Manual dispatch remains validation-only.
- A canonical tag push derives the target from that exact tag and the matrix's current mod
  version. It rejects overrides, unknown targets and stale mod versions. Publication still
  requires the exact source-branch head, a dated changelog and the existing protected jobs.

The resolved target reaches artifact and harness staging, reproducibility, runtime matrix rows,
the packaged-runtime action and every later staged-bundle verification. Runtime consumers also
bind the source commit and selected release identity. Supplying another target cannot relabel a
downloaded bundle. GitHub and marketplace publication receive only the selected target's files.

`release_schedule.py` derives one of four preparation slots from the complete matrix. Build jobs
and the two runtime loader families have separate bounded queues on isolated runners. Only the
publication writer jobs share `release-publish`; every queue preserves pending runs with `queue: max`.

`publication_state.py` binds upload intent to the tag, original source, producer run, immutable
archive ID/digest and manifest digest. Its versioned hidden draft-body comment contains the exact
matrix-derived row inventory. Writers reread the body under the shared lock before patching it and
confirm the saved state before authorizing an upload. This preserves the five-asset GitHub release
contract and survives runner loss without a second mutable branch or expiring queue artifact.

`verify_pending_publications.py` is a read-only observer until protected finalization is approved.
It runs current protected code, authenticates original build/E2E evidence and tag ancestry, downloads
the exact retained archive, and revalidates its production JARs, harnesses and SBOM against inert
source metadata. It never uploads a marketplace file. Pending moderation ends the probe successfully;
missing evidence, replaced bytes or changed remote IDs fail closed. The final writer rechecks the
same evidence and all marketplace rows before publishing GitHub. See `RELEASING.md` for retention,
ambiguous-response recovery and the distinction between upload completion and public availability.

During the modular migration, routine image E2E is deferred to GitHub by maintainer direction.
Compilation, local stage verification and workflow dry runs do not certify those images. The
protected selective-review/baseline pipeline and release governance/status/Pages migration are
still separate unfinished parts of `MODULAR-REWORK.md`.
