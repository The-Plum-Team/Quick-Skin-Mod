# Changelog

## 4.0.0 (2026-10-31)

### Added

- **A new skin menu built around your player**
> The skin menu drops its panel: your player stands in the middle on a turntable you can drag to turn, with your skins in a column of face tiles on the left, the player search at the top and the Body (arm width), Skin Websites (Normal or HD), Cape and pose buttons around the model. Clicking a skin wears it at once, and a button beside the sort button switches the column between the compact grid and a list where every skin gets a card with a 3D bust, its arm model and its resolution.

- **Favourites, a delete mode and a right-click menu for skins**
> Mark skins and CPM models as favourites to keep them right after your own skin in every sort order. Right-click a skin to favourite, rename or delete it, or use the bin beside the view button to pick several skins and delete them together. Added, favourited and deleted skins now animate in the column.

- **Nicknames**
> Click the name above your player in the skin menu to pick a nickname, kept per launcher account. On a server with Quick Skin everyone sees it in chat, death and advancement messages and the tab list, players without Quick Skin included, and players with Quick Skin also see it above your head; join and leave messages show "Nickname (Account)", operators still see account names in the tab list, and commands keep using account names. Server owners can turn player nicknames off with `allowNicknames` in `config/quickskin-server.json`.

- **Hide pieces of your armour from everyone**
> The skin menu's and the pause menu's models now wear what you wear and hold. Hover a piece of armour or an elytra on the skin menu's model to see through it, and click it to hide it from every other player, players without Quick Skin included; the piece stays worn and still protects you, and hidden pieces lie on the floor around the turntable, where a click puts them back on. The choice is kept per launcher account, and server owners can turn it off with `allowHiddenArmor` in `config/quickskin-server.json`, for example for PvP.

- **Admin tools for nicknames and skins**
> Operators can give players nicknames with `/quickskin nickname set`, hand out a different name to each player from `config/quickskin-nicknames.txt` with `random`, and take them back, clear or lock them with `reset`, `clear`, `lock` and `unlock`. They can also give players a skin for now, which other Quick Skin players see over the player's own skin until it is taken back, from the Player skins panel in the Server tab of the settings or with `/quickskin skin set|copy|random|shuffle|reset`; PNG skins put in `config/quickskin-skins/` join the server's skin pool (`/quickskin skin pool list|add|remove|reload`). Made for games where nobody should know who is who.

- **A reworked cape menu**
> Capes are now cards in sections (Favourites, My Capes, then the built-in capes by where they come from) under a heading that stays pinned while you scroll, with an import tile at the end of My Capes that you can click or drop files on. A large player on the right wears whichever cape is under the mouse, with the worn cape's animation speed under it; a click wears a cape, a right click marks it as a favourite or, for your own capes, edits or deletes it, and the mouse wheel scrolls the list wherever the mouse is.

- **A redesigned cape editor**
> The cape editor has a new layout with a 3D preview that shows the cape either as a cape or on elytra, Resolution, Position (Center, Snap, Mirror) and Opaque fill controls beside the canvas, and a zoom panel under it. It opens showing only the Front guide; a legend in the canvas corner turns the Cape Back and Elytra guides on and off, and hidden faces are dimmed.

- **Edit animated capes on a timeline**
> Animated capes now open in the cape editor with a timeline: trim the frames you keep, scrub to any frame, reverse them, play them back and forth and change the speed, and every frame keeps its own delay instead of a fixed 10 frames per second. GIFs too big to load before (up to 4096 pixels per side and 1024 frames) are now shrunk to fit and open in the editor.

- **WebP capes**
> You can import WebP images as capes (an animated WebP gives its first frame), and the file pickers now list everything the import takes: PNG, WebP and JPG skins and CPM models, and PNG, WebP and GIF capes.

- **Ten more official capes**
> Aurora, Hero, Twisted, Builder, Crafter, Moonlight Trail, Zombie Horse, Chinese Translator, cheapsh0t's and Blueprint join the built-in capes.

