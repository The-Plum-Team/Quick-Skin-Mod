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
python3 e2e/orchestrator.py --target 1.21.8 --list
```

Run Gradle through the serial coordinator. A target run is explicitly partial; omit `--target`
from the build/stage commands for the complete matrix build bundle. Derived target views never
replace the authoritative matrix and retain its full SHA-256 identity in staged evidence.

The **Release** workflow has two entry paths:

- A manual dispatch from `master` requires `minecraft_target`. It builds, rebuilds, compares and
  runs the target's full packaged release scenarios. Manual dispatch remains validation-only.
- A canonical tag push derives the target from that exact tag and the matrix's current mod
  version. It rejects overrides, unknown targets and stale mod versions. Publication still
  requires the exact source-branch head, a dated changelog and the existing protected jobs.

The resolved target reaches artifact and harness staging, reproducibility, runtime matrix rows,
the packaged-runtime action and every later staged-bundle verification. Runtime consumers also
bind the source commit and selected release identity. Supplying another target cannot relabel a
downloaded bundle. GitHub and marketplace publication receive only the selected target's files.

During the modular migration, routine image E2E is deferred to GitHub by maintainer direction.
Compilation, local stage verification and workflow dry runs do not certify those images. The
protected selective-review/baseline pipeline and release governance/status/Pages migration are
still separate unfinished parts of `MODULAR-REWORK.md`.
