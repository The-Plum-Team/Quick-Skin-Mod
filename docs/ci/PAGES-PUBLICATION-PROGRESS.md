# Pages publication by authenticated coverage progress

`scripts/pages/publication_progress.py` is an advisory cost-admission controller. It runs in
the protected Pages discovery job, before the complete collection and rendering fan-out. Its
decision is never a runtime gate, image verdict, reusable baseline or deployment certificate.

## Admission and durable state

The release matrix supplies every target and the single lossless ordinary anchor. There is no
second supported-version inventory. The controller authenticates the anchor's exact SHA-bound
cache and its completed successful Pages owner, then requires that owner's exact-attempt Build,
Deploy and all four target-derived Collect/Refresh job families. Every ordinary cache must be
present in that same owner; compatibility progress comes only from its validated compatibility
caches. Artifact creation must fall inside the corresponding successful upload step in that
exact attempt. A cancelled/failed or selectively rerun owner cannot claim the old attempt's
publication. Different owners are never unioned into a fictional complete deployment.
For each published compatibility target, the successful selector step's start is the conservative
consumption boundary. A compact producer completing after that boundary reopens publication even
when its artifact predates the later cache upload. This closes the completion-during-build race.
Ordinary evidence has a separate all-target E2E owner/attempt boundary. A later complete same-head
runtime attempt reopens ordinary publication automatically, including its exact raw anchor;
partial or older attempts cannot displace the current site.

New compatibility handoffs are queried by exact matrix-derived name and selected by immutable
ID. Each retains its exact source SHA, owner repository/workflow/run/attempt, non-expiration,
bounded size, SHA-256 digest and successful compact-publication upload window. An exact-ID read
after owner admission rejects a changed/deleted nomination. Terminal owners may be shared only
within this snapshot; each artifact is checked independently. Invalid/stale/foreign candidates
are not progress. Malformed inventories, incomplete pagination, API failures and exhausted
request budgets stop admission; they never turn into evidence absence or a new full fan-out.

The existing successful caches are the durable ledger; no extra marker, state branch, archive
download, producer-side baseline validation or optimistic completion flag is introduced. When
there is no successful current publication, one exact successful E2E owner must contain every
ordinary handoff from its current attempt before the initial full fan-out is admitted.

Full collectors still select and validate their own current artifacts, including arriving
evidence newer than the controller snapshot. Build still authenticates the complete 16-target
runtime fan-in before rendering; raw anchors and original runtime identities are unchanged.
The controller supplies immutable ordinary/compatibility handoff-ID nominations by target so a later-timestamp stale
cache cannot hide a producer that the preceding collector missed. The collector independently
rechecks that exact ID, source, repository, owner, current attempt, upload window, digest and
availability before download; disappearance fails closed instead of falling back to stale data.
These nominations are not authority to publish and do not replace bundle validation.
Fresh master checks bracket progress admission and the existing render/deploy boundaries.
Rotation remains bound to the actual completed successful Pages owner, not to a scheduler run.

## Timing and recovery

For an available publisher, the policy admits:

1. The initial complete ordinary publication.
2. A compatibility halfway milestone, after at least ten minutes from the oldest unpublished
   authenticated handoff. The matrix determines the halfway count, rounded upward.
3. The complete compatibility product immediately, without waiting for the coalescing interval.
4. Partial progress when its oldest unpublished handoff has waited 45 minutes, even if another
   target stalls or fails. Further progress can establish another bounded partial deadline.

Wakes and publications keep their shared non-cancelling concurrency lock. Duplicate/reversed
wakes therefore rediscover current coverage after the active publisher; completion arriving
during a build is admitted on the next available publisher. The controller itself inserts no
final-completeness delay: its deterministic replay enqueues the final immediately and therefore
within the two-minute policy target. GitHub runner/queue delivery is external and must be
measured in the live generation; the replay is not a guarantee of hosted-runner latency.

The former monthly Pages schedule is replaced, not supplemented, by one hourly recovery
sweep. This recovers a lost ordinary/compatibility notification and a cancelled publication
without an idle timer runner or a self-dispatch loop. A stalled partial is eligible at 45 minutes
and is observed by the next hourly sweep: at most 105 minutes before publisher/runner
availability, plus external GitHub schedule delivery delay. Scheduling is not hard real-time.
Every recovered publication still performs ordinary full evidence admission.

After three failed/cancelled full publication attempts without newer readiness, automatic
fan-out stops. Failed wake authentication, rotation and skipped Build placeholders do not spend
this budget. New evidence establishes a new recovery opportunity. `operation=manual` remains
the explicit operator recovery and never weakens collectors or deployment admission.