- **A livelier player preview**
> The preview now loops one of six random idles, and picking another skin plays a short move (a hop, a twirl, a wave and more) and switches to another idle. Walking and Sitting are redone: the player strides in place, or jumps onto the grass block, sits with its legs out and looks around; poses blend into each other, and switching between slim and classic arms springs the arms to their new width instead of snapping.

- **Curios, Trinkets and Accessories on the skin menu's model**
> Accessories from Curios, Trinkets and Accessories, such as Artifacts' power glove, now show on the skin menu's model. On Minecraft 1.20.1 and 1.21.1 you can click a Curios accessory there to hide it, like armour, which flips Curios' own visibility toggle so it hides in the world too.

- **Cosmetic Armor Reworked support**
> With Cosmetic Armor Reworked installed, the skin menu's model wears the cosmetic armour the world shows. A slot you hid through Cosmetic Armor lies on the floor around the turntable like any hidden piece, and clicking it switches Cosmetic Armor's own setting.

- **Your own skin follows your account**
> Quick Skin now checks your Mojang skin on every start, and when it changes it lists the new one as your own skin, keeping the old one as an ordinary skin. After Upload to Mojang it takes the skin as Mojang stored it, since Mojang may retouch an uploaded skin. A default skin (Steve, Alex and the others) that Mojang shows for an account without one never replaces the own skin you already have.

- **Delete your own skin**
> Your account's own skin can now be deleted like any other, and it is not downloaded again on the next start. It comes back when your account's skin changes, when you import or search it again, or when no other skin is left; the last skin in the list can't be deleted.

- **Players who are offline keep their look**
> Servers now also share the last appearance of players who are not connected, so mods that draw absent players, such as BlockPops' player figures, show their Quick Skin skins instead of Steve or Alex. Only the server needs this update.

- **Tell players without Quick Skin about it**
> A server with Quick Skin now tells each player who joins without it, once per world and a minute after they join, that the server uses Quick Skin and where to get it, in their game's language. Server owners can turn it off with `notifyPlayersWithoutQuickSkin` in `config/quickskin-server.json`, or change the text with `playersWithoutQuickSkinMessage` and the link with `playersWithoutQuickSkinLink`; Bedrock players are never told.

- **Quick Skin faces on JourneyMap**
> JourneyMap's player icons, on the minimap and the fullscreen map, now show the face of the player's Quick Skin skin and update when the skin changes.

### Fixes

- **No more crash in Quick Skin's dialogs with Vulkan (26.2)**
> On Minecraft 26.2 with the Vulkan renderer, opening the delete dialog, the rename dialog or the settings no longer crashes the game.

- **No more crash at launch with Respawn Recap and similar mods**
> The game no longer stops at startup when another mod ships the same WebP library Quick Skin used. Quick Skin now uses its own WebP decoder, which also works on Apple Silicon, Linux ARM and Android.

- **Quick Skin no longer changes your GUI Scale**
> Opening the skin or cape menu no longer changes your GUI Scale option or resizes the window, which could break other mods. Both menus now look the same at any GUI Scale, and every other screen opens at your own.

- **Legacy 64x32 skins get correct hands and feet**
> When a legacy 64x32 skin is converted, the top and bottom faces of the new left arm and leg (the end of the hand and the sole of the foot) are now copied the way Minecraft does it instead of coming out wrong.

- **Skin searches on a slow day**
> Searching a player's skin and the start-up check of your own skin now wait longer for Mojang, which sometimes takes several seconds to answer. When Mojang is limiting searches, the menu says so and asks you to wait a minute instead of blaming your connection.

- **Mods' title-screen panoramas behind Quick Skin's menus**
> On Fabric and Forge, Quick Skin's screens now show the panorama the title screen shows, so a mod such as Aquamirae or a resource pack that changes it carries over instead of the vanilla one.

- **The preview is no longer dark on 1.20.1**
> On Minecraft 1.20.1 the front of the player preview no longer turns as dark as the side of an item after an item was drawn.

