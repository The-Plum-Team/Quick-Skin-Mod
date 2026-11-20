# 0019. Show immersive poses to other players

Date: 2026-11-19

## Status

Accepted.

## Context

In the immersive view of the skin menu (the menu floats in the world round the real player, the
camera in front of them) the player plays the menu's poses: the idle, the walk in place, the sit
on a grass block with its jumps onto it and off it, and the move the model makes when another skin
is picked. Until now only that player's own client drew them: the poses are a change of drawing
(`WorldPose`), nothing was sent, and everyone else saw the player standing still. The maintainer
asked for other players to see them too.

A Quick Skin appearance already reaches only Quick Skin clients (ADR 0011), and a player without
Quick Skin has nothing that could draw a pose, so only Quick Skin clients can be shown one. The
pose must never change the player themselves: their position, hitbox and movement stay the game's.

## Decision

- **Protocol.** A new capability `immersive-poses` (bit 10; master's last was `elytra-texture`,
  bit 9, and no other branch had claimed a later one). The client sends a C2S `immersive_pose`:
  its player's UUID, a sequence number, and the pose (`ImmersivePose`): pose id (none, idle, walk,
  sit), the yaw the body faces, a closing flag, a counter of the skin-change moves and which move
  the latest was. It is 8 bytes after the UUID and sequence, with no string or array, so it fits
  every vanilla payload limit (`WirePayloadBudget.immersivePose`). The server relays the same pose
  in an S2C `sync_immersive_pose` with the posing player's UUID. Both have one codec in every era
  (`ImmersivePoseCodec`), registered as payloads from 1.21 and as channels in the 1.20.1 overlay.
- **The posing client.** A client tick (`ImmersivePoseBroadcast`, in the composition root) reads
  what the immersive view plays: the preview the skin menu names to `WorldPose.play` for the current
  view, its pose, the view's facing yaw (`ImmersiveMode.faceYaw`, wrapped into one turn), whether
  the view is closing, and each skin-change move once. `ImmersivePoseSyncService` sends a change at
  most every 250 ms, but the start, the start of the close and the end at once, and repeats an
  unchanged pose every 5 s. It sends only on a session that negotiated the capability, never in a
  replay, and nothing while the server says it does not share poses. A CPM model plays no pose,
  as in the view itself.
- **The server.** `ServerImmersivePoses` admits a pose on the network thread only from the exact
  negotiated v2 session with the capability, about the sender itself, behind its own per-session
  token bucket (`TextureTransferRateLimiter.allowImmersivePose`, apart from the storage budget a
  player browsing skins in that menu spends). On the server thread `ImmersivePoseRelay` drops a
  pose out of sequence, from a replaced session or beyond a second budget (10 at once, then 4 a
  second; ending a pose is never refused), keeps a repeat without telling anyone, and ends a pose
  not repeated for 15 s. A change goes only to the other Quick Skin clients the game's entity
  tracking pairs with the player (`ChunkMap.TrackedEntity.seenBy`, read through the accessors of
  ADR 0012 and bound by the composition root), so a client far away, or one a vanish mod keeps
  the player from, learns nothing. Once a tick the server follows that set
  (`ImmersivePoseAudience`): a client that starts to see a posing player (it came into range,
  joined, changed world or respawned) is told the pose as already under way (`SETTLED`, a flag
  only the server sets), and one that stops seeing them is told it ended. A pose ends for
  everyone told when its client ends it, when the player leaves, dies, respawns, changes world or
  becomes a spectator, and while the new option `allowImmersivePoses` is off.
- **Other clients.** `ClientNetworkHandler` validates the pose and keeps it per player with the
  world it was told in (`RemoteImmersivePoses`, client-foundation, bounded), behind a packet
  window of its own (`ClientTextureIngressLimiter.allowImmersivePosePacket`, 4096 per 10 s, apart
  from the appearance packets' control budget), forgets the poses of a world it leaves, and
  ignores a pose of the local player. `WorldPose` keeps one animation state per posing player, keyed by UUID, and
  plays it on the viewer's own clock: the first pose heard starts as it is (no jump), a later
  change of pose plays the menu's jump onto the block or off it, a new move counter plays the told
  move, and the body turns towards the told yaw over the view's own 0.65 s (already turned for a
  settled pose) and back over 0.4 s once the view closes. The draw is the local player's: the
  body turned, lifted and scaled before the game poses the model, the sit block drawn under it,
  the model given the menu's pose, and the cape and elytra hung from the posed body. A player
  who is sneaking, swimming, gliding, riding or asleep, or whom CPM draws a model for, keeps the
  game's own pose, and one this client cannot see (invisible) shows neither pose nor block, as
  the game shows nothing there. Nothing of the player entity changes. The drawing exists on every
  supported version, as the immersive view does: before 1.21.2 the draw is the player entity's, from
  1.21.2 the render state's, bound to its player by the state's entity id. The protocol, server and
  store are the same on every version.
- **Option.** `allowImmersivePoses` defaults to on: the poses change nothing but drawing on Quick
  Skin clients, cost a few bytes per change, and a player in that menu cannot act anyway, so there
  is no gameplay reason to hide them by default. A server that wants none turns it off; the
  server then keeps and relays nothing and clients stop sending.

## Consequences

- Other Quick Skin players see a player in the immersive skin menu play its poses, turned the
  way that player's own client turns them, and see them return to normal when the menu closes.
- Players without Quick Skin and players on a Quick Skin server older than this see the player
  standing, as before.
- A Quick Skin client learns of the poses of the players the game shows it, and of no other. A
  pose says only which of the menu's poses a player plays and the way they face, never what they
  look at in the menu.
- The server looks at the entity trackers of the posing players once a tick; with nobody posing
  it looks at nothing.
- The pose state is the client's own drawing: two clients may show a different idle at a time,
  since each picks its idles itself; the pose, the jumps and the moves are the same.
- Unit tests cover the pose bounds and names, the relay's sequence, rate, repeat and expiry rules,
  who is told as the tracked set changes, admission, the capability, the option, the wire budget,
  the client's packet window, the client store and the told move.
- Checked by hand with two clients on a packaged dedicated server, Forge 1.20.1 and Fabric 1.20.1
  (each with its own loader's server; a Forge client on a Fabric server runs no Quick Skin at all,
  as before): idle, sit with both jumps, walk, the skin-change move, the turn, the close, a viewer
  coming into range and a viewer joining while the pose plays. When the view was brought to the
  later versions, the same with two Fabric clients on a packaged Fabric server of 1.21.4, 1.21.8,
  1.21.11 and 26.3 (the turn, the jumps, sitting, walking, the skin-change move and the close);
  not on NeoForge, nor the viewer coming into range or joining.