A successful complete generation checks bounded current-SHA successful compatibility-owner and
ordinary E2E-owner inventories, then exits before per-target handoff scans when no owner finished
after its consumption boundaries. This automatically recovers a lost same-head replacement wake, including a producer
that began before collection and finished during publication. An explicit compatibility wake
inspects its current handoffs directly. There is no self-dispatch loop, idle timer runner or
producer-side baseline admission. Stale compatibility wakes cannot dispatch a newer generation.

## Saved-generation replay and measurements

The bounded fixture `scripts/ci/tests/fixtures/pages-progress-reference.json` projects the saved
`master-runs.json` inventory for `ec802e0f0d022b16620460d87001dd9fcecb6267`, retaining its SHA-256
and all 34 observed Project site runs. Readiness timestamps are the 16 successful compact
publisher completion times, not invented upload timestamps. The count-only replay represents
targets as anonymous arrival slots; independent API fixtures test exact target/owner identities.
It also includes hourly recovery ticks and completion events, conservatively assigning the
observed initial publication's 17m45s whole-workflow duration to every simulated publication.

| Measurement | Observed saved generation | Clean policy replay |
|---|---:|---:|
| Recorded Project site workflow runs | 34 | Same 34 input events, plus explicit recovery ticks |
| Full site Build jobs | 9 | 3: ordinary, halfway, final |
| Successful deployments | 8 | 3 simulated; not live deployments |
| Executed Collect/Refresh jobs | 544 | 192 simulated |
| Build-job runner time | 2,839 seconds | Not measured |
| Collect/Refresh runner time | 12,801 seconds | Not measured |

The observed transport-failed deployment reached 32 collectors but no refresh matrices. Its two
skipped refresh placeholders are not executed jobs; nine wholly successful 64-job waves would
have been 576 jobs, which is not the observed count. Build plus Collect/Refresh consumed 260.67
runner-minutes, excluding discovery, deployment and rotation. The replay beats the at-most-five
full-build target but does not turn fewer modeled jobs into a measured wall-clock or billing saving.

Controller GET operations are counted at the actual bounded API boundary, separately from
workflow and job counts. The eight-ready-target fixture performs 49 GETs: two head checks, 18
artifact inventories, nine owner reads, nine exact-attempt job inventories, eight exact-artifact
reads, one E2E-owner inventory and two failed-attempt inventories. A complete-generation early
exit performs eight GETs, including the two current-generation owner queries that recover lost
ordinary/compatibility replacement wakes.
Each nominated handoff's independent collector authentication performs six GETs: two head checks,
two exact-ID artifact reads, one owner read and one exact-attempt job inventory. Those collector
operations are separate from the scheduler's counts and from the existing full bundle validation.
Each invocation has a hard limit of 160 GET attempts and downloads zero archives. These are
executed fixture operations, not installation-wide consumption estimates. The conservative
replay calls the real controller against a fake API for all old wake types, readiness, completion
and recovery events admitted while idle: 43 snapshots execute 1,820 scheduler GETs; the three
publications' nominated collectors execute 240 further GETs. This deliberately includes old
derived deployment/rotation events as extra readiness checks, so 43 is not a prediction of new
workflow-run count. Existing full collector/runtime API work is not reconstructed; observed
pre-change API totals and new live workflow counts remain separate measurements.

Recovery also has a real steady-state cost: 24 scheduled discovery jobs per day. The current
discovery shell adds three GETs to the eight-GET completed-controller snapshot, so a completed
generation costs 264 GETs/day under that fixture shape. Hosted checkout/startup/teardown time
and billing rounding have not been measured for this new scheduled path. They must be reported
separately from publication savings; summed quota snapshots do not measure global consumption.
The hourly interval deliberately avoids 144 jobs/1,584 GETs per day for a ten-minute schedule.
It trades a 55-minute partial bound for the documented 105-minute bound while event-driven final
publication remains immediate. A per-invocation 45-minute timer is not a per-generation bound:
the reference could spend roughly 40 idle minutes before halfway and another 20 before final,
as well as repeated metadata reads. The selected policy has zero idle timer runner time and
does not introduce a second persistent timer state machine. Do not claim a net monetary saving
from this replay; production frequency, hosted job setup and billing rounding still matter.

The subsequent live full-generation acceptance must record exact run/attempt/SHA inventories,
actual admitted Build and Collect/Refresh jobs, their durations, all scheduler JSON summaries,
final handoff completion, active-publisher availability, final enqueue/start/deploy times and
the successful rotation owner. Report scheduled no-op jobs/API operations separately. This
offline implementation does not claim that live acceptance has already happened.
