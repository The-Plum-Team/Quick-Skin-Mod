# Changelog

## 3.1.0 (2026-10-05)

### Added

- **FancyMenu compatibility**
> You can now hide, move and resize the player preview of the title screen and the pause menu from FancyMenu's layout editor. Hiding it also hides its rotate and animation buttons.
> *@Lappland and @EC thx for the idea!*

- **Option to hide the player preview in the menus**
> The new "Show Title Screen Preview" and "Show Pause Menu Preview" settings, in the Client tab, turn off the big player model on the title screen or on the pause menu. The Change Skin button stays.
> *@Lappland and @EC thx for the idea!*

- **Edit button for capes made with the cape editor**
> Capes imported through the cape editor now have an Edit button that reopens the editor with the original image (every frame of a GIF included) and updates the cape in place.

- **TaCZ guns in the in-game player preview**
> With a TaCZ gun in hand, the in-game player preview now shows the gun and plays its reload, recoil and melee animations, also in first person.

- **Press Enter to search a player's skin**
> Pressing Enter in the username field now starts the search, no need to click the button.

### Fixes

- **The in-game player preview no longer twitches**
> The mini player model shown while playing was shaking when holding a TaCZ gun or with animation mods installed. It now stays steady.
> *@The Man with Somewhat of a Name thx for reporting it!*

- **The skin menu buttons can always be clicked**
> The Modrinth, CurseForge, Discord and Settings buttons no longer hide behind the player model or stop working when the preview is moved or enlarged over them.
> *@J20 MC thx for reporting it!*

- **No more crash with ImagineBook**
> Fixed a startup crash on NeoForge when Quick Skin and ImagineBook were installed together.
> *@svxer thx for reporting it!*

- **CPM models inside skins work again**
> Importing a skin that carries a Customizable Player Models model no longer breaks the model, and other players on the server now see it too.
> *@kiura thx for reporting it!*

- **Quick Skin and CPM no longer undo each other**
> Whatever you picked last, in Quick Skin or in CPM, is what you and other players see, also after relogging or restarting the game.
> *@kiura thx for reporting it!*

- **Real Camera compatibility**
> Real Camera no longer turns off its first-person body ("Binding failed") when you use a Quick Skin skin.
> *@I3AT5 thx for reporting it!*

- **Entity Texture Features compatibility**
> ETF's skin features (emissive pixels, blinking and so on) now work on standard 64x64 Quick Skin skins.
> *@Milan4ikdudewtf thx for reporting it!*

- **Ears compatibility with its newest update**
> Ears features (snout, ears, tail and so on) show again on Quick Skin skins with Ears 2.x.
> *@lapislizuli thx for reporting it!*

- **Your own skin on servers that give you a different UUID (1.20.1)**
> On Minecraft 1.20.1, Quick Skin now recognises your own player when an offline-mode server or a proxy gives you a different UUID from your launcher account.

- **No more `VarInt too big` errors on servers with Quick Skin 2.x**
> Skins and capes bigger than 1 MiB are no longer sent to servers that still run Quick Skin 2.x, because those servers relayed them as one oversized packet that could disconnect other players. You still see it yourself; other players see you without it.

### Changed

- **Each launcher account keeps its own skin**
> Skin selections now belong to the account instead of the instance. If an instance is launched with another account, that account uses its own skin, or the one a Quick Skin 3.x server saved for it, instead of overwriting it. Capes and CPM models are still chosen per instance.
> *@w0ot thx for reporting it!*

- **Clearer errors when searching a player's skin**
> A failed username search now tells you which step failed (finding the player, reading their profile or downloading the skin) instead of always saying "Player not found".
> *@GOJO thx for reporting it!*

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
