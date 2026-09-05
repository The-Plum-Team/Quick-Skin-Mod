# Adding a Minecraft target

Shared development lives on `master`. A target selects an API family and loader inputs from the
same source tree and produces one installable JAR per loader. It does not need a permanent version
branch or a second copy of a feature. The ongoing migration status is in
[`MODULAR-REWORK.md`](MODULAR-REWORK.md); release/governance migration is still unfinished.

Start with the complete [`release-matrix.json`](../../release/release-matrix.json). Add the target's
artifact rows, exact runtime/dependency pins and installers, then update its checked Gradle
properties, dependency locks and verification records. Keep exact Minecraft metadata ranges,
Java toolchains, artifact/harness task paths and runtime policies consistent. The validator checks
the entire inventory even when a build selects just one target.

Select the existing compatibility family first. Native texture/model/GUI operations belong to
`minecraft-adapter`; preview rendering and payload transport have their own family implementations.
Features consume those APIs through the module dependencies declared in
[`modules.json`](../../architecture/modules.json). If compilation exposes a new native signature,
adapt the owning compatibility boundary and check its other consumers. Avoid copying an entire
feature or introducing a feature-to-assembly dependency.

An overlay is appropriate for a bounded loader or native API replacement that cannot share the
canonical implementation. Declare its route in the matrix and share the directory across every
compatible target. Resources may have their own replacement without a Java copy. For example, the
NeoForge Architectury bridge uses one `legacy26_1` directory for two targets, including tests of
the pinned upstream bytecode. Its classes and mixin configuration are absent from later targets.

Inspect the resulting plan and build one target while iterating:

```bash
python3 scripts/release/matrix.py --matrix release/release-matrix.json
python3 scripts/release/build_matrix.py --target 26.2 --plan
python3 scripts/release/build_matrix.py --target 26.2
python3 scripts/release/verify_release.py --target 26.2 \
  --stage build/target --manifest build/target/artifacts.json
```

Replace `26.2` with the target being added. On Windows, use `python`; the coordinator selects
`gradlew.bat`. A scoped build compiles only that target's Minecraft modules and loaders, plus the
shared Java modules and their tests. Its result explicitly records partial coverage.

After reconciling shared changes, build and verify the complete matrix:

```bash
python3 scripts/release/build_matrix.py --clean
python3 scripts/release/verify_release.py \
  --stage build/release --manifest build/release/artifacts.json
python3 scripts/release/verify_release.py --verify-staged \
  --stage build/release --manifest build/release/artifacts.json
```

The coordinator starts each Gradle process sequentially and checks every expected JAR. It does
not launch Minecraft. For a reproducibility gate, rebuild with `--rerun-tasks`, then compare the
new outputs against the first manifest with `scripts/release/verify_reproducibility.py`.

Regenerate the matrix-owned README, E2E README and workflow guidance profiles with their
`scripts/release/` generators. Keep deterministic scenarios and capture prerequisites in
[`scenario-contract.json`](../../e2e/scenario-contract.json); consumers derive their lane sets from
the matrix. During this migration, routine image E2E runs on GitHub after the imports are ready.
Compilation and packaging do not stand in for that runtime evidence. Selective CI admission and
reuse of unaffected feature evidence remain part of the migration, so existing protected gates
continue to require their complete profiles.
