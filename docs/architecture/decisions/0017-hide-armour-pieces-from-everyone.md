# 0017. Hide armour pieces from everyone

Date: 2026-10-24

## Status

Accepted. Amends the preview rule of RUNTIME-INVARIANTS ("a preview hides everything the previewed
player wears or holds"): the skin menu's and the pause menu's previews now show it.

## Context

The maintainer asked for the player's model in the pause menu and in the skin menu to wear what
the player wears, and for a click on a piece of armour there to hide it in game, so that other
players do not see it. Asked how, they chose: each piece on its own; hidden from everyone,
players without Quick Skin included; and a server option to turn it off, since a hidden piece
still protects and that matters in PvP.

Until now every Quick Skin preview hid all equipment (`PreviewEquipmentPolicy`): the cape editor
drew the worn elytra over the cape being edited, and a helmet covered the face of the skin menu's
preview. The skin menu does not draw the live player: it draws its own posed model, so it can show
a skin before it is chosen and play its poses.

A Quick Skin appearance reaches only Quick Skin clients (ADR 0011), but equipment reaches every
client through vanilla's `ClientboundSetEquipmentPacket`, sent when a piece changes and, inside a
bundle, when a player comes into view.

## Decision

- **Hiding.** The client keeps, per launcher account (`ClientConfig.accountHiddenArmor`), the
  pieces the account hides: one bit each for the head, chest (chestplate or elytra), legs and feet
  slots (`HiddenArmor`). It tells the server in a new C2S `armor_visibility`, under the new
  capability `hidden-armor` (bit 7), when the session negotiates and whenever the player changes
  it; the server admits only a negotiated v2 session naming its own player, behind the
  storage-mutation rate limit.
- **Everyone else.** `ServerHiddenArmor` keeps the pieces of each connected player, released with
  the exact session. A mixin on the play connection's `send(Packet)` passes every packet to
  `ServerHiddenArmor.filter`, which rewrites another player's equipment packet, alone or in a
  bundle, with the hidden pieces empty. The player's own equipment and every other packet pass
  unchanged. A change is sent at once to the other players of the world. Nothing is ever written
  to the player: the item stays worn and protects. `allowHiddenArmor` (default on) turns the
  filtering off and makes clients say so.
- **The player.** Where the server hides the pieces, the player's own client does not draw them
  either, in third person, in the inventory and in the previews: before 1.21.2 the armour, elytra
  and worn-head layers skip them when they draw the local player (NeoForge 1.21.1 draws armour
  through its own overload, which is hooked too), and from 1.21.2 the player renderer's extraction
  leaves them out of the render state. Reads of the equipment are never answered differently, so
  client logic such as starting to glide keeps seeing the worn elytra.
- **Previews.** The pause menu's preview of the live player shows its equipment as the world does.
  The skin menu's own model borrows the equipment layers (armour, held items, worn head, elytra)
  of the player renderer of the previewed model type, poses that renderer's model like the preview
  and draws them over it; from 1.21.9, where armour models pose themselves from the render state
  when the submitted features are drawn, the preview's pose is bound to that state and applied at
  the end of `HumanoidModel.setupAnim`. The elytra layer draws from the preview's body, as the
  cape does: vanilla hangs the wings from the player's root, where the body always is in the world,
  but the preview's walk, sit and idles lean, lower and twist the body. Before 1.21.2 the renderer
  Minecraft picks for the player takes the preview's pose as well, because accessories pose
  themselves from it (Curios' `followBodyRotations`, which Relics' necklaces use) and it is the
  other model type's renderer while the menu shows a skin of the other type. Before 1.21.2 each
  renderer model the preview poses gets its own pose back once the layers have drawn: the world's
  next draw poses only part of each model part again, so the sit's offsets stayed on the live
  player, which the cape menu and the world then drew half seated. The cape menus and the cape
  editor still hide everything.
- **Clicks.** Hovering a worn piece on the skin menu's model closes four corner marks in round
  what is seen of it, as the import zone's corners frame it. A hidden piece is not drawn on the model
  and is never picked there, where it would cover a piece behind it, such as a second glove on the
  same hand: it lies on the floor round the turntable ring as its item, turning and bobbing as a
  dropped item does (`PedestalDrops`). The first two lie on the ring's front beside the feet, the
  rest beyond the ring, spaced for every piece the player wears. Each keeps its place while it
  lies there: a piece put back on leaves its place empty and the next one hidden takes the first
  empty place, so nothing the player is about to click moves. A piece just hidden hops up
  off the model, falls there and bounces twice, and a click on it puts it back on and it flies up to the model; hovering it
  holds it still, names it in a tooltip that hangs under the ring below it, clear of the model and of the other pieces, and shows it on the model, where it goes, with marks round both.
  It fades in there and out when the mouse leaves; a piece just clicked is left unmarked until
  the mouse leaves it. A piece another mod takes off while the player is drawn, in the world as in
  the preview, is covered rather than hidden: Relics' boots, shown in Curios' feet slot, take off
  the armour boots under them. The preview compares the armour before `RenderPlayerEvent.Pre`
  with what its draw finds, and a covered piece lies on the floor too, darker, with the covering
  accessory (the one shown in the Curios slot of that body part) as a badge in its corner; hovering
  it says what covers it and marks that accessory on the model, and a click hides the accessory so
  the piece shows. Quick Skin cannot tell the covering mod to stand down, so this is the only way
  to show the piece; when no Curios accessory sits in that slot, the tooltip says another mod's
  piece covers it and a click does nothing. (Until the floor came, hovering a worn piece made it a see-through ghost,
  as hiding it would; once hiding moved the piece to the floor, pointing it out read better.) The
  preview's draw leaves the shown piece out, and once the rest of the preview is drawn, the cape
  included, the piece's layers draw it alone again, every texture swapped for vanilla's
  see-through entity draw of the same texture at the fading alpha (`PreviewEquipment.renderGhost`;
  from 1.21.9 a collector passes the layers' submissions on in a later order, with the tint's
  alpha scaled), so modded armour, trims and dyes keep their look. The glint is left off; a draw
  that names no texture (a worn item from 1.21.9, a player head) stays opaque.
  The silhouette (`PreviewArmorSilhouette`) comes from the piece's layers drawing it once more,
  alone and starting from the transform the preview drew its model with this frame, into a sink
  that keeps the quads instead of drawing them (from 1.21.9 a collector, told apart from its
  methods by their parameter types, poses and draws each submitted model into it). The quads are
  filled pixel by pixel where their texture keeps its texels, so a helmet's shape, an elytra or a
  mod's own armour model come out as drawn; the glint and trims are left out. The mouse is over
  what is seen there: the fill also keeps each pixel's depth, the model itself is traced the same
  way, and of the pieces under the mouse the one nearest the viewer counts, unless the model is
  nearer, so hovering the chest in front of an elytra selects nothing (the boxes the piece covers
  stand in when it cannot be traced).
  A click released in place hides or shows it. On a server without the capability, or with the
  option off, the click only says why nothing changes.

## Consequences

- A hidden elytra still flies; others see a player gliding without wings, and its cape, if any.
  Before 1.21.2 the player's own cape stays hidden behind a hidden elytra, as vanilla's cape layer
  still finds it in the chest slot.
- A hidden piece does not show on any client, so armour-reading client mods (an armour HUD of
  other players) see it empty too; the server's own logic, and other mods on the server, still
  see it worn.
- On NeoForge the new mixins live in their own configuration (`quickskin-armor.mixins.json`), since
  NeoForge does not read `quickskin.mixins.json`.
- The skin menu's poses move the borrowed renderer's model; the world poses it again before every
  draw, so nothing shows outside the menu.
- Packaged E2E covers no hidden armour yet, and the skin menu's and pause menu's captures now draw
  whatever the scenario's player wears at that moment. The mask, admission, configuration and wire
  bounds have unit tests, and the feature was checked by hand on Fabric 1.21.11 and 1.21.1 with two
  clients.
