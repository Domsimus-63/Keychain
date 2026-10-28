**Keychain 4.0.0 is a complete rewrite.** Switch Microsoft and offline Minecraft accounts in-game, now
on **Minecraft 1.21.11, 26.1 – 26.1.2, 26.2 and 26.3** (Fabric). Your accounts from Keychain 3.x and
older are imported automatically.

![Keychain account list](https://raw.githubusercontent.com/Domsimus-63/Keychain/main/docs/screenshots/accounts.png)

## Download

Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api),
then put **one** of these in your `mods` folder (remove the old Keychain jar first):

| Minecraft | File |
| --- | --- |
| 1.21.11 | `keychain-4.0.0+mc1.21.11.jar` |
| 26.1, 26.1.1, 26.1.2 | `keychain-4.0.0+mc26.1.jar` |
| 26.2 | `keychain-4.0.0+mc26.2.jar` |
| 26.3 | `keychain-4.0.0+mc26.3.jar` |
| any of the above | `keychain-4.0.0-universal.jar` (picks the right build for you) |

## Highlights

- **Switching that works on real servers.** Keychain now rebuilds chat-signing keys, the services
  connection, your profile and skin, and (26.2+) the friends list for the new account. Servers with
  secure chat accept you right after switching.
- **Safe mid-game.** Switch from the pause menu and it happens the moment you leave the world, so your
  current connection never breaks. Changed your mind? Click *Stay here*.
- **Microsoft sign-in like the official launcher**, in your browser, with plain-language explanations
  when something goes wrong (no Xbox profile, child account, game not owned, ...).
- **Accounts stay signed in.** Tokens are checked before every switch and refreshed when needed, and
  rarely used accounts are renewed in the background so they don't expire after 90 days.
- **New account screen:** 3D skin preview, search (Ctrl+F), filters, favorites, your own order,
  double-click or hover **Use** to switch, Undo after removing, Copy UUID.
- **Stronger storage:** AES-256-GCM with a random key kept outside your Minecraft folder (protected by
  Windows on Windows). Sharing a modpack or instance no longer shares your logins.
- **More ways in:** key button on the title screen, pause menu and server list, an optional
  *Open Keychain* key, and a Mod Menu *Configure* button.
- **Settings:** choose where buttons appear, remember your last account, share accounts between
  instances, custom Azure app ID.
- **English, German and Turkish.**

## Upgrading from 3.x

- Accounts and favorites are imported on first start; the old `config/account-switcher.json` is
  renamed to `account-switcher.json.migrated`.
- The mod ID changed to `keychain`. Keychain 4 won't load next to an old Keychain jar — remove it.

The full list of changes is in [CHANGELOG.md](https://github.com/Domsimus-63/Keychain/blob/main/CHANGELOG.md).
