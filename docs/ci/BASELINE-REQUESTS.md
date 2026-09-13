# Complete-baseline request coalescing

`feature_coverage_request.py` is a scheduling gate, not a certificate issuer. Review and
Pages producers execute it in their existing final request jobs, sharing the short
`quick-skin-feature-baseline-request-<protected SHA>` lock with `queue: max`. The actual
collector retains its separate non-cancelling generation lock and complete authentication.

## Admission and recovery

Each invocation checks the exact repository, protected SHA, successful runtime source and
current attempt. Selected runtime sources cannot request complete certification. It checks
one public archive, then interleaves exact target report/public inventories, stopping at the
first gap. Complete readiness also requires terminal owners, except the authenticated requesting
producer's own in-progress final job. Terminal recovery has no owner-settlement exception.
The review tail also waits for the capacity-resume sibling, so it cannot
outlive the collector's short owner-settlement wait. That prevents a collector from inspecting
reports while sibling producers are still waiting for the request lock. Owner metadata is reused
only inside that invocation; no runtime job, report archive or image is admitted by the readiness check.

An already active exact source/attempt collector suppresses another POST. After POST, the
request lock is retained for at most 30 seconds until a new exact collector is observable,
including one that has already completed. A successful historical workflow alone is not a
deduplication flag. Suppression after completion reuses the canonical existing-certificate
path: issuer, immutable certificate bytes, exact policy/contracts/Git ancestry, complete
target inventory, currently available public archives and the live runtime graph all remain
mandatory. It does not reassemble or download the sixteen normalized reports.

Cancelled-owner reports are preserved. If recovery leaves several same-name artifacts, only
an authenticated terminal cancelled protected owner can be omitted from candidate selection.
There must still be exactly one eligible report, and the collector fully validates it.
Malformed/foreign metadata and API failures are errors, never evidence absence.

Public readiness follows the collector's newest-ID-first eight-candidate window, counting
expired entries before skipping them. A newer terminal failed/cancelled/timed-out Pages
publication blocks an older successful candidate: the collector would reject that ordering,
so readiness does not start it. An admissible newer successful publication is needed;
this gate does not introduce an older-public fallback or delete failed publication evidence.

Only an in-progress producer tail forwards its exact owner for the collector to wait on.
A completed success/failure/cancelled/timed-out producer instead nominates the exact runtime
source ID and attempt, leaving the trigger-owner field empty. A cancelled report can thereby
wake certification of its authenticated replacement without being admitted as that replacement's
owner. Failed or timed-out public producers similarly need an admissible successful successor;
missing required evidence or a still-running required replacement cannot start collection. The CLI reuses its initial
producer-owner read only within that invocation and rejects malformed or foreign wake owners.
The collector checks the requested source ID and attempt, runs the unchanged full admission
and rechecks live `master` and that source's current attempt before publishing.
Ordinary final-review or final-publication notifications therefore need no debounce timer:
collection begins as soon as that last producer/request job and the collector runner are
available. GitHub runner startup is not a hard real-time guarantee.

`feature-coverage-request.yml` handles failed/cancelled/timed-out producer `workflow_run`
completion, explicit manual recovery and an hourly recovery tick. It rediscovers only successful
runtime sources for the exact current SHA, skips selected sources and reconciles the newest
complete source; it never launches Minecraft or a model. Lost notifications,
cancelled collectors and certificate/public-artifact withdrawal thus have a bounded recovery
opportunity (up to 60 minutes plus GitHub scheduling/runner delay). A source still missing
required evidence remains unissued; API failures stay visible. Recovery does not recursively
dispatch itself. The hourly sweep has real steady-state runner/API cost and is reported
separately from producer-burst savings. Job-level event guards exclude successful producer
completion echoes, generic drain sweeps, Pages repository wakes and foreign/stale executions
before assigning a runner. Successful producers have already executed their request tail.
If that continue-on-error tail fails, its otherwise-successful owner does not receive an
immediate second check: hourly/manual recovery provides the documented bounded opportunity.

