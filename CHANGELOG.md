# Changelog

## 4.0.1

### Fixed
- **Minecraft 26.3: clicking accounts did nothing.** 26.3 numbers the left mouse button differently, so
  clicks on account rows were ignored: you could not select, double-click to switch, use the hover
  *Use* button or click the star.
- **Minecraft 26.3: Ctrl+F (search) and Ctrl+Z (undo) did nothing**, for the same kind of reason.
- Switching to a Microsoft account now reuses the game's own, already connected Minecraft services
  client on 26.3 instead of starting a new one, so services are never briefly unavailable after a switch.

### Tests
- The in-game test now clicks account rows and the star with the real mouse and presses Ctrl+F, and
  checks that Minecraft services answer right after an online switch. The click test fails on 4.0.0.

## 4.0.0

A complete rewrite. Your accounts from Keychain 3.x and older are imported automatically.

### New
- Microsoft sign-in in the browser, the same way the official launcher does it.
- Switching now rebuilds chat-signing keys, the services connection, player reporting, the profile
  and (26.2+) the friends list for the new account. Servers with secure chat enabled now accept
  messages after switching.
- Switching while in a world is queued until you leave it instead of half-applying to the open
  connection.
- Plain-language explanations for every Microsoft, Xbox and Minecraft sign-in failure.
- Hover **Use** button and clickable favorite star on each row; double-click or Enter to switch.
- Custom order with ▲▼ buttons or Alt+Up/Down.
- Copy UUID, last-used time and live status for the selected account.
- Settings screen: choose where the Keychain button appears (title screen, pause menu, server list),
  remember the last account across restarts, share accounts between instances, custom Azure
  app ID.
- Keychain button on the multiplayer server list.
- Vanilla toasts for switch results.
- Fully translatable (all text is in `assets/keychain/lang/en_us.json`).
- Microsoft accounts are checked with Minecraft services before switching and refreshed if the token
  was revoked, so you never switch to a session servers reject. Username changes are picked up.
- Rarely used Microsoft accounts are renewed in the background about once a week, so they no longer
  expire after Microsoft's 90-day refresh-token limit.
- Undo (or Ctrl+Z) for 10 seconds after removing an account.
- *Stay here* cancels a switch that is waiting for you to leave the world.
- Optional *Open Keychain* key (unbound by default) and a Mod Menu *Configure* button.
- German and Turkish translations.
- Search also finds accounts by UUID; Cmd shortcuts and Backspace-to-remove on macOS.
- The side panel shows how long the current Microsoft session stays valid.

### Improved
- New vault format: AES-256-GCM with a random key stored outside the Minecraft folder (Windows DPAPI
  protected on Windows) instead of a key derived from your computer name.
- Reads and writes are atomic and never overwrite a vault that cannot be read. Two game instances
  using the same vault no longer undo each other's changes.
- An unreadable vault can be set aside with *Start fresh*; the old file is kept as a backup.
- The Keychain button finds a free spot next to vanilla buttons instead of guessing coordinates.
- Selecting a row no longer switches accounts by accident.
- Each Microsoft account remembers which Azure app issued its tokens, so changing the custom app ID
  no longer breaks accounts that were added before.
- *Start fresh* also recovers when the vault key itself cannot be read (new PC or Windows user).
- Switching is all-or-nothing: if rebuilding the session fails, the previous session stays intact.
- ▲▼ move an account past its visible neighbor, so moves always show up even with favorites.
- The sign-in page also listens on IPv6, for systems that resolve localhost to ::1.
- Keychain no longer lets you hide all of its buttons without another way to open it.

### Technical
- One codebase for 1.21.11, 26.1.x, 26.2 and 26.3; version differences are isolated in a small
  `compat` package per version.
- 31 unit tests for the core (including a check that every translation matches English) and an in-game test that runs on every supported version.
- Mod ID changed from `accountswitcher` to `keychain`. Keychain 4 refuses to load next to an old
  Keychain jar, so remove the old one.
