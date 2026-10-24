# 0015. Show cosmetic nicknames through display names

Date: 2026-10-23

## Status

Accepted. Complements [ADR 0011](0011-show-quick-skin-appearances-only-to-quick-skin-clients.md):
appearances stay on the Quick Skin protocol; a nickname is text that the server shows. Amended
2026-10-24 with admin commands.

## Context

The maintainer asked for a roleplay nickname: clicking the player's name tag in the skin menu
changes the name others see, "even if it is only visual". Players joining a server should be
announced with the new name and the original one, commands should keep targeting the original
name, and operators should see both names in the tab list. A follow-up, prompted by a server
owner building a Murder-style game in which every round gives players new names and skins so that
nobody knows who is who, asked for commands with which admins set a player's name or hand out
random generic names.

Vanilla already separates the two names on every target of the release matrix (checked in the
decompiled 1.20.1 to 26.3 jars, vanilla and Forge/NeoForge builds):

- Commands, selectors and suggestions resolve the `GameProfile` name: `PlayerList.getPlayerByName`,
  the selector's `name=` (`getName`, `getPlainTextName` from 1.21.9), `getScoreboardName`, and the
  client's suggestions, which read the profile names in `PlayerInfo`.
- Chat, death and advancement messages, `/msg`, `/me`, `/list` and command feedback name a player
  through `Player.getDisplayName()`, which formats `getName()` for the team and adds a click
  that suggests `/tell <profile name>`, a hover that names the entity and an insertion of the
  profile name. Chat signatures cover the message, not the sender's name.
- The tab list shows the clientbound `UPDATE_DISPLAY_NAME` text of `ServerPlayer
  .getTabListDisplayName()` when there is one, as it arrives, on vanilla and modded clients.
- `PlayerList.placeNewPlayer` broadcasts the yellow join message, from `getDisplayName()`, before
  the player's client can send any packet (Quick Skin's join hook and hello come later on every
  loader), and nobody sees their own join message.
- A name tag above a head is drawn from the client's own `getDisplayName()`, so a server cannot
  change it for vanilla clients short of renaming the profile or abusing team prefixes.
- NeoForge never reads `quickskin.mixins.json`; it reads only the configurations its
  `neoforge.mods.toml` declares.

## Decision

A player may pick a cosmetic nickname; the account name keeps every role it has in the game.

- **Rules.** `Nickname` (content-core) is the one rule set: NFC, trimmed, single spaces, at most
  32 UTF-16 units, letters of any script, digits, at most two combining marks per character,
  spaces and `_ - . , '` or `’`, with a letter or digit. Formatting codes, control, invisible and
  bidirectional characters, the Hangul fillers and variation selectors that draw nothing, emoji,
  icon glyphs and brackets (which could fake the account name the server adds, or a chat prefix)
  are refused. The account name, or a blank one, means none. Two names *read alike* when they are
  equal once compatibility forms are decomposed, accents dropped, the Cyrillic and Greek letters
  Minecraft's font draws like Latin ones folded to them, and case ignored.
- **Client.** The nickname is kept per launcher account in `quickskin-client.json`
  (`accountNicknames`, keyed like the skin selection) and chosen through the skin menu's name
  tag. On a session that negotiated the new optional capability `nicknames` (bit 5) the client
  sends it once (an empty one too, so a removed nickname is forgotten) and after every change,
  never during a replay; a refusal for changing too often is retried when the wait is over. A
  refusal never rewrites the stored choice: on that server the menu shows the name the server
  shows, and says why when it opens. Nicknames the server announces are checked by rules that
  do not depend on the Java version's Unicode tables, one by one, since a server on a newer Java
  may accept a letter an older client does not know.
- **Server.** `ServerNicknameStore` keeps each player's nickname in the world's
  `quickskin/nicknames.json` (at most 4096 players, the longest unseen forgotten first, read at
  start, saved atomically on the cache-I/O worker, never overwritten when unreadable), so a
  returning player's join message can use it. The store also remembers the account name of
  everyone who joined (within the same bound). A request is admitted only from that exact
  negotiated session and only about the sender, rate limited on the network thread, and refused
  when the server has `allowNicknames` off (default on), when a moderator set `"locked": true` on
  the player in the file, when it changes within 10 seconds of the last change (across
  reconnects), or when it reads like another online player's account name, an operator's or
  whitelisted name, or the account name or nickname of anyone the store remembers; no profile
  lookup is made, since it can block on Mojang. When a player joins, any other player's nickname
  that reads like the newcomer's account name is cleared. Every change is logged with the account
  name and UUID, and announced in yellow to everyone with an English fallback for clients without
  the translation: "Account is now known as Nick", or "Old (Account) is now known as New" when
  one nickname replaces another or goes.
