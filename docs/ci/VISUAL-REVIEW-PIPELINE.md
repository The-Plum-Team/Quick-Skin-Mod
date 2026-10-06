# Bounded reviewer preparation and durable completed results

Issue #1949 moves only independent, secretless capsule preparation off the ordinary
reviewer's model/cache critical path. It does not add parallel model workers or change
the exact verdict-cache key, prompt, model, image, label, manifest, or completion rules.

## Measured reference generation

The reference is source `ec802e0f0d022b16620460d87001dd9fcecb6267`, reviewed on
2026-09-09: 16 reports and 2,880 frames. The checked-in fixture
`scripts/ci/tests/fixtures/visual-review-stage-reference.json` records all 16 exact
run/attempt/job identities and their GitHub step timestamps. Protected numeric model
summaries were read from those exact job logs; provider payloads were not retained.
This is a historical measurement, not a measurement of the changed workflow.

| Historical stage | Total seconds across 16 jobs | Per-job range |
| --- | ---: | ---: |
| Fetch, extract, authenticate and validate capsule | 449 | 17–35 |
| Restore authenticated exact-policy cache | 21 | 0–2 |
| Bounded model runner | 3,763 | 139–348 |
| Independent result/input validation | 138 | 5–10 |
| Upload normalized report | 19 | 1–2 |

The model-runner stage includes its own input validation, planning, model-visible image
preparation, provider calls and retry delays; these timestamps do **not** isolate pure
inference. Provider wait duration was not separately recorded. The remaining job time
includes setup, guards, cache publication, certification and cleanup. Summed reviewer
job service time was 4,729 s. The observed first-review-to-last-cleanup interval was
4,766 s (79m26s); the final reviewer waited 75m34s from run creation to job start.

The actual counters were 685 cache hits, 173 within-capsule represented frames, 2,022
triaged frames, 259 triage chunks, 56 escalated frames and 18 verification chunks.
There were 278 provider process attempts (260 triage and 18 verification), including
one retry. Overlap is not a reason to increase any of these counts.

## Ownership and immutable handoff

Two constant secretless preparation slots are selected from the original capsule ID's
parity. They use read-only permissions, the exact protected checkout, bounded extraction
and complete pre-secret capsule/provenance/image validation. The artifact handoff contains
the **original ZIP bytes**, not a rewritten manifest or image set. Its name includes the
current drain run, attempt and original capsule ID; retention is one day. The original
seven-day queued capsule remains the durable retry source.

Preparation initially checks out the immutable executing workflow `github.sha`, never
a selector output. Before any API access, an inline guard checks the selected immutable
IDs, hashes, name and byte bound. It then authenticates the exact capsule metadata and
protected curator owner with two read-only metadata requests. Only an implementation
already present in the fetched Git history and ancestral to that workflow revision may
be checked out locally, with hooks and network protocols disabled, before dependency
installation or capsule validation. Historical protected ancestors remain eligible;
newer or unrelated implementations fail closed and leave the input for a fresh wake.
Current-generation eligibility alone does not authorize executing arbitrary policy.

The model job retains the current workflow's control helpers and their data dependencies
from an explicit `git archive "$GITHUB_SHA"` before its existing exact historical
reviewer checkout. Preparation restoration and completed-result/cache authentication
execute from that isolated snapshot, even when the old checkout lacks those helpers.
The wrapper owner is bound to the executing **workflow SHA**, while the unchanged
original capsule and model/proof policy remain bound to the selected **implementation
SHA**. These identities need not be equal for eligible historical work.

One global `quick-skin-visual-review-model` owner serializes cache restoration, model
execution, independent validation, report/cache publication and cache rotation across
ordinary review generations. Each runner allows at most 32 simultaneous provider calls,
so the aggregate ordinary-review bound is also 32, not `32 × prepared capsules`.
The independently governed optional-compatibility reviewer and the capacity probe retain
their existing limits; this is not a new combined cross-workflow provider quota.

Before use, the model owner reauthenticates the same protected run/current attempt,
successful named preparation job, exact artifact ID/owner/digest, wrapper bounds and
the original capsule size/SHA-256. It then rechecks the current source head and source
proof. Image validation inside the protected runner and the independent post-model
validator remain in place. Hostile or stale data never becomes executable policy.

