**Bug-fix release — please update if you play on Minecraft 26.3.**

## Fixed

- **26.3: clicking accounts did nothing.** Minecraft 26.3 numbers the left mouse button differently
  from older versions, and Keychain still expected the old number. Clicks on account rows were
  ignored, so you could not select an account, double-click to switch, use the hover **Use** button,
  or click the favorite star.
- **26.3: Ctrl+F (search) and Ctrl+Z (undo) did nothing**, for the same kind of reason.
- Switching to a Microsoft account on 26.3 now reuses the game's own, already connected Minecraft
  services client, so services are never briefly unavailable right after a switch.

1.21.11, 26.1 and 26.2 had none of these problems, but get the same jar update.

## Tested

The in-game test now does what a player does: it double-clicks account rows and clicks the star
with the real mouse, presses Ctrl+F, and checks that Minecraft services answer right after an online
switch. It passes on 1.21.11, 26.1, 26.2 and 26.3. The click test fails on 4.0.0.

## Download

Replace your old Keychain jar with **one** of these:

| Minecraft | File |
| --- | --- |
| 1.21.11 | `keychain-4.0.1+mc1.21.11.jar` |
| 26.1, 26.1.1, 26.1.2 | `keychain-4.0.1+mc26.1.jar` |
| 26.2 | `keychain-4.0.1+mc26.2.jar` |
| 26.3 | `keychain-4.0.1+mc26.3.jar` |
| any of the above | `keychain-4.0.1-universal.jar` |

Your saved accounts carry over; nothing to redo.
