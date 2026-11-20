# Changelog

## 4.2.0

### New
- **Password-protected backups.** Settings → *Backup & restore* saves every account to one file,
  locked with a password you choose (AES-256-GCM, key derived with PBKDF2-SHA256, 600,000 rounds).
  Unlike the vault, a backup opens on any computer, so it is the way to move your accounts. Restoring
  adds the accounts you do not have yet and renews signed-out ones; nothing is ever removed.
- **11 languages.** New: Spanish, French, Italian, Polish, Portuguese (Brazil), Russian, Chinese
  (Simplified) and Japanese, next to English, German and Turkish.
- **A language just for Keychain.** Settings → *Language* opens a scrolling list. Keychain follows
  the game's language by default, or uses the one you pick while the rest of the game stays as it is.
- **Warning on signed-out accounts.** Accounts that have to be signed in again get a small yellow
  badge on their head, and the list header counts them (*Needs sign-in: 2*). Accounts whose sign-in
  was last renewed more than 90 days ago get the badge too, since Microsoft has most likely dropped it.

### Safer vault
- Every save is decrypted again before it replaces the vault, so a save can never write a file that
  does not open.
- Saves are forced to disk before they replace the old file, so a power cut cannot leave an empty or
  half-written vault, key or settings file.
- The previous state is kept as `accounts.vault.bak`. If the vault file is damaged, Keychain rebuilds
  it from that copy on its own, keeps the damaged file, and tells you.
- A vault written by a newer Keychain is never "repaired" with the older copy.
- On Linux and macOS, the Keychain folder and every file Keychain writes are readable by you only.
- Key material is wiped from memory once it has been used.

### Changed
- The bottom buttons are smaller (18 pixels high, at most 116 wide) and centered.
- The ▲▼ buttons are gone; reorder with Alt+Up/Down in the list.
- *Show* and *Sort* grow to fit their longest label, so translations no longer scroll inside the button.
- The account preview says *Never used yet* instead of "Last used never".
- The language setting reads *Automatic* while Keychain follows the game.
- **A cleaner account screen that matches the rest of Keychain.** The list and the preview panel
  share one frame and line up edge to edge; *Back to <name>* sits in the header line next to
  "Playing as"; the hover *Use* button looks like every other button; only the status turns yellow
  on a signed-out account, not the whole line; gray text is easier to read on bright backgrounds.
- Titles, subtitles and bottom buttons sit at the same height on every Keychain screen, and every
  list (accounts, backups, languages) uses the same framed look.
- While a switch waits, the header says only who is next, so it no longer runs under the toast.

### Fixed
- List scrollbars stuck out past the right edge of their list and overlapped the rows.
- The preview panel did not line up with the account list's top and bottom edges.
- The account count and *Undo* sat directly on the list's top border; *Undo* was cramped.
- "Select an account" was drawn above the middle of the empty preview panel.
- In the backup and language lists, the second line of a row ran into the selection outline.
- The Microsoft sign-in panel had an empty band above its buttons.
- A failed check of an account removed during the check still showed an error.

### Removed
- Importing accounts from Keychain 1.x–3.x. **Upgrading from 3.x?** Start the game once with 4.1.0
  first, which imports them, then update to 4.2.0.

## 4.1.0

### New
- **Back to your launcher account.** Once you switch away from the account the game was started
  with, a *Back to <name>* button returns to it without a restart, even though that account is not
  saved in Keychain. Like every switch, it waits until you leave the world you are in.
- Arrow keys pick a result without leaving the search box, and Enter switches to it.
- The account list opens scrolled to your current account, and keeps its scroll position and
  keyboard focus when the window is resized.

### Fixed
- **Realms kept using the previous account.** The Realms list, invitations and news still belonged
  to the account the game was started with, and a "cannot sign in to Realms" answer from that
  account stuck until restart. Realms now follows the switch.
- Gameplay telemetry was still sent in the previous account's name after a switch.
- **A queued switch could happen in the middle of a connection.** Server networks (Velocity,
  BungeeCord) briefly unload the world while moving you between servers; Keychain took that for
  "left the world" and switched your account underneath the live connection. A queued switch now
  waits until you are back at a menu, and no switch is applied while a server is still logging you in.
- **Add Offline Account: the typed name went nowhere** if you had last used Tab or the arrow keys.
  The *Cancel* button had the focus instead of the name box, so Enter cancelled.
- **Two games starting for the first time at once** could leave one of them with "saved accounts
  could not be unlocked": it read the new key file while the other game was still writing it.
- Two instances sharing one account list could undo each other's change when both saved within the
  same second (file systems with coarse timestamps). Changes are now locked against each other and
  compared by content.
- *Start fresh* checks the vault once more first. If it opens after all (the problem was temporary,
  such as the file being in use), nothing is moved aside. The screen also shows the actual reason,
  so a vault made by a newer Keychain is not mistaken for a lost key.
- A Microsoft session that was renewed during a switch is saved even if you picked another account
  in the meantime, and the switch uses the renewed session even if saving it failed.
- *Undo* puts back the account exactly as it was removed (including a session renewed since you
  selected it) and restores *Remember account* if it pointed at that account.
- Skins no longer flash back to the default skin while they are refreshed, and Keychain no longer
  asks Mojang for the skin of an offline launcher account, which cannot have one.
- Typing in the search box is no longer interrupted when the screen rebuilds itself.
- The storage note in Settings no longer overlaps other text on very small windows.
- Settings are saved atomically, so a crash cannot leave half a settings file.
- The sign-in page listens on 127.0.0.1 even when Java prefers IPv6.

### Tests
- The key-file race was caught by an existing test that only failed some of the time; it now runs
  30 rounds. New unit tests cover same-second changes from another instance, *Start fresh* on a
  vault that recovered, and accounts removed elsewhere (35 unit tests).
- The in-game test now also clicks *Back to <name>*, checks that Realms and telemetry follow the
  switch, picks search results with the arrow keys, and adds an offline account after keyboard
  navigation. It passes on 1.21.11, 26.1, 26.2 and 26.3.

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