Cache restore occurs only after acquiring the global model lock. A previous owner's
exact successful independent-validation/cache-build/cache-upload steps commit its
immutable replacement union, regardless of later job cancellation, failure or timeout.
The specific cache ID/name/digest/size/owner must still match freshly retrieved inventory
metadata, and its creation timestamp must belong to that exact attempt's successful
upload window. A later attempt cannot lend its steps to an earlier cache. The exact
checkout/prompt/policy digest and every cache entry are still validated.
Replacement cache publication must succeed before consumed shards are
deleted. Thus two preparations sharing an exact key see the latest admitted union;
distinct keys remain present. There are no competing cache writers or best-effort
last-writer-wins merges.

## Cancellation and provider loss

The normalized complete report is uploaded **before** publishing/rotating its cache.
If the protected owner is then cancelled, retry authenticates that cancelled owner's
exact attempt and successful independent-validation/report-upload steps, binds the
immutable artifact timestamp to that upload interval, verifies its digest and bounded
four-file inventory, and requires byte-identical current proof/manifest plus complete
clean verdict coverage. It does not accept partial results, foreign policy, a later
attempt's steps, or a merely present report filename. A valid cancelled report with
another proof/manifest is skipped without reusing any verdict: the same source run ID
can have a newer runtime attempt, and the historical report must not block its newly
authenticated capsule. Malformed or partial reports and API failures remain errors.
The cancelled report owner must be exactly the selected implementation or the current
executing workflow SHA. This preserves legacy implementation-owned reports and a current
workflow's completed review of an older capsule, without admitting an arbitrary third
revision. Fresh metadata must bind that same authenticated owner head; the proof and
manifest must still be byte-identical to the original capsule.

The recovered result passes the ordinary independent validator and cache publisher;
the model step exits successfully before consulting provider credentials or invoking a
provider executable. Original cancelled artifacts are retained. Canonical downstream
admission must distinguish a terminal cancelled predecessor from its unique eligible
replacement, never certify the cancelled artifact itself.

Cancellation before successful normalized upload retains the original queued capsule
and existing admitted cache, but this change does not make unfinished local chunks a
complete durable report. An already-active shared capacity cooldown may still defer
queue admission before recovery executes. Recovery itself requires no inference; it
does not bypass the shared capacity scheduler or claim immediate dispatch during an
open circuit.

Freshly completed inputs and inputs skipped as already reviewed remain until their
existing seven-day expiry. The cleanup job succeeds without any API call for either
case, preserving release-tail dependencies. A fresh owner can still be cancelled after
cleanup, and an already-reviewed report can belong to another still-running drain;
neither is proof that the recovery input can be deleted. Missing and terminally invalid
inputs retain their existing exact-ID cleanup. Canonical certificate admission still
rejects cancelled report owners; it has not been relaxed to compensate for lost inputs.

If the bounded GitHub GET fails while `feature_review.py --verify-proof` reauthenticates
the source, the verifier still fails before model admission but emits a typed protected
retention signal. The failed capsule step maps only that signal to a sanitized
`github_transport_unavailable` attempt marker. Its `transient: true` disposition retains
the original capsule for the existing target-scoped cooldown and scheduled retry; it
does not establish an HTTP status, quota cause, or successful proof. Response-size, JSON,
digest, source, attempt and capsule validation failures remain nontransient. No error-text
matching, new retry loop or extra dispatch is added. Historical implementation checkouts
without this typed signal retain their original classification behavior.

