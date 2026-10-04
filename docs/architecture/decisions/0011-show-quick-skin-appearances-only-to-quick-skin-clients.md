# 0011. Show Quick Skin appearances only to Quick Skin clients

Date: 2026-10-01

## Status

Accepted.

## Context

Issue #2017 asked whether a server running Quick Skin can show a player's selected skin to
observers who do not run the mod. Quick Skin sends appearances and texture bytes only on its own
negotiated channels: `ServerNetworkHandler.sendAppearanceToPlayer` and the join path in
`CommonEvents` skip every recipient for whom `ProtocolNetwork.canReceive` is false, and the skin is
applied by client-only mixins. The server never changes the vanilla `GameProfile`.

An unmodded client takes a player's skin from the `textures` property of that profile and applies
two rules that a server cannot satisfy by itself. Both were read in the client and authlib jars of
every target in the release matrix on this date (1.20.1, 1.21.1 to 1.21.11 and 26.1 to 26.3):

- The texture URL must be on a Mojang host. authlib's `TextureUrlChecker` accepts `.minecraft.net`
  and `.mojang.com` up to authlib 7.0.61 (Minecraft 1.21.11) and only `textures.minecraft.net` in
  7.0.63 and 9.0.75 (26.1 to 26.2). authlib 10 (26.3) requires the URL to match the texture
  endpoint published by Mojang's service discovery.
- For every player other than the local one the property must carry a valid Mojang signature.
  1.20.1 loads other players' skins with `requireSecure = true`; from 1.21.1 on, the lookup
  returns the default skin unless `PlayerSkin.secure()` is true, which requires
  `SignatureState.SIGNED`.

A resource pack cannot replace this: the 1.21.9 `PlayerSkin.Patch` texture override applies to
player heads and mannequins, while a real player's player-info entry carries only the profile. On
1.20.1 a client that receives a profile without a `textures` property fetches the signed profile
from Mojang itself; later versions show the default skin.

On an offline-mode server the profile has no `textures` property, so unmodded observers see a
default skin and nothing a player or the server owns can change that; only a third-party signing
service could.

A skin therefore reaches an unmodded observer only after Mojang has stored and signed it. Two
sources exist: the player's own account, which Quick Skin already supports through Upload to
Mojang, and third-party signing services such as MineSkin, which upload to their own accounts and
need an operator API key. Both are limited to standard 64x64 or legacy 64x32 skins with the classic
or slim model; custom capes, HD skins and CPM models cannot be delivered.

## Decision

Quick Skin appearances stay on the Quick Skin protocol and are visible only to clients that run
the mod. The server does not rewrite the vanilla profile and does not call a signing service. This
is a decision not to build the server-side route now; it is technically possible through the two
extensions below.

Players with a paid account on an online-mode server who want an unmodded audience use Upload to
Mojang; other players see the result after the uploader rejoins, or after a LAN host restarts the
game (from 1.21.1 the host's profile is fetched once per game launch). `README.md` states these
limits.

## Consequences

- Synchronization between Quick Skin clients is unchanged.
- The closest viable extension is a server-side refresh after a Mojang upload: fetch the signed
  profile again, replace the `textures` property and resend the player-info entry and the entity
  to observers without the mod. It needs a new optional v2 capability, a session-service call that
  differs across authlib 4, 6 to 9 and 10, a mutable-profile accessor from 1.21.9 (the profile is
  a record), and tracked-entity accessors. Packaged E2E runs offline clients, so it could only be
  verified by hand with two authenticated accounts.
- A signing-service integration would work without changing the player's account and on
  offline-mode servers, at the cost of a third-party dependency, an operator API key, rate limits
  and uploading players' skins to that service. It requires a new decision record.
