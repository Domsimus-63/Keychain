# Install Keychain 3.0

1. Close Minecraft. Back up your Minecraft instance's `config/account-switcher.json` if you have saved accounts.
2. Remove the old Keychain JAR from that instance's `mods` folder.
3. Copy `keychain-3.0-universal.jar` into `mods`.
4. Use Fabric Loader and Fabric API for your Minecraft version, then launch Minecraft.
5. Open **K** on the title screen or **Keychain** from the pause menu.

The universal JAR contains builds for **1.21.11, 26.1.x, 26.2.x, and 26.3.x**. Install it alone. The individual JARs in `versions/` are alternatives; do not install both forms.

Minecraft 1.21.11 uses Java 21 or newer and Fabric Loader 0.19.2 or newer. The modern variants require Java 25; 26.3 additionally requires Fabric Loader 0.19.5 or newer.

Your existing saved accounts remain compatible. Use **Show** to filter account types or favorites, and the adjacent sort button to change account order. **Ctrl+F** focuses search. Click an account to switch while keeping Keychain open. Favorites stay first. Removing a saved account keeps the current session signed in.

Reconnect to a server after changing accounts if you were already connected.

All four variants pass builds and automated tests. See `VALIDATION.md` for live UI verification and remaining checks, including real Microsoft sign-in and the 26.3 startup limitation observed on this machine.