The same retention applies to every other protected GitHub read that can stop the reviewer
before a verdict. Run 37411958425 attempt 1 (`mc1.21.6`, 2026-10-06) raised
`GitHubGetUnavailable` in cancelled-report recovery during an installation quota exhaustion,
before any model call. It had no typed signal, so the fallback wrote `protected_validation`
with `transient: false` and the cleanup job tried to delete the unreviewed input; only the
same quota exhaustion deferred that deletion. Now `visual_review_completed.py` and
`visual_review_preparation.py` write the fixed `github_transport_unavailable=true` output for
that exception. The shell retry helpers accept an opt-in `GITHUB_API_RETRY_UNAVAILABLE_SIGNAL`
file that describes only their most recent call: it exists after the bounded retry stopped
on a response that the helper already classifies as retryable (rate limit, HTTP 408/429/5xx
or transport failure), and success or any other failure removes it. The queue guard's own
bounded retry does the same. The classifier maps a signal only from the step that actually
failed (queue guard, preparation restore and source reauthentication, or report recovery) to a
`github_transport_unavailable` marker with `transient: true`. The marker still starts the
target-scoped cooldown, so a later sweep retries the retained capsule; it never opens the
Claude capacity circuit. The reviewer's restore and recovery steps source the helper from
this workflow's retained protected copy, so an older implementation checkout cannot lose the
signal. Identity, digest, owner, freshness and other validation failures still classify as
`protected_validation`. The optional-mod review classifies its live-`master` admission read
the same way; it has no input cleanup, so the marker only records the cause.

Both generic and exact queue selectors suppress retained inputs while their authenticated
reports have successful, failed or unfinished owners, and reopen them after cancellation.
Fresh reports start their own seven-day retention after their reviewed input's upload,
so those markers outlive their inputs and suppress another review. An already-reviewed
input from a later recuration can instead outlive the other owner's older report; if that
marker expires first, the remaining bounded input window can admit review again. There
is no new scheduled cleanup dispatch, and suppression while a marker exists is not a
claim of zero future inference after marker expiry.
Expiry, not a guaranteed later wake, is normal retirement for completed inputs.

GitHub keeps an artifact's listing record after its retention expires, so a name that is
used every generation accumulates records indefinitely: `mod-compatibility-plan` held 1,031
records on 2026-09-16, of which only 155 were live. The review queue's named lookups
(`visual_review_queue.py`) therefore collect and bound live records only, which is what every consumer already admits, and a separate 200-page bound
keeps the scan itself finite and fail-closed. Counting expired history against the live bound
previously made scheduled compatibility recovery fail permanently once a name crossed 1,000
records, and would have done the same to the shared capacity circuit.

This durability has an explicit storage/API cost. Admission allows at most 512 MiB per
input: one 16-target generation can retain up to 8 GiB for seven days (up to 56 GiB with
one such generation daily), excluding reports, caches and one-day prepared wrappers.
This is a per-input/per-wave bound, not a repository-wide aggregate storage cap. The
16-target queue regression observes 17 unique owner lookups per settled generic sweep
(16 report owners plus one shared curator); the real client memoizes those owner GETs.
At the existing 48 daily sweeps, a still-current retained generation therefore adds
816 owner GETs/day versus an empty input queue, plus any additional artifact-list pages.
Historical non-current generations do not trigger those owner reads. Each fresh completion
avoids its previous cleanup metadata GET and DELETE, but that does not establish net API
or storage savings. Retention itself adds no runner, dispatch or polling loop; the possible
older-marker expiry retry above must not be counted as guaranteed zero added inference.

## Observable model attempts and local retry waits

The runner retains telemetry schema 1 and the existing six model-attempt counters.
Separate sanitized stdout snapshots record cumulative launched CLI attempts and local
retry-backoff events, with started/completed/cancelled counts, the existing fixed error
categories and elapsed milliseconds measured by a monotonic clock. Concurrent worker
wait durations are summed service time, not review-stage wall time or provider queue
time. A CLI process attempt is not an individual API request or model turn. Logging
does not change retry decisions, delays, model limits or payloads.

Snapshots carry sequence and terminal/failure markers. Count the latest consistent
snapshot, never add cumulative snapshots together. Handled success and failure emit
terminal counters; an interrupted process may leave only observed lower bounds, and
absence is unknown rather than zero. The independently schema-checked JSON summary
also reaches stdout and the step summary, including model-free recovery. No provider
response text, credentials or usage payloads belong in these logs.

Provider-internal waiting is not exposed by the CLI and remains unmeasured. Historical
evidence records one retry but no actual local wait duration; do not backfill it as zero
or substitute the configured delay for a measurement. Updating runner instrumentation
changes its exact cache-policy digest. The first new-policy wave therefore uses a new
cache namespace: retain the old keys, do not migrate verdicts or weaken policy hashing,
and report actual cold-namespace inference/cache counts separately from overlap savings.

