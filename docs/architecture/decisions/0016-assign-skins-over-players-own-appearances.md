# 0016. Assign skins over players' own appearances

Date: 2026-10-24

## Status

Accepted. Complements [ADR 0011](0011-show-quick-skin-appearances-only-to-quick-skin-clients.md):
an assigned skin is a Quick Skin appearance and reaches only Quick Skin clients. Builds on the
admin commands of [ADR 0015](0015-show-cosmetic-nicknames-through-display-names.md).

## Context

A server owner building a Murder-style game asked for a way to give players new skins every round,
"pooling skins and having them randomly applied to players", so that nobody knows who is who.
Other players had asked to change another player's skin. The maintainer answered with a panel
for admins and commands that hand out skins.

Quick Skin keeps each player's own appearance in `ServerPlayerAppearanceRepository`. The player's
client uploads it, pins its textures (`PinnedTextureBudget`) and waits for the server's exact echo
of it before it counts as acknowledged. A Quick Skin client treats every server update about its
own player as such a confirmation and never applies it, apart from adopting a saved
`local_skin:` record once per session (RUNTIME-INVARIANTS). A skin the server imposes therefore
cannot replace the player's own record: the client would keep re-sending its own appearance, and
its acknowledgement and adoption logic would mistake the imposed skin for the player's choice.

## Decision

An admin may give a player a skin for now: other players see it instead of the player's own skin
and model until an admin takes it back, and the player sees it on themselves.

- **Over the own appearance.** The repository keeps the player's own appearance untouched.
  `ServerSkinAssignments.shownToOthers` puts the assigned skin and model in its place only in what
  `ServerNetworkHandler.sendAppearanceToPlayer` sends to recipients other than the player; the
  cape stays the player's. The player's own echoes, uploads and cooldowns work as before. A change
  of assigned skin is announced through the same appearance coordinator, so snapshots, joins and
  offline appearances carry it.
- **The player's own client.** The capability `assigned-skins` (bit 6) adds `assigned_skin`
  (S2C), which tells the player's client the skin id and model it was given, or an empty id once
  it is taken back, when it changes and when the session becomes ready. The client draws it on the
  local player (`PlayerAppearanceService.applyAssignedSkin`): the repository entry holds the
  assigned skin, while every local choice meanwhile (skin menu, saved-look restores, CPM
  withdrawals, adoption) only updates the player's own look, which is what syncs to the server and
  what shows again when the skin is taken back. Logic that asks whether the player wears a skin of
  their own (`CpmLookArbiter`, `SavedAppearanceRestorer`, the skin menu) reads that own look.
- **Where skins come from.** An admin gives one skin to many players (a skin of the server's
  pool, a skin another player shows, or one uploaded from the admin's own catalog), random pool
  skins (all different while the pool has enough), or the players' own Quick Skin skins swapped
  among them so that nobody keeps theirs (a single random cycle). The world's skin pool holds at
  most 64 named skins; PNG files in `config/quickskin-skins/` (64x64 or an HD size) join it when
  the server starts and on `skin pool reload`, named after the file, `_slim` or `_classic` choosing
  the model and the arms read otherwise.
- **Who may.** Only permission level 2, checked on the server thread for every `skin_admin`
  request and by the command tree. The request names at most 256 players; it is admitted on the
  network thread only from a v2 session with `assigned-skins`, behind the storage-mutation rate
  limit. A request that names a skin the admin is still uploading waits for that upload, at most
  four per admin and 30 seconds each; texture uploads keep every existing bound. The server answers each
  request (`skin_admin_result`) and tells admins' clients the pool and who has an assigned skin
  (`skin_admin_state`, at most 64 pool skins and 1024 players), after each change and when a
  player becomes an admin.
- **Textures.** An assigned skin is a `local_skin:` content id the texture cache holds as a skin,
  with an explicit `classic` or `slim` model. The skin of a connected player is pinned under a
  holder of its own against the same budget as players' appearances, so the cache never evicts
  it; like other appearances of players who left, an absent player's pins nothing, and one the
  cache no longer holds when they return is taken back. The pool's skins are pinned under one
  holder, as many as the budget holds. A give or a pool addition the budget cannot hold is
  refused, and so is a 4097th player.
- **Persistence.** The world's `quickskin/assigned-skins.json` keeps the assigned skins and the
  pool, read at start, saved atomically on the cache-I/O worker after each change and at stop,
  and never saved over when unreadable. A skin whose texture the cache does not hold is kept but
  neither shown nor handed out, so a cache that fails to load loses nothing. An assigned skin
  lasts until an admin takes it back, across reconnects and restarts; `skin reset` without
  players takes back everyone's, also of players who are not online.
- **Commands and panel.** `/quickskin skin set|copy|random|shuffle|reset` and
  `/quickskin skin pool list|add|remove|reload` for admins, with English fallbacks for the console
  and clients without Quick Skin; the Player skins panel, opened from the Server tab of the
  settings, for admins on a server with `assigned-skins`. It is a server tool, so it sits beside
  the server's other settings rather than in the skin menu, whose button stays "Coming soon".

## Consequences

- Players without Quick Skin never see assigned skins, as they never see Quick Skin skins; they see
  the Mojang skins of everyone. A game that hides who is who needs Quick Skin on every client.
- A player whose look is a CPM model keeps it: Quick Skin never hides a model CPM draws.
- Capes stay the player's own; a game that wants them hidden asks its players to remove them.
- Quick Skin clients without `assigned-skins` (3.1.1 and older) see other players' assigned skins,
  since the substitution happens on the server, but their own player in their own skin.
- Assigned and pool skins take pinned-texture budget; HD pool skins can fill it, and a give or a
  pool addition the budget cannot hold is refused.
- Removing a pool skin does not take it from players who wear it; deleting a folder file does not
  remove its pool skin, which `skin pool remove` does.
- Packaged E2E covers no assigned skin yet; the store, folder import, pins, wire bounds and
  negotiation have unit tests, and the commands and panel were checked by hand on Fabric 1.21.11.
