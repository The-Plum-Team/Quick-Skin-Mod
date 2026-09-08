# 0008. Transport the installed loader server between jobs

Date: 2026-09-08

## Status

Accepted. Supersedes the "Do not upload `RuntimeStore` from GitHub-hosted jobs" consequence of
[ADR 0003](0003-standardize-packaged-e2e-platform.md); every other part of that decision stands.

## Context

Both the Forge and the NeoForge installer download their own Maven libraries while installing a
server. Quick Skin pins every byte it fetches itself, through the release matrix for installers and
through Gradle's verification metadata for dependencies, but those libraries belong to the
installer and are outside that inventory.

Generation `5efc90cd` lost eight optional-mod compatibility waves to that download. Every failure
was the same:

```
NeoForge server installation failed after 3 isolated attempts
These libraries failed to download. Try again.
net.neoforged.installertools:installertools:3.0.5
net.neoforged.installertools:cli-utils:3.0.5
```

The stack ends in the TLS socket while parsing the HTTP response header, so the connection died
mid-response rather than returning a 404, and sibling libraries from the same host validated their
checksums seconds earlier. Measured directly, `cli-utils-3.0.5.jar` is nine kilobytes and answered
in 5.08 s on the first request and in 0.056 s on the next. These observations establish variable
download latency and connection failures; they do not establish a CDN-cache or throttling cause.
Sixteen waves start at once, each with several lanes, so avoiding repeated downloads reduces
exposure to those failures regardless of their upstream cause.

`prepare_server` previously ran that install once per scenario, so a compatibility lane performed
eleven of them. That is fixed separately by routing the install through `RuntimeStore`, which
leaves one install per recipe per job. Around 140 lanes per generation still repeat the same
~16 installs, because the store is destroyed with the job.

ADR 0003 forbade persisting the store on GitHub-hosted runners. Its own context records that the
poisoned install which motivated that decision was "a partial tree reused by later scenarios in
the same job, not a cross-run Actions cache". The prohibition was conservative scoping, not a
mitigation of that incident.

## Decision

- The installed loader server moves into its own store, separate from the much larger client
  install, so it can be transported on its own. Both remain content-addressed stores with
  identical validation.
- A packaged-runtime job restores that store from an Actions cache keyed by the exact server
  recipe digest. The key carries no commit: an installed tree stays valid for every commit that
  keeps the same matrix row and installer.
- Only Forge and NeoForge servers travel. A Fabric server install fetches the vanilla server
  through Mojang's hash-pinned manifest and does not resolve libraries from a Maven repository.
- Restores use the exact key with no prefix fallback. A near miss must never satisfy a lookup the
  recipe digest exists to make exact.
- Only immutable content-addressed material travels: `blobs`, `recipes` and `trees`. Leases,
  staging and quarantine are machine-local run state whose OS locks and device/inode identities
  are meaningless on another runner.
- Writing is restricted the way Gradle's home cache already is. `scripts/ci/runtime_store_cache_policy.py`
  is a fail-closed CLI that approves only a protected `master` dispatch; pull requests, tags,
  ephemeral branches and unknown input restore read-only.
- A restore is always optional. The store validates the restored recipe record, its tree manifest
  and every blob before use, so unusable material is a cache miss and a fresh install, never a
  gate failure.

## Consequences

- The repeated unpinned third-party download becomes one download per recipe rather than one per
  job. Combined with the per-recipe install, a compatibility lane goes from eleven server installs
  to at most one, and usually to none.
- This is a supply-chain change and should be reviewed as one. The store is self-consistent but
  has no provenance anchor: a recipe record binds a recipe digest to a tree digest, and every
  deeper layer faithfully validates whatever tree that record names. Today the link to a
  hash-pinned installer is that the tree was built in the same job. The write policy replaces that
  link: only a protected `master` generation can publish an entry, and GitHub scopes a pull
  request's own writes to its merge ref, so a fork cannot reach `master`'s entry.
- Pinning an expected recipe-to-tree binding in a reviewed in-repo lock would be a stronger anchor.
  It is deliberately not part of this decision, because it requires evidence that the installers
  are byte-deterministic across runs, which we do not have.
- Sixteen entries share GitHub's ten gigabyte repository cache budget with the Gradle home caches.
  Eviction degrades gracefully to today's behaviour, a fresh install, but it can also evict a
  Gradle generation. The first protected generation measures the real entry sizes; widening the
  transport to client installs is gated on that measurement.
- Retention needs no new pruner family. GitHub removes a cache entry that has not been read for
  seven days, and a recipe that leaves the release matrix is never restored again, so it expires on
  its own. The cache pruner deliberately keeps discovering live branches from the API and never
  reads the release matrix.
- A warm cache can hide an upstream installer regression until the recipe identity changes. That
  identity already covers the installer hash, the loader version and the Minecraft version, so a
  genuine upstream change still forces a fresh install.
