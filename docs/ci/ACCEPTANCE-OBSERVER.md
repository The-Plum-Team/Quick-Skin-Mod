# Recoverable acceptance observation

`scripts/ci/acceptance_observer.py` watches existing GitHub Actions attempts and keeps a shared,
bounded local inventory. It addresses [issue #1946](https://github.com/The-Plum-Team/Quick-Skin-Mod/issues/1946):
a failed local read must not leave collaborators believing that a long-running remote gate is
still executing. The historical successful generation stays closed; testing this reader requires
no new acceptance wave.

This is an informational observer. A successful inventory means only that its explicitly
registered attempts returned `completed/success`. Required-check completeness, current PR heads,
artifact provenance, acceptance seals, merge authorization and publication remain with their
existing owners.

## Register and share one inventory

One coordinator resolves the existing run IDs, exact attempts and exact head SHAs from the task's
check links or its existing bounded discovery snapshot. Register every run to observe, including a
preceding baseline when it belongs to that task. Different runs may legitimately have different
SHAs. The observer never discovers repository-wide runs or artifacts and never changes the chosen
identities automatically.

Use an existing private local directory outside tracked source. Supply actual values for the
uppercase placeholders below; do not copy an old run into a new task's inventory.

```sh
python3 scripts/ci/acceptance_observer.py --state /private/task-context/acceptance.json init \
  --repository The-Plum-Team/Quick-Skin-Mod \
  --run RUN_ID:ATTEMPT:HEAD_SHA \
  --run BASELINE_RUN_ID:BASELINE_ATTEMPT:BASELINE_HEAD_SHA \
  --interval 30 --timeout 10
```

`init` makes no network requests and refuses an existing state file. The inventory supports up to
100 unique run/attempt pairs. Share its absolute path with every collaborator; use the same path
for the task across restarts. A changed head or newly requested attempt requires an explicitly
registered new inventory. An old terminal success does not certify the newer attempt or head.

Run one coordinator in a durable terminal or the existing local process supervisor:

```sh
python3 scripts/ci/acceptance_observer.py --state /private/task-context/acceptance.json observe \
  --watch --max-seconds 43200
```

Every GitHub call is an explicit read of
`repos/OWNER/REPO/actions/runs/RUN_ID/attempts/ATTEMPT` on `github.com`. The coordinator validates the
repository, run ID, attempt, SHA, status and conclusion before replacing any successful snapshot.
It uses the installed `gh` authentication; it never stores credentials. It does not launch,
restart, cancel or dispatch remote workflows, models or Minecraft runtimes, or write to GitHub.

Other collaborators run the local reader, which works without `gh` or network access:

```sh
python3 scripts/ci/acceptance_observer.py --state /private/task-context/acceptance.json status
python3 scripts/ci/acceptance_observer.py --state /private/task-context/acceptance.json status \
  --watch --max-seconds 43200
```

`observe` without `--watch` performs one due-read sweep. `status --watch` inspects local state every
polling interval and at the next stale boundary; it does not spawn a replacement coordinator. Both watch commands stop when every
registered attempt is terminal or their time budget expires (one hour by default, seven days
maximum). Resume the same `observe --watch` command after its budget expires or its process stops.
Use the product's monitoring mechanism to keep checking `status` when no local terminal is open.
Requests are also capped by the remaining observation budget; earliest-due entries are visited
first so repeated short sessions cannot starve unread siblings.

## Read health and recover

The JSON includes a live-computed `state` and separate `remote_state` for each attempt:

| State | Meaning |
|---|---|
| `pending` | No terminal conclusion has been observed; recent observation is healthy. |
| `read_error` | The latest read failed; the previous valid snapshot is retained. |
| `stale` | The coordinator heartbeat or pending snapshot is at least two polling intervals old, or the wall clock moved backwards. |
| `remote_failure` | An exact registered attempt completed with a non-success conclusion, including cancelled, skipped or neutral. |
| `success` | An exact registered attempt completed successfully. |

`terminal` requires every registered attempt to be terminal. Aggregate state prioritizes a known
remote failure, then stale/read errors, then pending. Inspect the individual rows when a completed
failure and a stale sibling coexist. Completed snapshots stay terminal even after their coordinator
exits and are never polled again. Exit codes are 0 for success, 1 for a remote failure, 2 for an
unsettled inventory and 3 for invalid/unreadable local state or competing coordinator ownership;
successful local initialization exits 0 even though its new inventory is pending.

An OS-backed lock allows one writer for that state path; it releases on process death. Do not
delete the lock file or start another independent inventory to recover the same task. Atomic
replacement preserves the previous complete state across interrupted writes. A sibling `.partial`
is overwritten by the next locked write. Keep the state, staging and lock files on the same local
filesystem; a network share is not a supported distributed lock or clock source.

After a dead observer, restart `observe` with the same `--state`. A request interrupted in flight
becomes a sanitized `interrupted` read error before retry. Its request remains counted as started,
with completion unknown. Restart preserves all exact identities, successful snapshots, completed
attempts, errors, due times and provider cooldowns. Missing/unreadable local state is a visible
`local_read_error`; an API 404 is a read error, never missing evidence or successful completion.

The first two retry delays are 2 and 4 seconds, capped at the polling interval. Further failures
fall back to one retry per interval. Every request has a timeout, capped further by interval /
unfinished-attempt count so a slow sweep cannot consume one full timeout per sibling. Choose an
interval that permits realistic API latency for the inventory's size. Healthy terminal recovery is
observed by the next two polling intervals; declared rate-limit waits and continued failed reads
are explicit exceptions, not evidence that remote execution continues.

Primary reset and rate-limit `Retry-After` deadlines pause the entire inventory and survive
restart; a `Retry-After` on another failed read delays only that attempt. A rate
limit without a usable deadline waits at least one minute; repeated rate limits double this delay
up to one hour. A declared later deadline is never shortened to fit that cap. Heartbeats continue
during the pause. Rate limits are handled according to GitHub's
[REST retry guidance](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#handle-rate-limit-errors-appropriately);
the reader uses GitHub's [exact-attempt endpoint](https://docs.github.com/en/rest/actions/workflow-runs#get-a-workflow-run-attempt).

Only normalized endpoint/category/exit status/HTTP status/time errors enter the state. Each run
retains the latest error and at most 20 error records. Raw response bodies, stderr, URLs returned
by the provider, request headers and credentials are never logged or persisted. The normalized
inventory is capped at 2 MiB. Both CLI output streams share a 512 KiB in-memory limit; excess
output terminates the read and reports `invalid_response` without saving those bytes.
Both local and remote JSON are limited to 64 nested containers before decoding, independently
of the Python version's recursion behavior; quoted brackets do not consume that depth budget.
`requests_started` is durably incremented before each CLI invocation;
`requests_completed` records returns, so a killed in-flight call leaves an honest uncertainty gap.
These are local request-attempt counters, not a claim about the credential's remaining quota.
`recovery_latency_seconds` measures local wall-clock time from the first failed observation to the
next valid one; it does not infer the time at which the remote run actually finished.

## Local fault injection

```sh
python3 -m unittest discover -s scripts/ci/tests -p 'test_acceptance_observer.py' -v
```

The suite uses temporary files, an isolated fake `gh`, an injected clock, atomic-replace failure
and a real killed coordinator process. It covers nonzero exits, HTTP failures, timeout, a killed
in-flight read, identity changes, a competing coordinator, rate-limit recovery, restart after remote
completion and terminal deduplication. No production workflow or remote acceptance is started.

The deterministic transient-failure case uses three exact-attempt requests (initial pending,
failed read, recovered terminal), with terminal recovery after 2 simulated seconds for a 10-second
polling interval. The killed-process case records two started requests and one completed request,
with 2 simulated seconds from restart's interrupted-read report to terminal recovery. It also emits
a bounded JSON record of actual wall-clock test duration and both request counters. Test output
therefore records recovery latency without logging provider output or using real credentials.
