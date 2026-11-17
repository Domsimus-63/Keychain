**Quality release: one new feature and a round of fixes for every supported Minecraft version.**

## New

- **Back to your launcher account.** Once you switch away from the account the game was started
  with, a **Back to <name>** button returns to it without a restart, even though that account is
  not saved in Keychain. Like every switch, it waits until you leave the world you are in.
- **Keyboard search.** Arrow keys pick a result without leaving the search box, and Enter switches
  to it.
- The account list opens scrolled to your current account and keeps its scroll position and
  keyboard focus when the window is resized.

## Fixed

- **Realms kept using the previous account.** The Realms list, invitations and news still belonged
  to the account the game was started with, and a "cannot sign in to Realms" answer from that
  account stuck until restart. Realms now follows the switch.
- **Telemetry was still sent in the previous account's name** after a switch.
- **A queued switch could happen in the middle of a connection.** Server networks (Velocity,
  BungeeCord) briefly unload the world while moving you between servers. Keychain took that for
  "left the world" and switched your account underneath the live connection. A queued switch now
  waits until you are back at a menu.
- **Add Offline Account: the typed name went nowhere** if you had last used Tab or the arrow keys,
  because *Cancel* had the focus instead of the name box.
- **Two games starting for the first time at once** could leave one of them with "saved accounts
  could not be unlocked".
- Two instances sharing one account list could undo each other's change when both saved within the
  same second.
- **Start fresh** checks the vault once more first and moves nothing if it opens after all. The
  screen also shows the actual reason the vault could not be opened.
- A Microsoft session renewed during a switch is saved even if you picked another account meanwhile.
- **Undo** puts the account back exactly as it was removed and restores *Remember account*.
- Skins no longer flash back to the default skin while they are refreshed.
- Smaller fixes: search focus survives screen rebuilds, no overlapping text in Settings on very
  small windows, settings are saved atomically, the sign-in page works when Java prefers IPv6.

## Tested

35 unit tests, and the in-game test, which now also clicks **Back to <name>**, checks that Realms
and telemetry follow the switch, picks search results with the arrow keys and adds an offline
account after keyboard navigation. It passes on 1.21.11, 26.1, 26.2 and 26.3.

## Download

Replace your old Keychain jar with **one** of these:

| Minecraft | File |
| --- | --- |
| 1.21.11 | `keychain-4.1.0+mc1.21.11.jar` |
| 26.1, 26.1.1, 26.1.2 | `keychain-4.1.0+mc26.1.jar` |
| 26.2 | `keychain-4.1.0+mc26.2.jar` |
| 26.3 | `keychain-4.1.0+mc26.3.jar` |
| any of the above | `keychain-4.1.0-universal.jar` |

Your saved accounts carry over; nothing to redo.