## Where the runner stage went

The runner starts no model process before its first `Sanitized model progress` line, which
it writes right after the first `Popen`. Splitting the runner step of every review job at that
line and at the first verification launch, from the exact-attempt job logs of the two complete
waves that followed the preparation split:

| Generation | Review jobs | Before the first launch | Triage | Verification | Runner step |
| --- | ---: | ---: | ---: | ---: | ---: |
| `e7ad2618` (2026-09-14) | 16 | 1,949 s | 771 s | 944 s | 3,664 s |
| `157d3cac` (2026-09-19) | 17 | 1,941 s | 889 s | 935 s | 3,764 s |

About half of that stage therefore had no call in flight: 83–148 s in every job. The
independent validation step repeats the same input validation in about 9 s; the rest was
model-image staging. Every chunk decoded, resized and re-encoded its 1920x1080 sources at PNG
level 9 while holding the one artifact lock that each launch takes again.

The runner now encodes those transient copies at zlib's default level and stages them in the
chunk workers, holding the lock only to publish a finished copy. They remain deterministic
1280x720 PNGs with identical pixels, are never uploaded, and no verdict binds their bytes.
Replaying the retained 180-frame `mc1.20.1` capsule of `26c400a5` through the provider, with
the model process replaced by a recorder (Windows workstation, four logical CPUs, 23 chunks),
moved the first launch from 15–26 s to 2–3 s and the last from 44–49 s to 8–12 s; the 156
staged images grew from 45.0 to 46.9 MiB (4.3 %). That is a local replay, **not a hosted or
live timing**, and the effect on a complete wave is unknown until one is measured.

The split in the table no longer isolates staging. The first progress line now follows the
first staged chunk, and the other chunks stage while those calls are in flight, so staging
moves into the triage column instead of disappearing. Compare a later wave by its runner-step
total and by the time to its last triage launch (the first progress line whose `triage_chunks`
equals the `Visual review plan` count, while the plan has no more chunks than parallel calls),
never by the time to the first launch, and report it with that wave's model attempts and
cache hits.

The same change removes two causes of repeated inference seen in both waves:

- A reviewer no longer deletes verdict caches whose exact-policy name differs from its own.
  `anchor-semantic` and `reference-comparison` have different policy digests, so each mode
  retired the other's current cache: the paired job that followed the anchor restored no
  shard. Shards of a superseded policy now simply expire after their seven days.
- A drain whose next job is still waiting has no running job. The guards accepted an owner
  only as `in_progress` or terminal, and in that interval they ignored the cache, report,
  attempt marker and generation block it had uploaded: the only other paired job that restored
  no shard followed such an owner, and on `26c400a5` a sibling reviewed all 180 frames of a
  generation whose block had been uploaded seconds earlier. The status GitHub reports in that
  interval was not recorded, so every non-terminal status without a conclusion now counts as
  the live owner. Its authority is unchanged: the exact successful steps, the upload window
  and the immutable artifact metadata.

Both edits cost one cold wave. The runner's digest is part of the exact cache policy of the
ordinary and the optional-mod reviewer, and older shards no longer come from a byte-identical
cache-producing workflow.

## Offline replay, overhead and live acceptance

`HistoricalStageReplayTest` checks every matrix target and all reference counters. Its
counterfactual preserves the historical model/cache order and all non-preparation job
service time. With preparation ordered the same way, both two slots and the conservative
single-slot sensitivity finish preparations ahead of the serialized owner. These are
simulation outputs, **not live after timings or GitHub queue guarantees**:

| Extra serialized handoff/reauthentication per report | Simulated service seconds | Reduction from 4,729 s |
| --- | ---: | ---: |
| 0 s, optimistic ceiling | 4,309 | 420 s |
| 5 s | 4,389 | 340 s |
| 10 s | 4,469 | 260 s |
| 20 s | 4,629 | 100 s |
| 30 s | 4,789 | −60 s |

