# Changelog

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