## Verification and measurement

The saved `baseline-readiness-2026-09-09.json` fixture contains the original 24 staggered
notifications: sixteen review completions and eight public publications. The production
scheduler replay starts one collector rather than 24: twenty incomplete notifications are
suppressed, the last review starts certification, and three later public notifications
reauthenticate the available certificate. A concurrent-owner test retains every pending tail
and starts collection only from the last one. Reversed review/public completion order,
expired/withdrawn evidence, a failed collector, head/attempt advancement, API failures,
duplicate/foreign reports and post-dispatch visibility races have separate regressions. A real-Git
cancelled-producer/replacement regression runs through source resolution, request dispatch and
complete canonical collection: the cancelled original remains inadmissible, while the distinct
valid replacement supplies its target in the exact sixteen-target, 2,880-frame baseline.

These are deterministic replay results, not live quota or billing measurements. The full
real-Git fixture replay of current pre-scheduler behavior versus the actual new scheduler CLI
records 482 versus 546 bounded GET-equivalent operations (+64, 13.3%), and 24 versus one POST. Both
paths download sixteen normalized reports plus three existing-certificate ZIPs (19), and
assemble exactly one complete certificate. The extra reads pay for current readiness,
owner settlement and observable collector ownership; the measured improvement here is
23 fewer collector workflow/job starts, not a claim that every API category decreases.
Action checkout/runtime setup now occurs in the existing producer jobs, so eliminating
collector starts does not eliminate all of their former setup work. Hourly recovery and
its idle runner/API overhead, and checks after failed producer completion, are additional
and not included in that producer-tail replay. Its producer owners are already terminal:
456 request GETs plus 90 collector GETs include each CLI's initial current-head guard.
Earlier helper-only/CLI totals were 525/549 before terminal wakes stopped forwarding a
trigger-owner exception. The revised CLI reuses its existing owner read; its collector avoids
three redundant terminal-trigger owner/artifact/job reads. An in-progress own tail still
requires the original collector trigger authentication and any bounded settlement polls;
this terminal fixture does not measure those waits or extra polling reads. A separate sensitivity
keeps only the final notifying review tail in progress and settles it before collector admission:
it measures 549 GETs (456 request plus 93 collector), again one collector and 19 ZIPs. The other
notifications remain terminal in that sensitivity; it measures neither settlement polls nor
runner/request-lock delay. Thus the three-read reduction is specific to terminal wake routing,
not a universal saving for normal in-progress producer tails.

An earlier design also checked every successful producer completion. Before the terminal-wake
routing correction, executing that CLI one second after each saved tail added 403 GETs and
24 recovery starts, for 952 GETs and 25 collector/recovery jobs total; a three-minute sensitivity
gave 982 GETs. Those
completion offsets were modeled, not observed. That design was rejected because it
replaced 23 avoided collector starts with 24 redundant recovery starts. The final event
guard drops all successful echoes before runner allocation; bounded hourly recovery remains.

On a healthy real-Git certificate fixture, the actual recovery CLI and bounded API adapter
use 33 GETs, including one certificate ZIP download, per hourly reconciliation. That is
24 jobs and 792 GETs/day before failed producer completions. Successful empty hourly Pages
completions and generic drain sweep completions allocate no recovery runner and perform no
scheduler GETs. These are fixture operation counts, not live quota/billing figures; startup, Git fetches and action
setup are not measured. Recovery buys eventual revalidation after lost notifications or
withdrawn evidence and is not an idle-cost saving.

The full collector still authenticates all sixteen targets and 2,880 frames, with unchanged source
run/attempt, immutable artifact IDs/digests, runtime provenance and public availability rules.
`baseline_request` logs distinguish `evidence-incomplete`, `collector-active`,
`certificate-available`, `collector-dispatched` and stale-source outcomes, plus bounded
GET/POST attempt counts. The live rollout must separately record request suppression,
collector starts/no-ops, certificate issuance, final-readiness latency and recovery overhead.
