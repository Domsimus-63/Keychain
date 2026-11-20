**Backups, eight new languages, and a safer vault.**

## New

- **Password-protected backups.** Settings → **Backup & restore** saves every account to one file,
  locked with a password you choose. A backup opens on any computer, so it is the way to move your
  accounts to a new PC. Restoring adds the accounts you do not have yet and renews signed-out ones.
  Nothing is ever removed.
- **11 languages.** New: Spanish, French, Italian, Polish, Portuguese (Brazil), Russian, Chinese
  (Simplified) and Japanese.
- **A language just for Keychain.** Settings → **Language** opens a scrolling list. By default
  Keychain follows the game's language.
- **Warning on signed-out accounts.** A small yellow badge marks accounts that have to be signed in
  again, and the list header counts them, so you see it before a switch fails.

## Safer vault

- Every save is checked by reading it back before it replaces the vault.
- Saves are forced to disk first, so a power cut cannot leave an empty or half-written file.
- The previous state is kept as `accounts.vault.bak`. If the vault is ever damaged, Keychain rebuilds
  it from that copy automatically and tells you.
- On Linux and macOS, Keychain's files are readable by you only.

## Changed

- Smaller buttons at the bottom of the Keychain screen, and a visual clean-up of every screen:
  scrollbars stay inside their lists, panels line up, nothing sits on a border any more.
- The ▲▼ buttons are gone; reorder with Alt+Up/Down.
- Importing accounts from Keychain 1.x–3.x was removed. **Upgrading from 3.x?** Run 4.1.0 once
  first, then update.

## Tested

44 unit tests (backup encryption, wrong passwords, tampered files, vault self-repair, every
translation complete), and the in-game test, which now also creates a backup, restores it with a
wrong and then the right password, and switches Keychain's language. It passes on 1.21.11, 26.1,
26.2 and 26.3.

## Download

Replace your old Keychain jar with **one** of these:

| Minecraft | File |
| --- | --- |
| 1.21.11 | `keychain-4.2.0+mc1.21.11.jar` |
| 26.1, 26.1.1, 26.1.2 | `keychain-4.2.0+mc26.1.jar` |
| 26.2 | `keychain-4.2.0+mc26.2.jar` |
| 26.3 | `keychain-4.2.0+mc26.3.jar` |
| any of the above | `keychain-4.2.0-universal.jar` |

Your saved accounts carry over; nothing to redo.