- **No duplicate skins on import**
> Importing or searching a skin you already have no longer saves a second copy; it selects the one in your list. Deleting the skin you are wearing now wears the first skin in the list.

### Changed

- **New look for buttons, dialogs and toasts**
> Quick Skin's buttons share one new style, with the main action in green and a new pixel font on the skin menu's buttons, and the Delete, Rename and Upload to Mojang dialogs share one frame with a close button. The Enable Styled Buttons setting is gone, since every Quick Skin button now has the same style.

- **Clearer messages**
> Every Quick Skin message is now a toast in the menu's corner that says what went wrong and what to do, with the real sizes and limits, in your language. The cape menu's messages are toasts too, and cancelling the cape editor no longer reports that no cape was found.

- **Redesigned settings that apply right away**
> The settings now have their tabs stacked in a sidebar, the selected one in dark blue, and every setting is a row with its label on the left, its control on the right and a tooltip saying what it does; the texts are clearer and the GUI tab is now called Interface. Every checkbox takes effect as soon as you click it, without waiting for Done.

- **Blurred menu background by default**
> Quick Skin's menus now blur the world or the title panorama behind them, like Minecraft's own menus, and this is the new default for everyone. The starry background is still available in the settings.

## 3.1.1 (2026-10-11)

### Added

- **Compact Change Skin button**
> The new "Compact Change Skin Button" setting, in the Client tab, shows the Change Skin button of the title screen and the pause menu as a small Quick Skin icon, the same size as the language and accessibility buttons. The player preview and its buttons move to stand right above it. It is off by default and changes nothing when Essential is installed.
> *@Suelvys thx for the idea!*

- **Players without Quick Skin can see your new account skin**
> Server owners can turn on `shareAccountSkinWithVanillaClients` in `config/quickskin-server.json` (off by default). On an online-mode server, when you upload a skin to your Mojang account with the Upload to Mojang button, players who don't have Quick Skin see the new skin within a minute or two instead of only after you rejoin. Capes, HD skins and CPM models are still only visible to players with Quick Skin.

- **Upload limit for servers**
> Server owners can choose the biggest skin or cape that is synced to other players with `maxTextureUploadKilobytes` in `config/quickskin-server.json` (64 to 16384 KiB, 16384 by default). A skin or cape over the limit stays on your own screen, a chat message tells you why, and the rest of your appearance still syncs. Players on Quick Skin 3.1.0 or older should update before a server lowers the limit.

### Fixes

- **No more crash at launch with loading-screen mods**
> The game no longer crashes on startup ("No model for layer minecraft:player#main") when a mod that removes the loading screen, like Remove Reloading Screen, is installed. The title screen's player preview simply shows up once the game has finished loading.
> *@Llohgun thx for reporting it!*

- **The Change Skin button lines up with Quit Game on NeoForge**
> On NeoForge, the title screen's Change Skin button, together with the player preview above it, sat a few pixels higher than the Options and Quit Game row. It now sits on that row.
> *@Suelvys thx for reporting it!*

- **Importing skins on Android**
> On Android launchers, Import Skin now opens a file browser inside the game instead of doing nothing. If your file isn't listed, copy it into the game folder; the "Game folder" button takes you there.
> *@GIGTY thx for reporting it!*

- **No more disconnects from big skins on servers with Quick Skin 2.x**
> On a server that still runs Quick Skin 2.x, another player's large skin or GIF cape no longer disconnects you with "ByteArray with size ... is bigger than allowed 32768".

- **Smooth title-screen panorama with Very Many Players**
> With Very Many Players installed, the main menu's panorama turned in visible steps once the computer had been on for a few days, and jumped every few hours. It turns smoothly again.

- **Better performance with many capes around**
> Every visible Quick Skin cape now takes less work per frame, which helps on servers with lots of caped players.

- **The cape menu message no longer covers the buttons**
> The "Imported cape" and "Cape deleted" messages of the cape menu now show above the buttons instead of on top of Remove Cape.

- **More translations**
> The newest texts (skin search errors, the cape editor and the preview settings) are now translated into every language instead of showing up in English.

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