- **What it changes.** One `@ModifyArg` replaces the name inside `Player.getDisplayName()`'s team
  formatting, so chat, death and advancement messages, `/msg`, `/me`, `/list` and command feedback
  show the nickname with the team's colours and with vanilla's click, hover and insertion still
  naming the account; on Forge and NeoForge it gives way when another mod's `NameFormat` renamed
  the player. The join and leave messages show "Nick (Account)" with vanilla's own translations.
  `getTabListDisplayName` returns the nickname in the team's colours when no other mod set a tab
  name; the server sends operators (permission level 2) a second packet that adds the account
  name, after each join, nickname change, team change (its colour, prefix and suffix, styles
  included) and change of operator status (looked at every second, since "Open to LAN" does not
  always resend permission levels), and after Quick Skin's own account-skin refresh (ADR 0012)
  re-lists the player for vanilla clients. Quick Skin
  clients receive the nicknames of the players online over the `nicknames` capability and show
  them on name tags. The profile, `getName`, `getPlainTextName` and `getScoreboardName` are never
  touched.
- **Admins.** `/quickskin nickname`, for permission level 2 and registered through Architectury's
  `CommandRegistrationEvent`, gives one player a nickname for now (`set`) or each player a
  different name from `config/quickskin-nicknames.txt` (`random`), takes those back (`reset`;
  without players, everyone's, also of players who left, whose names are then free again),
  removes every nickname of the players (`clear`) and locks or unlocks their own (`lock`,
  `unlock`). The server writes the list file with about ninety given names that suit anyone when
  it is missing; it holds one name per line under the same rules, a name that reads like an
  earlier one counting once. An admin's nickname is a second layer of the store's entry
  (`assigned`): it shows instead of the player's own nickname, which stays and shows again when
  the admin's is taken back, survives restarts, follows the same rules (and nobody else may take
  it) without the cooldown, and the player's own requests meanwhile answer the new status
  `ASSIGNED` (7). It is told to that player (a chat line) and to the operators (vanilla's command
  feedback), never announced, so a round can hand out names without saying who is who. A
  `random` takes the players' earlier admin names back first, so a new round can reuse them.
  `allowNicknames` governs only the names players choose; an admin's shows either way. The
  server also tells the player's own client unasked, in a `nickname_result` with request id 0,
  whether they may change their nickname: `ASSIGNED` or `LOCKED` while they may not,
  `UNCHANGED` while they may, when the session becomes ready and after each admin change. While
  they may not, the skin menu's name tag opens no dialog and its tooltip says why.
- **Loaders.** The four mixins live in `com.quickskin.mod.mixin.nickname`, listed in every
  `quickskin.mixins.json` for Fabric and Forge and in `quickskin-nicknames.mixins.json`, which both
  NeoForge `neoforge.mods.toml` files declare. Each injector is degradable (`require = 0`): a
  missed hook leaves the account name showing.

## Consequences

- Players without Quick Skin see nicknames in chat, the tab list and join and leave messages,
  but always the account name above heads. Quick Skin releases without this change neither send
  nor receive nicknames; their chat and tab list follow the server like a vanilla client's.
- The first join after a nickname was chosen still shows the previous nickname (or none): the
  client tells the server right after the message, and the announcement follows.
- Nicknames are presentation, not privacy: hovering a name, `/list uuids`, the join message, the
  tab list of operators and command suggestions all show account names.
- Another mod that sets tab-list names (TAB, Styled Player List) or display names keeps
  precedence; servers that use one can show the nickname through its display-name placeholder.
  A mod that calls `refreshTabListName` resets an operator's view of a nicknamed player until
  the next refresh.
- The console logs chat under the nickname; the change log and the join and leave messages
  link it to the account.
- A moderator clears a nickname for good with `clear` and then `lock`, since the player's client
  sends its nickname again at the next join; `nicknames.json` can also be edited while the server
  is stopped. `allowNicknames` stops everyone from choosing a nickname, while names admins give
  still show.
- A game that hides who is who still leaks accounts where Minecraft names them: hovering a name in
  chat and its `/tell` suggestion, the join message, operators' tab list and, for players without
  Quick Skin, name tags. Such a game wants Quick Skin on every client and a team that hides name
  tags or chat as it sees fit.
- Packaged E2E covers no nickname yet: the display hooks run only on servers and clients that
  pick a nickname, and the store, rules, admission and wire bounds have unit tests.
