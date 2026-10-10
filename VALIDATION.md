# Keychain 3.0 validation

Verified on 2026-10-10. Builds used JDK 25 and Gradle 9.7.0. Development game instances used separate run directories and dummy offline accounts; the user's Minecraft instance and saved accounts were not modified.

## Build matrix

| Minecraft | Build | JUnit tests | Universal JAR live UI |
| --- | --- | --- | --- |
| 1.21.11 | Passed, Java 21 bytecode | 13 passed | Passed |
| 26.1 | Passed, Java 25 bytecode | 13 passed | Passed |
| 26.2 | Passed, Java 25 bytecode | 13 passed | Passed |
| 26.3 | Passed, Java 25 bytecode | 13 passed | Startup blocked before the menu |

Each target has 8 account-storage regression tests and 5 account-query/expiry tests. All builds also validate the access widener. The legacy game checks ran on JDK 25; Java 21 compatibility is established by compilation targeting release 21 and class-file version checks, not by a separate Java 21 game launch.

## In-game checks

The universal bundle selected the correct Keychain build on 1.21.11, 26.1, and 26.2. An isolated development harness opened Keychain through the title-screen **K** button, interacted with the real Minecraft widgets, checked the resulting session and vault state, and captured rendered frames at 1280x720 with GUI scales 2 and 3.

Verified:

- Wide and compact controls stay within the screen; the compact layout displays more account rows and omits the 3D preview.
- Single clicks apply the selected offline account and keep the screen open; selection survives refreshing the list.
- Favorites update, remain selected after sorting, and filter correctly.
- Ctrl+F focuses search; username matching is case-insensitive; Escape clears a focused nonempty search without leaving the screen.
- Search and filters do not change the active session. Online, offline, and favorites views show the expected fixture accounts.
- Invalid offline usernames disable submission. A valid username can be submitted with Enter, saved, and activated.
- Canceling account removal keeps all accounts. Confirmed removal removes the selected saved record without changing the current session.
- Empty-list and unreadable-vault states render correctly. Add actions are disabled when the vault is unreadable, and the original file is preserved.
- Azure configuration and Microsoft sign-in controls fit the compact screen. A simulated pending Microsoft sign-in cancels when leaving its screen.

The final current-account token fix additionally checks that a replacement session token is applied even when its UUID is already active. This check uses a dummy offline token in the development client.

## Remaining verification

The 26.3 development client loaded the matching Keychain build, then exited during native startup with `0xC0000005` before reaching a menu. A separate Fabric/API baseline with no Keychain or UI harness reproduced the same native exit during resource loading. The specific native cause is undiagnosed; 26.3 UI interaction therefore remains unverified on this machine.

Real Microsoft OAuth sign-in, online skin downloads, online server reconnection, Realms, and interaction with other UI mods were not tested in this release. The Microsoft screen screenshot uses a simulated pending state and is a layout check, not proof of successful authentication.

Existing Keychain account encryption and OAuth endpoints were retained. Source reference for native screen lifecycle and widget construction: [Fabric custom-screen documentation](https://docs.fabricmc.net/develop/rendering/gui/custom-screens). Version-specific input and rendering signatures were checked against the cached Minecraft bytecode and verified by compilation.
