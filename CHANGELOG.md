# Changelog

## 3.0 — 2026-10-10

- Rebuilt account management around Minecraft's native widgets and responsive stone panels.
- Added search, account-type and favorite filters, favorites, and name/last-used sorting.
- Added explicit current-account labels, keyboard search and switching, and compact-screen layouts.
- Kept one-click switching, row stability in saved-order view, UUID-based active markers, asynchronous skin heads, and draggable 3D previews.
- Stopped hover from retargeting account actions.
- Updated offline entry, sign-in progress, and removal confirmation screens.
- Fixed successful online login failing to activate the saved account.
- Applied new session tokens even when signing back into the current UUID.
- Guarded canceled login and refresh completions against later session or screen changes.
- Preserved favorites when refreshed accounts replace old token data.
- Refused partial migration of malformed vaults; retained accounts needing reauthentication.
- Rolled back failed favorite saves and removed accounts by UUID.
- Replaced GLFW-specific shortcut handling with Minecraft input constants for 26.3 compatibility.
- Added 10 regression tests to the existing 3 and a reproducible release-build script.
