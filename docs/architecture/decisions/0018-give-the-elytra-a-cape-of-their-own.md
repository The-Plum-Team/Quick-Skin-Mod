# 0018. Give the elytra a cape of their own

Date: 2026-11-04

## Status

Accepted.

## Context

A player asked on Discord for the elytra to wear a different cape from the one on their back.
Minecraft draws the elytra with the cape's texture (its atlas holds both), and Quick Skin's
renderer hooks put the selected cape on both inputs, `PlayerSkin`'s cape and elytra textures, so
that a late profile response cannot leave a Mojang elytra beside a custom cape.

The maintainer chose the interface from mockups: Cape and Elytra tabs over the cape menu's list
(the cape editor's view buttons), and in the Cape tab a right-click item that puts a cape on the
elytra.

## Decision

- **Data.** `PlayerAppearance` gains `elytraId`: a cape id, `minecraft:elytra` for vanilla's
  elytra under any cape, or `""` for the cape (the default, and what every older peer and file
  means). It is instance-wide like the cape (`ClientConfig.activeElytraId`).
- **Rendering.** Where the elytra wear something other than the cape, the renderer hooks put that
  cape's texture (its current animation frame) on `PlayerSkin`'s elytra input, or vanilla's elytra
  texture for `minecraft:elytra` and while that cape's texture has not arrived; never the cape's,
  since vanilla falls back to the cape when that input is empty. Before a world, the saved choice
  applies to the local player as the saved cape does.
- **Protocol.** A new capability `elytra-texture` (bit 9). On a session that negotiated it,
  appearances travel as `update_appearance_elytra` and `sync_appearance_elytra`, the v2 fields
  plus the elytra's cape id; every other session keeps the immutable v1 and v2 packets and sees
  the elytra wear the cape. The elytra's cape is validated, uploaded, deferred behind its upload,
  pinned against the same budget, saved, restored and shared with offline appearances exactly as
  the cape, and its animation metadata is acknowledged like the cape's. An admin's assigned skin
  keeps the player's cape and elytra.
- **Menu.** The Cape and Elytra tabs sit over the list. The Elytra tab lists each cape as its two
  wings, with Same as Cape and Vanilla first; a click puts it on the elytra at once, the choice
  is marked purple, and the model wears elytra. In the Cape tab the elytra's cape carries a purple
  wings badge, and the right-click menu offers to wear a cape on the elytra or, on the elytra's
  cape, to give them the cape again. Deleting or editing that cape follows it.

## Consequences

- A player wearing a separate elytra cape on a server without the capability shows the cape on the
  elytra to everyone there; nothing is lost when they return to a newer server.
- Two packets more in each loader's channel set; the old packets keep their schemas.
