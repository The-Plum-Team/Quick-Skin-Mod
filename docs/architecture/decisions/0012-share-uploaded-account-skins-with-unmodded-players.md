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
A server in offline mode never receives Mojang-signed profiles. A backend behind BungeeCord or
Velocity does receive them: the proxy authenticates the player with Mojang and forwards the signed
properties (legacy IP forwarding or Velocity's modern forwarding). But the backend itself runs with
`online-mode=false`, so it did not authenticate the player and cannot tell a genuinely forwarded
profile from one a client made up when it is reachable directly; the proxy, not the backend, owns
that trust. Custom capes, HD skins and CPM models can never be delivered this way.

Online mode alone does not prove that the profiles are Mojang's either. authlib can be pointed at
another Yggdrasil service through the `minecraft.api.*` system properties, and the authlib-injector
Java agent rewrites Mojang's URLs and adds its own signing key (Ely.by, LittleSkin, Drasl and
similar services). Their profile ids are not Mojang account ids, and some let users choose their
id, so a lookup of that id on Mojang's session server could return another person's account skin.

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
- The server ignores the report when the option is off, and logs that the option has no effect,
  at startup and for each report, on an offline-mode server (proxy backends included: they keep
  running in offline mode, see above) and on a server that does not authenticate players with
  Mojang's own session server. The latter is detected from what the running JVM actually uses:
  authlib's environment parser and every `minecraft.api.*` property (only an explicit `PROD`
  environment is accepted), any `authlibinjector.*` property, an authlib-injector `-javaagent`
  argument or its loaded agent class, and a session-server URL that no longer names
  `sessionserver.mojang.com` over HTTPS (an agent rewrote it). On such a server no profile id is
  ever sent to Mojang.
- Signatures are checked against Mojang's own published profile property keys (from
  `api.minecraftservices.com/publickeys`, built into `TexturesSignatureVerifier`), never against
  the keys the running authlib trusts, which authlib-injector extends. The report is refused when
  the textures the player currently carries (from login) are signed by anyone else; a player
  without signed textures is accepted, because the environment check above already holds. The
  fetched textures must verify too, and Mojang's name for the profile must equal the connected
  player's name. Every refusal is logged. If Mojang starts signing with a key that is not built in,
  sharing fails closed until the key is added.
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
  exact session's `textures` property by building a new `GameProfile` and installing it through a
  `Player.gameProfile` accessor, on every version: from Minecraft 1.21.9 profiles are immutable
  records, and before that the property map must not be edited while a network thread may still
  be encoding a queued player-info packet that holds it. Before 1.20.2 the profile cache entry
  that skulls read is the login profile itself, so it is replaced too; later versions resolve
  skulls through the session server or cache only names. It then sends each online player who
  does not run Quick Skin a player-info removal and the initialising player-info update, and for
  those who track the player a new entity pairing through the `ChunkMap` tracker, followed by a
  camera packet for a spectator who watches through the player's eyes (it would otherwise keep
  the removed entity as its camera). Quick Skin observers and the uploader are left alone; players
  who join later read the updated profile from the ordinary join packets.
- The client reports the upload from the upload's own success path, so closing the dialog while
  the upload runs does not lose the report. The server sends, with its synchronized
  configuration, what unmodded players will see (`accountSkinVisibility`: `shared` when the option
  is on and the server authenticates with Mojang, `after_rejoin` when it is off on such a server,
  `unavailable` in offline mode or with another authentication service). This value is computed
  for every sync and never stored in `quickskin-server.json`. The upload dialog shows the matching
  text, and a neutral one when the server did not say.

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
- Skin-changing server mods and plugins (SkinsRestorer and similar) replace the `textures`
  property themselves. An explicit upload report overrides their skin with the account skin for
  unmodded observers until the plugin applies its own skin again, for example on the next join.
  Operators who want such a plugin to stay authoritative should leave the option off.
- Vanish mods: the refresh is sent through each observer's ordinary connection, so a vanish mod
  that filters player-info and entity packets per receiver (the usual implementation) keeps
  hiding the player. The entity re-pairing goes only to observers whose tracker already sees the
  player. Vanilla keeps no per-observer record of tab-list entries, so the player-info re-addition
  cannot be limited to observers that already list the player without intercepting every outgoing
  packet; a vanish mod that hides a player once and then relies on nothing re-adding it could
  reveal that player to unmodded observers. Such servers should leave the option off.
- Observers that run Quick Skin are skipped: they keep the appearance the Quick Skin protocol
  gives them.
- Packaged E2E runs offline-mode servers with offline clients, so the online path cannot run in
  CI. Unit tests cover response validation, the Mojang signature check (with textures that
  Mojang's session server really signed), the authentication-environment detection, pacing, the
  configuration gate and visibility sync, report admission, observer selection and the profile
  update; the refresh packets were inspected on a local Forge 1.20.1 server with an unmodded client using
  a local test hook. Rendering of a real Mojang-signed skin requires a manual check with two
  authenticated accounts.
