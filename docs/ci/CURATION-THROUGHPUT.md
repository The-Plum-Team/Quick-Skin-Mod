# Bounded ordinary visual curation

Issue [#1948](https://github.com/The-Plum-Team/Quick-Skin-Mod/issues/1948) owns preparation,
not model concurrency. The workflow keeps its three target runners and whole-producer completion
dependency. It does not admit an unfinished producer or turn a visible upload into success.

## Where the original wave spent time

The complete generation `ec802e0f0d022b16620460d87001dd9fcecb6267` used curator
[`34319067699/1`](https://github.com/The-Plum-Team/Quick-Skin-Mod/actions/runs/34319067699).
The exact-attempt job/step inventory distinguishes these quantities:

| Original 16-target wave | Measured time |
| --- | ---: |
| Complete workflow elapsed | 20m56s |
| First target start to last target completion | 18m38s |
| Sum of target runner-active intervals | 2,986s |
| Sum of combined authentication/download/image-validation/curation steps | 2,788s |
| Sum of curated upload steps | 46s |
| Sum of target waits after common authentication | 6,024s |

The sums overlap across runners and are not workflow elapsed or billed time. The old step combines
API, download and image work; its 2,788 seconds must not be described as pure image CPU. Target
uploads took only 2–4 seconds each. The last target finished at 06:45:30 UTC; final wakes started
at 06:45:32, while the whole producer finished at 06:47:26. The separately recorded first actual
reviewer started at 06:48:11.

## Implementation and bounds

Each target still independently authenticates the complete current source run/attempt, original
runtime reference, exact artifact partition, digest/size and any feature selection before images.
The row validator now returns the complete frame snapshot that it has just admitted. Manifest
projection and the same-run Fabric reference consume that snapshot in the same process instead
of rereading and decoding each report's PNGs a second time. Nothing is cached across invocations
or loaded as an allegedly trusted snapshot from an artifact.

Canonical curation still reopens each candidate and checks both its original-file and decoded-RGB
digest. It uses two CPU-only candidate workers with at most two ordered futures in flight. They
do not make API calls, publish files or decide coverage. The main thread retains reference work,
semantic-region analysis, exact manifest order, pixel/byte accounting and atomic output. Thus up
to three compute threads can be active inside a target; the target-runner limit remains three.
Each worker retains the existing per-image byte/pixel limits. Logical candidate/reference visits
are checked against the aggregate pixel bound before prefetch, and retained image bytes retain
their independent cap. Extra concurrent image working memory is bounded, not free.

The one-worker default remains for other callers. The original PNG encoding, compression level,
1920×1080 dimensions, region semantics, labels, runtime assertions and proof schema do not change.
The consumer independently revalidates the canonical capsule before credentials. Successful,
failed and interrupted curation joins its workers; handled failure/cancellation removes incomplete staging
and cannot publish a source proof. An independent subsequent invocation can recover the target.
Existing durable capsules, partial-matrix recovery, quota handling and exact wake identities remain
unchanged. No additional runner, archive download or metadata lookup is introduced by CPU workers.

## Comparable image-path measurement

The before/after replay uses every raw artifact of original runtime
[`34311573736/1`](https://github.com/The-Plum-Team/Quick-Skin-Mod/actions/runs/34311573736):
32 immutable ZIPs, 1,152,888,950 compressed bytes, acquired once with a 64 MiB per-artifact and
1.25 GiB aggregate bound. Every local read still checks the recorded exact size/SHA-256 and runs
bounded extraction. Original matrix and scenario-contract blobs equal those at this change's base.

Both versions process all 16 targets and all 2,880 ordinary frames, including repeated same-run
reference visits. They run sequentially on the same host, interpreter and Pillow, recording wall
and aggregate process CPU separately for archive/extraction, row validation, manifest projection,
canonical curation and independent capsule validation. Every final manifest and the complete
content-addressed image inventory must be byte-identical between versions. Small focused tests
can overlap the benchmark; this is not an exclusive hosted-runner laboratory.

Remote source authentication is explicitly replaced by the same saved authenticated source
fixture for this offline image-path experiment. Its wall/CPU measurements exclude live API latency,
installation quota, runner scheduling, upload and model execution. The one-time artifact fetch is
diagnostic acquisition cost, not claimed production bandwidth savings. Real-Git/source-attempt
regressions remain separate from these image measurements; protected live gates remain required.

### Complete replay results

The complete experiment ran on macOS 26.6.2 ARM64, Apple M5 Pro (15 physical/logical cores,
24 GiB RAM), CPython 3.11.15 and Pillow 12.3.0. The before process loaded the original code at
`33c14b1e73ff2e6686ee00ef5f43871388f83523` before implementation edits; after started only once
before completed. These are sums of sequential per-target image-path intervals, not hosted
workflow elapsed times:

| Same complete 16-target workload | Before | After |
| --- | ---: | ---: |
| Total measured wall seconds | 1,067.298 | 628.797 |
| Aggregate process CPU seconds | 1,065.931 | 984.574 |
| Row validation plus manifest/reference collection, wall seconds | 164.050 | 81.885 |
| Canonical image curation, wall seconds | 815.391 | 459.252 |
| Canonical image curation, CPU seconds | 814.475 | 815.495 |
| Independent capsule validation, wall seconds | 76.290 | 75.650 |
| Raw local archive read/extraction, wall seconds | 6.705 | 6.775 |
| Report PNG decodes | 8,460 | 4,230 |
| Raw archive reads | 47 | 47 |
| Raw compressed bytes read | 1,576,990,265 | 1,576,990,265 |
| Canonical image bytes retained across target outputs | 784,231,027 | 784,231,027 |
| Required ordinary frames | 2,880 | 2,880 |

The observed local wall reduction is 41.085%, with 7.632% less aggregate CPU. Canonicalization's
CPU cost is essentially unchanged (+1.020 seconds); its overlap reduces wall time while removing
the duplicate report decode reduces total CPU. The 47 archive visits include the same-run anchor
reference in each paired target, exactly as before. The offline experiment makes zero remote
authentication calls; the 16 source-admission boundaries are fixed fixtures, not measured API
savings. Production admission call sites remain unchanged.

All 16 actual before/after manifests and every retained image's name, size and SHA-256 match.
After proofs were captured as actual output. Before proof bytes were not separately retained:
their unchanged construction was checked by AST equality and reconstructed from the recorded
before manifest/frame/image metadata and exact frozen source identities. All 16 reconstructed
proofs equal actual after output. This is differential proof-construction evidence, not a claim
that historical proof files were captured or that the local fixture is a live producer.

The after single-process replay's full peak RSS was 461,586,432 bytes. Sampling during the latter
part of before observed 365,740,032 bytes, only a lower bound on its true peak; no peak-memory delta
can be inferred honestly. These measurements cover one process replaying 16 targets, not fresh
hosted target jobs. Up to three compute contexts replace one inside each target, with no change
to the three-runner limit. The measured memory/CPU tradeoff must not be hidden behind the
two-worker label.

Retained local evidence is named `curation-before.jsonl`, `curation-after.jsonl` and
`curation-comparison.json` in the task's evidence directory. Their SHA-256 digests are respectively
`9923cacafb1bd6decb8de9e7853f4a7ca236496622053d9ac9b7ed24fc30bca3`,
`c11ad3ee72b98154d8c6bbaca9a4edd11aa43abcd035eff74e97890314d2287c` and
`2bc74da41e9750c371e1969be1449e1d2edc00a5909997a404ac8e12cbb0bfe1`.
The acquisition inventory and complete original job snapshot are retained separately, alongside
the bounded fetch/replay/comparison scripts. No packaged runtime or model was launched by this
experiment. Protected CI uses its pinned Python 3.13 decoder environment; this experiment does
not claim identical timings on that environment.

## Live diagnostics and safety checks

The curator emits a bounded `curation_profile` JSON object containing only named-stage call/failure
counts, wall/CPU seconds, logical API-client metadata/archive counts, validated archive bytes and
admitted row/frame counts. Transport retries can issue more requests than these logical counts;
the counters are not installation-wide quota usage. Admission archive downloading is nested inside
selection authentication, so nested stage times must not be added as independent totals. Profile
data cannot authorize a proof, change retry behavior or contain raw provider/error payloads.

Use the exact-attempt GitHub job/step inventory to keep scheduling and upload intervals separate
from the profile. A live complete-wave comparison must retain every target/attempt, the complete
2,880-frame product and the first admissible reviewer, and report extra CPU/memory alongside any
wall reduction. A local image-path speedup is not itself a measured hosted queue or end-to-end
speedup.

Focused regressions cover complete/selected rows, unchanged archive counts, fresh admission per
invocation, source-image mutation after collection, exact output-byte/order parity with one/two
workers, bounded prefetch, failed/cancelled futures and staging cleanup, and independent canonical
capsule revalidation. Existing source-reuse tests still reject failed/foreign attempts and missing
full runtime graphs.
