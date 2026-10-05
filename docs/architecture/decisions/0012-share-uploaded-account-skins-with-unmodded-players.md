# 0012. Share uploaded account skins with unmodded players

Date: 2026-10-06

## Status

Accepted. Amends [ADR 0011](0011-show-quick-skin-appearances-only-to-quick-skin-clients.md).

## Context

ADR 0011 kept every Quick Skin appearance on the Quick Skin protocol and named the closest viable
extension: refresh the signed vanilla profile after a player uploads a skin to their own Mojang
account. Issue #2052 asked for that route: a server running Quick Skin should let players without
the mod see a Quick Skin player's skin. The maintainer authorised revisiting ADR 0011 for it.

The limits that ADR 0011 recorded still hold for every target in the release matrix. An unmodded
client renders another player's skin only from a `textures` property that Mojang signed and that
points at a Mojang texture host. Only Mojang can produce that property, for a skin stored on a
Mojang account, in the standard 64x64 or legacy 64x32 layout with the classic or slim model.
Offline-mode servers, and backends behind a proxy (which run in offline mode), have no signed
profiles at all. Custom capes, HD skins and CPM models can never be delivered this way.

Quick Skin already uploads a skin to the player's own account (Upload to Mojang). Until now other
players saw the new account skin only after the uploader rejoined, because the server keeps the
profile it received at login and an unmodded client keeps the player-info entry, and the entity
keeps the entry it resolved first.

## Decision

Servers may opt in to sharing uploaded account skins with players who do not run Quick Skin. The
server option `shareAccountSkinWithVanillaClients` in `quickskin-server.json` is off by default.

- Quick Skin never uploads a skin by itself. After an explicit, successful Upload to Mojang the
  client sends `quickskin:account_skin_changed` to the server, only on a negotiated v2 session
  that advertises the new optional capability `account-skin-refresh` (bit 4). Older peers never
  negotiate it and are unaffected.
- The server ignores the report when the option is off, and on an offline-mode server, where it
  logs that the option has no effect, at startup and for each report.
- Otherwise `AccountSkinShareService` (`server-appearance`) reads the player's profile from
  `https://sessionserver.mojang.com/session/minecraft/profile/<id>?unsigned=false` on one bounded
  daemon worker, never on the server thread. The request is anonymous and carries no token or
  key. Lookups are paced by a server-wide token bucket (one every two seconds, burst of ten); a
  player starts at most one round every thirty seconds and a report during a round re-arms one
  more; a round makes at most six lookups with backoff (2 to 60 seconds), honours `Retry-After`,
  and stops early once the session server reports a different appearance than the player's
  current profile. Responses are bounded to 64 KiB and must be strict JSON naming the requested
  profile, with exactly one signed `textures` property whose payload names the same profile.
- On the server thread the composition root (`AccountSkinProfileTarget` in `common`) replaces the
  exact session's `textures` property: in place before authlib 7, and by installing a new
  `GameProfile` through a `Player.gameProfile` accessor from Minecraft 1.21.9, where profiles are
  immutable records. It then sends each online player who does not run Quick Skin a player-info
  removal and the initialising player-info update, and for those who track the player a new entity
  pairing through the `ChunkMap` tracker. Quick Skin observers and the uploader are left alone;
  players who join later read the updated profile from the ordinary join packets.
- The upload dialog says whether the connected server announced the option, and otherwise that
  unmodded players see the new skin after a rejoin.

A third-party signing service stays excluded: it would upload players' skins to someone else's
account and needs an operator key. That would require another decision record.

## Consequences

- Synchronization between Quick Skin clients is unchanged, and so is everything a server does
  while the option is off.
- Unmodded observers see the player's Mojang account skin, which is the uploaded skin, not
  necessarily the skin currently selected in Quick Skin. Capes, HD skins and CPM models remain
  visible only to Quick Skin clients.
- An enabled server makes anonymous HTTPS requests to Mojang's session server. The pacing above
  keeps them well inside Mojang's published limits; a rate-limited or failing lookup only delays
  or skips the refresh.
- An unmodded observer briefly loses and regains the player in its tab list and world when the
  refresh arrives.
- Packaged E2E runs offline-mode servers with offline clients, so the online path cannot run in
  CI. Unit tests cover response validation, pacing, the configuration gate and the profile update;
  the refresh packets were inspected on a local Forge 1.20.1 server with an unmodded client using
  a local test hook. Rendering of a real Mojang-signed skin requires a manual check with two
  authenticated accounts.
