# Infrastructure repair preflight

[`repair_preflight.py`](../../scripts/ci/repair_preflight.py) implements the local planning and
regression checks from [#1952](https://github.com/The-Plum-Team/Quick-Skin-Mod/issues/1952).
Run it before publishing an infrastructure repair or starting its full Build/Packaged E2E gate.
It does not run Gradle, Minecraft, models, GitHub API calls, or workflow dispatches.

First record the intended end state: `repair` means the changed infrastructure and its normal
classified acceptance; `complete-recovery` additionally requires complete optional-mod coverage.
Use exact Git commits for the base, reviewed head, and executing selection policy. Each `--scope`
records one reviewed implementation concern. Keep the output outside the checkout so the plan
does not make its own working tree dirty:

```sh
python3 scripts/ci/repair_preflight.py plan \
  --base BASE_SHA --head HEAD_SHA --policy POLICY_SHA \
  --intent repair --scope 'Repair the protected reviewer checkout.' \
  > /path/to/private-evidence/repair-plan.json
python3 scripts/ci/repair_preflight.py check \
  --plan /path/to/private-evidence/repair-plan.json
```

`plan` records base/head/tree and policy identities, changed paths, the canonical scenario
selection and compatibility classification, observed reusable evidence identities, missing
coverage, required actions, and optional follow-ups separately. Its checksum detects accidental
editing; it is a planning seal, not an authenticated acceptance certificate. `check` requires that
exact clean checkout, recomputes the plan, rejects an unresolved recovery action or evidence read
error, and runs the focused regressions. Uncommitted work can be assessed while implementing, but
cannot be described as evidence for the frozen head. Commit/publication authorization remains the
normal repository workflow requirement.

For complete recovery, a checkout-only repair whose canonical diff is non-impacting and whose
compatible optional evidence is unavailable reports `complete-optional-coverage` and explicitly
requires the existing authorized `e2e/full-validation-baseline.json` marker action. Include an
authorized marker refresh in that same reviewed repair, then regenerate the plan before its gate.
The tool never edits the marker. Routine non-impacting repairs acquire no recovery action. A
runtime-impacting diff already requests its own optional wave through normal protected admission.

If prior optional evidence is available, `--optional-evidence` can record the coordinator's
observation, with this exact schema:

```json
{
  "status": "available",
  "coverage_sha": "the exact planned 40-character head SHA",
  "checked_at": "2026-09-13T08:00:00Z",
  "identities": [{
    "repository": "The-Plum-Team/Quick-Skin-Mod",
    "source_sha": "the original 40-character tested source SHA",
    "run_id": 55,
    "attempt": 1,
    "artifact_id": 300,
    "digest": "sha256:the exact 64-character archive digest"
  }]
}
```

The example values are placeholders, not reusable proof. `unavailable` and `read_error` are
distinct statuses; missing input means unavailable for planning. An `available` observation must
carry immutable identities, but the offline planner cannot authenticate that assertion or establish
live public availability. It always records required live reauthentication. The coordinator must
use the existing canonical optional-evidence collector/carry-forward verifier before relying on
reuse, including repository, contracts, exact source attempts, complete reports, ancestry, artifact
digests, and live public availability. Neither this file nor a passing preflight admits runtime,
review, public evidence, or a complete baseline. No producer or consumer reads this plan to skip
its gates. A `check` success means the local plan is consistent; recorded evidence still needs
that live check immediately before an acceptance decision.

When the implementation head, scope, acceptance intent, or evidence observation changes, pass
`--previous` with the old plan. The new plan records the old checksum/head/tree and which fields
changed. Preserve both files and the original run/attempt evidence. Another tree needs its own
gates or explicitly authenticated reuse; an earlier green matrix does not become validation of
the new tree by changing a label.

The independently usable `focused` command runs the configured sequential-checkout regression
in a fresh Git workspace, verifying the exact reviewer commit and required file bytes. It also
runs the existing real ZIP/digest fan-in regression. Seeded variants reintroduce the historical
sparse checkout and immutable artifact-ID collision with differing ZIP timestamps; each must
fail before the expensive gate while the corrected paths pass:

```sh
python3 scripts/ci/repair_preflight.py focused
python3 -m unittest discover -s scripts/ci/tests -p test_repair_preflight.py -v
```

The command emits separate counts for focused checks, full PR matrices, post-merge reused work,
and fresh recovery waves. Only focused checks are executed here; the other counts are zero.
Retain that result beside the plan. Record subsequent full matrices and recovery/reuse work
separately with exact workflow run/attempt and tested SHA/tree, and combine those actual inventories
when reporting the task. Do not count compilation jobs as matrices, reuse as fresh execution, or
fixture/API operation counts as installation-wide consumption. This preflight preserves all
acceptance/provenance gates and makes no fixed CPU or cost-saving claim.