The first 29 s preparation cannot be hidden, leaving at most 420 s of modeled service
savings. Break-even is approximately 26.25 s additional serialized overhead per report.
Queue ordering, runner availability, downloads and model variability can change this.
The change adds one preparation job and one short-lived wrapper upload/download per
report. The pre-checkout guard adds two normal metadata GETs per preparation, or 32 for
a 16-target wave; bounded transient retries are additional. The current control snapshot
copies local checkout content, not another download: the four archived trees contained
247 tracked files / 4,295,919 bytes at the pre-final-head measurement, and this size
changes with the protected source revision. Restore adds exact owner, exact-attempt
jobs and artifact metadata GETs plus one
bounded archive GET; source proof/current-head reauthentication is retained. Completed
report recovery adds one exact-name inventory and bounded owner checks only when there
are candidates. Cache commit authentication adds bounded exact-attempt job and exact-ID
metadata reads.
No polling loop, full raw-archive fan-in, model warmup or additional inference is added.
This is a wall-clock latency tradeoff, not a claim of fewer runner minutes: duplicated
job setup, the wrapper transfer and repeated source authentication can increase total
CI service while shortening the serialized tail. Model inference count is not increased.

A separate 2026-09-13 offline replay exercised the actual restoration helper five times
against a real 180-frame `mc1.21.1` capsule recurated from the verified historical raw
artifacts. All 236 files, including the manifest, proof and content-addressed images,
were byte-identical before/after. The locally reconstructed level-6 ZIP was 40,109,390
bytes; its stored wrapper was 40,109,540 bytes. Median measurements on macOS arm64,
Python 3.11.15 and Pillow 12.3.0 were:

| Local stage | Wall seconds | CPU seconds |
| --- | ---: | ---: |
| Original bounded extraction and full input validation | 4.879 | 4.869 |
| Additional secretless wrapper packing | 0.034 | 0.034 |
| New serialized authenticated-wrapper restore/extraction | 0.176 | 0.175 |

This replay made zero network/model calls, used a warm local filesystem, and mocked
only the immutable GitHub metadata/transport boundary. The fixture is locally produced
from verified historical inputs, not claimed to be an original remote curator artifact.
These figures isolate local handoff overhead; they do not measure GitHub transfer,
runner setup, current source-graph authentication or the Python 3.13 Linux CI runtime.
The manifest SHA-256 was
`db40e0a16fba543ca57d980be297395e20d0434e78bd8ea6b8743c369d37be38`.

The next normal live full generation must retain all 16 reports/2,880 frames and record
preparation/model queue and stage times, wrapper transfer size/time, cache counters,
provider attempts/retries, report/cache upload order and complete-current-head admission.
The runner now emits only bounded numeric counters to the job summary for that comparison.
Do not report the modeled saving as achieved, or launch a duplicate full inference wave
solely to manufacture a benchmark. The serialized runner stage remains the dominant
critical path, although about half of it was image staging rather than provider time (see
"Where the runner stage went"); a regression beyond the measured overlap budget requires
revisiting this split rather than increasing concurrent model/cache owners.

The first deployment had to begin its new-policy live wave only after any already-running
old-policy model job finished: a previously started immutable workflow keeps its old
implementation-scoped lock and cannot retroactively acquire the new global lock. Drains under
the global lock have run on `master` since 2026-09-14.

Focused regressions cover same/different exact keys, malformed cache policy/key,
foreign/stale preparation identities, bounded traversal rejection, unsuccessful
preparation, cancelled publication, another report attempt, partial completion and a
real recovered model shell step with no provider token or executable. The union-retirement
regression proves that an earlier key A remains reusable after the replacement A+B cache
is committed and the writer is cancelled, fails or times out; recovering current report
B alone would not preserve A. Failed validation/build/upload, changed metadata and an
artifact outside the exact upload interval cannot authorize that replacement.
The actual cleanup shell also proves successful zero-API retention after fresh review
and after an in-progress other-owner report, while exact-ID invalid/missing cleanup
continues and mismatched metadata cannot authorize deletion. A late-cancellation replay
executes cleanup before recovering the original capsule without provider access.
Real local two-revision Git tests admit an authenticated protected ancestor, reject
newer/sibling revisions and malformed identities before checkout, preserve current
control helpers across an old tree missing those files, and distinguish the current
wrapper owner from the original historical curator policy.
