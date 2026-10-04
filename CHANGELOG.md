# Changelog

## 3.1.0 (2026-10-05)

### Added

- FancyMenu is now a supported integration. The player preview on the title screen and the pause
  menu can be hidden, moved and resized in FancyMenu's layout editor (element
  `quickskin_player_preview`), and a resized box scales the model. Hiding the preview also hides
  its rotate and animation buttons. The Change Skin, rotate and animation buttons have their own
  FancyMenu ids, so they can be customized separately.
- Added Show Title Screen Preview and Show Pause Menu Preview to the Client tab of the settings, to
  turn off the player preview on either screen. The Change Skin button stays.
- Capes imported through the cape editor now have an Edit button that reopens the editor with the
  original image (every frame of a GIF) and updates the cape in place.
- Pressing Enter in the username field of the skin search now starts the search.
- With a TaCZ gun in hand, the in-game player preview now shows the gun, and it plays TaCZ's
  reload, recoil and melee animations in first person too.

### Fixed

- Fixed the in-game player preview twitching while a TaCZ gun is held.
- Fixed the Modrinth, CurseForge, Discord and Settings buttons of the skin menu becoming impossible
  to click, or hidden behind the model, when the player preview was moved or enlarged over them.
- Fixed a startup crash on NeoForge when Quick Skin and ImagineBook are installed together. On
  Forge and NeoForge the bundled WebP library is now shared with other mods that ship it.
- Fixed Customizable Player Models (CPM) models embedded in a skin PNG: importing such a skin no
  longer breaks the model, and other players on the server now see it too.
- Quick Skin and CPM no longer undo each other's choices: whichever look was picked last, in Quick
  Skin or in CPM, is the one you and other players see, also after a relog or a restart.
- Fixed Real Camera turning off its first-person body ("Binding failed") with a Quick Skin skin.
- Entity Texture Features can now read Quick Skin skins, so its skin features (emissive pixels,
  blinking, and so on) work again.
- Ears features (snout, ears, tail and so on) show again on Quick Skin skins with Ears 2.x.
- On Minecraft 1.20.1, your own player is now recognised when an offline-mode server or a proxy
  gives you a different UUID from your launcher account.
- On servers that still run Quick Skin 2.x, a skin or cape larger than 1 MiB is no longer uploaded,
  because the server would relay it as one oversized packet that could disconnect other players or
  show them a `VarInt too big` error. You still see it yourself; other players see you without it.

### Changed

- Skin selections now belong to the launcher account instead of the instance. When an instance is
  launched with another account, that account uses its own choice, or, if it has none in this
  instance, the skin the server saved for it is restored instead of being overwritten.
- A failed username skin search now says which step failed (the player lookup, the profile or the
  skin download) instead of always reporting "Player not found", and writes one line to the log.
- The README now explains that skins and capes chosen in Quick Skin are shown only to players who
  also run Quick Skin.

## 3.0.1 (2026-09-27)

### Fixed

- Fixed skins and capes never syncing in large modpacks. On Forge 1.20.1, when the client was slow
  to process the server's list of network channels (for example while JEI was still loading),
  Quick Skin decided on joining that the server did not have the mod and kept every appearance
  local for the whole session. The client now keeps checking for as long as it is connected and
  starts syncing as soon as the server's channels show up.
- Servers now also recognise Quick Skin 2.x clients whose channels arrive after they join.
- The server now saves a player's skin and cape as soon as they change, instead of only when the
  player leaves or the server stops, so a crash no longer loses the changes made in that session.

### Changed

- On Forge, skins and capes now load right after joining, even in large modpacks. Quick Skin reads
  the server's mod list from Forge's login handshake instead of waiting for the channel list, so
  appearances sync within a few seconds instead of after long loading freezes such as JEI's
  startup (about 40 seconds in a 370-mod pack).
- Added log messages for when the connection with the server is set up, when the server's
  channels arrive late, and when the server does not answer.

**Note: work on the features and bugs reported on Discord starts tomorrow; they should most likely
be ready by the weekend.**

## 3.0.0 (2026-08-09)

### Added

- Added high-resolution cape support, together with a cape editor that repositions, scales, and
  zooms a cape, previews it on the front, the back, and the elytra, and can flatten cape
  transparency onto a colour chosen with red, green, and blue sliders or a hex field.

### Changed

- Quick Skin's source code is now published at <https://github.com/The-Plum-Team/Quick-Skin-Mod>.

## 2.6.2.5

### Fixed

- Fixed an `IndexOutOfBoundsException` when rendering the skin-list drop zone with one or two skins loaded.
