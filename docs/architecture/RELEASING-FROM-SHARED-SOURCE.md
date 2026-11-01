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

Publication no longer builds anything. mod-base's shared release (`mod-base-release.yml`, data in
`scripts/ci/mod-base-release.json`) publishes the JARs of the tested generation of `master`'s head:
one dispatch creates one GitHub Release per matrix target, tagged `mc<TARGET>-v<MOD_VERSION>` when
it is published, and uploads every lane to Modrinth and CurseForge. The plan it seals binds each
file to its target, lane and digest, so a target's release can hold only that target's files. See
`RELEASING.md` for the procedure, the ledger and recovery.

During the modular migration, routine image E2E is deferred to GitHub by maintainer direction.
Compilation, local stage verification and workflow dry runs do not certify those images. The
protected selective-review/baseline pipeline and the status/Pages migration are complete; the
remaining migration work is listed in [`MODULAR-REWORK.md`](MODULAR-REWORK.md#remaining-work).
