package io.github.domsimus.keychain.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import io.github.domsimus.keychain.core.vault.AccountVault;
import io.github.domsimus.keychain.core.vault.BackupCodec;
import io.github.domsimus.keychain.core.vault.VaultData;
import io.github.domsimus.keychain.session.SessionManager;
import io.github.domsimus.keychain.ui.AccountsScreen;
import io.github.domsimus.keychain.ui.BackupScreen;
import io.github.domsimus.keychain.ui.KeychainLanguage;
import io.github.domsimus.keychain.ui.LanguageScreen;
import io.github.domsimus.keychain.ui.MicrosoftSignInScreen;
import io.github.domsimus.keychain.ui.OfflineAccountScreen;
import io.github.domsimus.keychain.ui.SettingsScreen;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

/**
 * Drives the real game through Keychain's screens on every supported version, checks the session
 * actually changes (and waits while in a world), and saves screenshots for review.
 */
public final class KeychainClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitForScreen(TitleScreen.class);
        // The title screen fades its buttons in; wait for that before the screenshot.
        context.waitTicks(40);
        context.takeScreenshot("keychain-01-title");

        context.clickScreenButton("keychain.title");
        context.waitForScreen(AccountsScreen.class);
        context.takeScreenshot("keychain-02-empty");

        context.runOnClient(minecraft -> {
            AccountVault vault = Keychain.get().vault();
            check(vault.upsert(Account.offline("Alex_Test")), "save offline account");
            check(vault.upsert(Account.offline("Builder42").withFavorite(true)), "save favorite");
            check(vault.upsert(Account.microsoft(UUID.fromString("5b7e3c1a-9d24-4f6b-8a10-2c4e6f8a0b1c"), "SkyMiner",
                new MicrosoftSession("not-a-real-token", "not-a-real-refresh-token", null, Instant.now().plusSeconds(3600)))), "save Microsoft account");
            check(vault.upsert(Account.microsoft(UUID.fromString("7c9e1f3a-2b4d-4c6e-9f01-3a5c7e9b1d2f"), "PixelPaladin",
                new MicrosoftSession("expired", null, null, Instant.now().minusSeconds(60)))), "save expired account");
        });
        context.waitTicks(5);
        context.takeScreenshot("keychain-03-accounts");

        // Switching the way a player does it: double-click the account row with the real mouse.
        AtomicReference<Object> keysBefore = new AtomicReference<>();
        AtomicReference<Object> realmsBefore = new AtomicReference<>();
        AtomicReference<Object> telemetryBefore = new AtomicReference<>();
        String launcherName = context.computeOnClient(minecraft -> minecraft.getUser().getName());
        context.runOnClient(minecraft -> {
            keysBefore.set(minecraft.getProfileKeyPairManager());
            realmsBefore.set(minecraft.realmsDataFetcher());
            telemetryBefore.set(minecraft.getTelemetryManager());
            check(Keychain.get().sessions().launcher().isEmpty(), "no way back is offered before the first switch");
        });
        double[] alexRow = rowPoint(context, "Alex_Test", false);
        context.getInput().setCursorPos(alexRow[0], alexRow[1]);
        context.waitTick();
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
        context.runOnClient(minecraft -> {
            Account alex = Keychain.get().vault().find(Account.offlineUuid("Alex_Test")).orElseThrow();
            check(minecraft.getUser().getName().equals("Alex_Test"), "double-click switched, user is " + minecraft.getUser().getName());
            check(minecraft.getUser().getProfileId().equals(Account.offlineUuid("Alex_Test")), "offline UUID applied");
            check(minecraft.getProfileKeyPairManager() != keysBefore.get(), "chat keys were rebuilt");
            check(minecraft.realmsDataFetcher() != realmsBefore.get(), "Realms data follows the new account");
            check(minecraft.getTelemetryManager() != telemetryBefore.get(), "telemetry follows the new account");
            check(Keychain.get().vault().data().lastActive().equals(alex.id()), "last active saved");
            check(Keychain.get().sessions().launcher().map(User::getName).orElse("").equals(launcherName), "the launcher's account is offered as a way back");
        });

        // The launcher's own account is not saved in Keychain; its button brings it back without a restart.
        context.waitTicks(3);
        context.takeScreenshot("keychain-03b-launcher-button");
        // Its label contains the account name, so it is found by its text and clicked with the real mouse.
        double[] launcherButton = context.computeOnClient(minecraft -> {
            String label = net.minecraft.network.chat.Component.translatable("keychain.action.launcher", launcherName).getString();
            for (Object child : Compat.currentScreen().children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible && widget.getMessage().getString().equals(label)) {
                    double scale = (double) minecraft.getWindow().getWidth() / minecraft.getWindow().getGuiScaledWidth();
                    return new double[] {(widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
                }
            }
            throw new AssertionError("Keychain game test failed: no button labelled " + label);
        });
        context.getInput().setCursorPos(launcherButton[0], launcherButton[1]);
        context.waitTick();
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
        context.runOnClient(minecraft -> {
            check(minecraft.getUser().getName().equals(launcherName), "back on the launcher's account, user is " + minecraft.getUser().getName());
            check(Keychain.get().vault().data().lastActive() == null, "the launcher's account is what the next start uses");
            check(Keychain.get().sessions().launcher().isEmpty(), "nothing to go back to while on the launcher's account");
            Account alex = Keychain.get().vault().find(Account.offlineUuid("Alex_Test")).orElseThrow();
            check(Keychain.get().sessions().switchTo(alex).join() == SessionManager.Outcome.SWITCHED, "switched to Alex_Test again");
        });

        // Clicking the star on a row favorites it.
        double[] star = rowPoint(context, "Alex_Test", true);
        context.getInput().setCursorPos(star[0], star[1]);
        context.waitTick();
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
        check(context.computeOnClient(minecraft -> Keychain.get().vault().find(Account.offlineUuid("Alex_Test")).orElseThrow().favorite()), "star click favorites");
        context.waitTicks(5);
        context.takeScreenshot("keychain-04-switched");

        // An expired account without a refresh token asks for sign-in instead of switching.
        context.runOnClient(minecraft -> {
            Account expired = Keychain.get().vault().find(UUID.fromString("7c9e1f3a-2b4d-4c6e-9f01-3a5c7e9b1d2f")).orElseThrow();
            SessionManager.Outcome outcome = Keychain.get().sessions().switchTo(expired).join();
            check(outcome == SessionManager.Outcome.NEEDS_SIGN_IN, "expired outcome was " + outcome);
            check(minecraft.getUser().getName().equals("Alex_Test"), "session unchanged");
        });

        // A Microsoft account is checked with Minecraft services before switching. This one's tokens are
        // fake, so the check fails, the refresh is rejected, and Keychain asks for a new sign-in instead
        // of switching to a session that servers would refuse.
        UUID skyId = UUID.fromString("5b7e3c1a-9d24-4f6b-8a10-2c4e6f8a0b1c");
        AtomicReference<CompletableFuture<SessionManager.Outcome>> skySwitch = new AtomicReference<>();
        context.runOnClient(minecraft -> skySwitch.set(Keychain.get().sessions().switchTo(Keychain.get().vault().find(skyId).orElseThrow())));
        context.waitFor(minecraft -> skySwitch.get().isDone(), 600);
        context.runOnClient(minecraft -> {
            SessionManager.Outcome outcome = skySwitch.get().join();
            check(outcome == SessionManager.Outcome.NEEDS_SIGN_IN, "fake Microsoft account outcome was " + outcome);
            check(minecraft.getUser().getName().equals("Alex_Test"), "session unchanged after rejected token");
            Account sky = Keychain.get().vault().find(skyId).orElseThrow();
            check(sky.status(Instant.now()) == Account.Status.NEEDS_SIGN_IN, "rejected refresh token forgotten");
        });

        // The online session path itself (services client, chat keys, social manager) applies cleanly.
        context.runOnClient(minecraft -> {
            User before = minecraft.getUser();
            Compat.applySession(minecraft, new User("OnlinePath", UUID.randomUUID(), "fake-online-token", Optional.empty(), Optional.empty()), true);
            check(minecraft.getUser().getName().equals("OnlinePath"), "online session applied");
            check(minecraft.getUser().getAccessToken().equals("fake-online-token"), "online token applied");
            // The services client must be connected right away: Minecraft services should answer and
            // reject the fake token, not report themselves unavailable (the 26.3 bug in 4.0.0).
            String answer;
            try {
                minecraft.userApiService.fetchProperties();
                answer = "accepted";
            } catch (Exception rejected) {
                answer = String.valueOf(rejected.getMessage());
            }
            Keychain.LOGGER.info("Keychain test: services answered the fake token with: {}", answer);
            check(!answer.toLowerCase(java.util.Locale.ROOT).contains("unavailable"), "services reachable after switching, got: " + answer);
            Compat.applySession(minecraft, before, false);
            check(minecraft.getUser().getName().equals("Alex_Test"), "restored");
        });

        context.runOnClient(minecraft -> check(java.util.Arrays.stream(minecraft.options.keyMappings)
            .anyMatch(key -> key.getName().equals("key.keychain.open")), "Open Keychain key registered"));

        // Ctrl+F focuses search. The harness sends keys without modifier flags, so the event is built
        // here: key position for older versions, typed letter (keycode) for 26.3, plus the Ctrl flag.
        context.runOnClient(minecraft -> Compat.currentScreen().keyPressed(new net.minecraft.client.input.KeyEvent(
            InputConstants.KEY_F, 'f', net.minecraft.client.input.InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER)));
        check(context.computeOnClient(minecraft -> Compat.currentScreen().getFocused() instanceof net.minecraft.client.gui.components.EditBox),
            "Ctrl+F focuses search");
        context.getInput().typeChars("build");
        context.waitTicks(3);
        context.takeScreenshot("keychain-05-search");
        check(selectedRow(context).startsWith("Builder42,"), "search highlights the first match, got: " + selectedRow(context));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(2);
        check(context.computeOnClient(minecraft -> io.github.domsimus.keychain.compat.Compat.currentScreen() instanceof AccountsScreen), "Escape clears search before closing");

        // Arrow keys pick a row without leaving the search box, and a stray Enter in the empty box does nothing.
        context.getInput().pressKey(InputConstants.KEY_DOWN);
        context.waitTicks(2);
        check(selectedRow(context).startsWith("SkyMiner,"), "Down moves the selection from the search box, got: " + selectedRow(context));
        context.getInput().pressKey(InputConstants.KEY_UP);
        context.waitTicks(2);
        check(selectedRow(context).startsWith("Builder42,"), "Up moves it back, got: " + selectedRow(context));
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.waitTicks(2);
        context.runOnClient(minecraft -> check(minecraft.getUser().getName().equals("Alex_Test"), "Enter in an empty search box does not switch"));

        // Typing a search and pressing Enter switches to the highlighted match.
        context.getInput().typeChars("build");
        context.waitTicks(2);
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.waitTicks(3);
        context.runOnClient(minecraft -> check(minecraft.getUser().getName().equals("Builder42"), "Enter in the search box uses the match, user is " + minecraft.getUser().getName()));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(2);

        context.clickScreenButton("keychain.action.add_offline");
        context.waitForScreen(OfflineAccountScreen.class);
        context.getInput().typeChars("New_Player");
        context.waitTicks(2);
        context.takeScreenshot("keychain-06-offline");
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.waitForScreen(AccountsScreen.class);
        context.runOnClient(minecraft -> check(minecraft.getUser().getName().equals("New_Player"), "Enter adds and switches"));

        // Remove asks for confirmation, then offers Undo, which puts the account back in place.
        int countBefore = context.computeOnClient(minecraft -> Keychain.get().vault().accounts().size());
        context.clickScreenButton("keychain.action.remove");
        context.waitForScreen(net.minecraft.client.gui.screens.ConfirmScreen.class);
        context.clickScreenButton("gui.yes");
        context.waitForScreen(AccountsScreen.class);
        check(context.computeOnClient(minecraft -> Keychain.get().vault().accounts().size()) == countBefore - 1, "removed");
        context.takeScreenshot("keychain-06b-undo");
        context.clickScreenButton("keychain.action.undo");
        context.waitTicks(2);
        check(context.computeOnClient(minecraft -> Keychain.get().vault().accounts().size()) == countBefore, "undo restored the account");

        context.clickScreenButton("keychain.action.settings");
        context.waitForScreen(SettingsScreen.class);
        context.takeScreenshot("keychain-07-settings");

        // Backup: type a password twice with the keyboard and press Enter.
        context.clickScreenButton("keychain.settings.backup");
        context.waitForScreen(BackupScreen.class);
        context.getInput().typeChars("test-password-1");
        context.getInput().pressKey(InputConstants.KEY_TAB);
        context.getInput().typeChars("test-password-1");
        context.waitTicks(2);
        context.takeScreenshot("keychain-07b-backup");
        int backupsBefore = backupFiles(context).size();
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.waitFor(minecraft -> backupFiles(minecraft).size() > backupsBefore, 600);
        context.waitTicks(2);
        context.takeScreenshot("keychain-07c-backup-created");
        context.runOnClient(minecraft -> {
            java.nio.file.Path newest = backupFiles(minecraft).getFirst();
            try {
                VaultData backup = BackupCodec.decode(java.nio.file.Files.readString(newest), "test-password-1".toCharArray());
                check(backup.accounts().size() == Keychain.get().vault().accounts().size(), "the backup holds every account");
            } catch (java.io.IOException failure) {
                throw new AssertionError("Keychain game test failed: backup unreadable", failure);
            }
            // Remove an account, so restoring has something to bring back.
            check(Keychain.get().vault().remove(UUID.fromString("7c9e1f3a-2b4d-4c6e-9f01-3a5c7e9b1d2f")), "removed PixelPaladin");
        });

        // Restore: the newest backup is selected; enter its password and click Restore.
        context.clickScreenButton("keychain.backup.tab.restore");
        context.waitTicks(2);
        context.runOnClient(minecraft -> {
            for (Object child : Compat.currentScreen().children()) {
                if (child instanceof net.minecraft.client.gui.components.EditBox box) box.setValue("wrong-password");
            }
        });
        context.clickScreenButton("keychain.backup.restore");
        context.waitTicks(40);
        check(context.computeOnClient(minecraft -> Keychain.get().vault().find(UUID.fromString("7c9e1f3a-2b4d-4c6e-9f01-3a5c7e9b1d2f")).isEmpty()),
            "a wrong password restores nothing");
        context.takeScreenshot("keychain-07d-wrong-password");
        context.runOnClient(minecraft -> {
            for (Object child : Compat.currentScreen().children()) {
                if (child instanceof net.minecraft.client.gui.components.EditBox box) box.setValue("test-password-1");
            }
        });
        context.clickScreenButton("keychain.backup.restore");
        context.waitFor(minecraft -> Keychain.get().vault().find(UUID.fromString("7c9e1f3a-2b4d-4c6e-9f01-3a5c7e9b1d2f")).isPresent(), 600);
        context.waitTicks(2);
        context.takeScreenshot("keychain-07e-restored");
        context.clickScreenButton("gui.done");
        context.waitForScreen(SettingsScreen.class);

        // Language: Keychain's own screens switch language, the rest of the game does not.
        String gameDone = context.computeOnClient(minecraft -> net.minecraft.network.chat.Component.translatable("gui.done").getString());
        context.runOnClient(minecraft -> Compat.setScreen(new LanguageScreen(Compat.currentScreen())));
        context.waitForScreen(LanguageScreen.class);
        double[] german = context.computeOnClient(minecraft -> {
            for (Object child : Compat.currentScreen().children()) {
                if (!(child instanceof net.minecraft.client.gui.components.ObjectSelectionList<?> list)) continue;
                for (Object item : list.children()) {
                    net.minecraft.client.gui.components.ObjectSelectionList.Entry<?> row = (net.minecraft.client.gui.components.ObjectSelectionList.Entry<?>) item;
                    if (!row.getNarration().getString().contains("Deutsch")) continue;
                    double scale = (double) minecraft.getWindow().getWidth() / minecraft.getWindow().getGuiScaledWidth();
                    return new double[] {(row.getContentX() + 30) * scale, row.getContentYMiddle() * scale};
                }
            }
            throw new AssertionError("Keychain game test failed: no Deutsch row");
        });
        context.getInput().setCursorPos(german[0], german[1]);
        context.waitTick();
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(3);
        context.takeScreenshot("keychain-07f-language");
        context.runOnClient(minecraft -> {
            check(Keychain.get().settings().language.equals("de_de"), "language saved, got " + Keychain.get().settings().language);
            check(net.minecraft.network.chat.Component.translatable("keychain.settings.language").getString().equals("Sprache"), "Keychain is in German");
            check(net.minecraft.network.chat.Component.translatable("gui.done").getString().equals(gameDone), "the game keeps its language");
        });
        // A resource reload replaces the game's language table; the choice must survive it.
        AtomicReference<CompletableFuture<Void>> reload = new AtomicReference<>();
        context.runOnClient(minecraft -> reload.set(minecraft.reloadResourcePacks()));
        context.waitFor(minecraft -> reload.get().isDone(), 1200);
        // The loading screen fades out for about a second after the reload itself is done.
        context.waitTicks(60);
        context.runOnClient(minecraft -> check(net.minecraft.network.chat.Component.translatable("keychain.settings.language").getString().equals("Sprache"),
            "the language choice survives a resource reload"));
        // A quick look at a language in another script, then back to following the game.
        context.runOnClient(minecraft -> {
            KeychainLanguage.select("ja_jp");
            Compat.setScreen(new AccountsScreen(null));
        });
        context.waitTicks(5);
        context.takeScreenshot("keychain-07g-japanese");
        context.runOnClient(minecraft -> {
            Keychain.get().settings().language = "";
            Keychain.get().settings().save();
            KeychainLanguage.select("");
            check(net.minecraft.network.chat.Component.translatable("keychain.settings.language").getString().equals("Language"), "back to the game's language");
        });
        context.waitForScreen(AccountsScreen.class);

        // Browser sign-in: the local sign-in page starts and the Microsoft link is ready to open or
        // copy. (The test run never opens a real browser, and nothing is signed in.)
        context.clickScreenButton("keychain.action.add_microsoft");
        context.waitForScreen(MicrosoftSignInScreen.class);
        context.waitTicks(20);
        context.takeScreenshot("keychain-08-microsoft");
        // Windows hands the clipboard to one program at a time; another one may hold it for a moment.
        String link = "";
        for (int attempt = 0; attempt < 5 && link.isEmpty(); attempt++) {
            if (attempt > 0) context.waitTicks(10);
            context.clickScreenButton("keychain.signin.copy_link");
            link = context.computeOnClient(minecraft -> minecraft.keyboardHandler.getClipboard());
        }
        check(link.startsWith("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?"), "sign-in link: " + link);
        check(link.contains("redirect_uri=http%3A%2F%2Flocalhost%3A") && link.contains("code_challenge_method=S256"), "redirect and PKCE in link");
        context.clickScreenButton("gui.cancel");
        context.waitForScreen(AccountsScreen.class);

        context.getInput().resizeWindow(640, 420);
        context.waitTicks(5);
        context.takeScreenshot("keychain-09-compact");
        context.getInput().resizeWindow(854, 480);
        context.waitTicks(5);

        context.clickScreenButton("gui.done");
        context.waitForScreen(TitleScreen.class);
        context.runOnClient(minecraft -> minecraft.options.skipMultiplayerWarning = true);
        context.clickScreenButton("menu.multiplayer");
        context.waitForScreen(JoinMultiplayerScreen.class);
        context.takeScreenshot("keychain-10-multiplayer");
        context.clickScreenButton("gui.back");
        context.waitForScreen(TitleScreen.class);

        // In a world the switch waits until the player leaves, so the open connection keeps its identity.
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.waitTicks(100);
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitForScreen(PauseScreen.class);
            context.takeScreenshot("keychain-11-pause");
            context.clickScreenButton("keychain.title");
            context.waitForScreen(AccountsScreen.class);
            context.runOnClient(minecraft -> {
                Account builder = Keychain.get().vault().find(Account.offlineUuid("Builder42")).orElseThrow();
                SessionManager.Outcome outcome = Keychain.get().sessions().switchTo(builder).join();
                check(outcome == SessionManager.Outcome.QUEUED, "in-world outcome was " + outcome);
                check(minecraft.getUser().getName().equals("New_Player"), "session unchanged while in world");
            });
            context.waitTicks(5);
            context.takeScreenshot("keychain-12-queued");
        }
        context.waitFor(minecraft -> minecraft.getUser().getName().equals("Builder42"), 200);

        // Everything survives a reload from disk.
        context.runOnClient(minecraft -> {
            AccountVault vault = Keychain.get().vault();
            vault.load();
            check(vault.state() == AccountVault.State.READY, "vault readable after reload");
            check(vault.accounts().size() == 5, "five accounts saved, found " + vault.accounts().size());
            check(vault.find(Account.offlineUuid("Builder42")).orElseThrow().favorite(), "favorite saved");
        });
        Keychain.LOGGER.info("Keychain client game test passed");
    }

    /**
     * Window-pixel position of an account row (its face, or its star) in the open Keychain screen,
     * for clicking it with the test mouse.
     */
    private static double[] rowPoint(ClientGameTestContext context, String name, boolean star) {
        return context.computeOnClient(minecraft -> {
            net.minecraft.client.gui.screens.Screen screen = Compat.currentScreen();
            for (Object child : screen.children()) {
                if (!(child instanceof net.minecraft.client.gui.components.ObjectSelectionList<?> list)) continue;
                for (Object item : list.children()) {
                    net.minecraft.client.gui.components.ObjectSelectionList.Entry<?> row = (net.minecraft.client.gui.components.ObjectSelectionList.Entry<?>) item;
                    if (!row.getNarration().getString().startsWith(name + ",")) continue;
                    double x = star ? row.getContentRight() - 9 : row.getContentX() + 40;
                    double scale = (double) minecraft.getWindow().getWidth() / minecraft.getWindow().getGuiScaledWidth();
                    return new double[] {x * scale, row.getContentYMiddle() * scale};
                }
            }
            throw new AssertionError("Keychain game test failed: no row for " + name);
        });
    }

    private static java.util.List<java.nio.file.Path> backupFiles(ClientGameTestContext context) {
        return context.computeOnClient(KeychainClientGameTest::backupFiles);
    }

    /** Backups in the backups folder, newest first. */
    private static java.util.List<java.nio.file.Path> backupFiles(net.minecraft.client.Minecraft minecraft) {
        java.nio.file.Path folder = Keychain.get().paths().backups();
        if (!java.nio.file.Files.isDirectory(folder)) return java.util.List.of();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(BackupCodec.EXTENSION))
                .sorted(java.util.Comparator.comparing((java.nio.file.Path file) -> file.getFileName().toString()).reversed()).toList();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Narration of the selected account row ("Name, kind account, status. ..."), or "" if none is selected. */
    private static String selectedRow(ClientGameTestContext context) {
        return context.computeOnClient(minecraft -> {
            for (Object child : Compat.currentScreen().children()) {
                if (child instanceof net.minecraft.client.gui.components.ObjectSelectionList<?> list && list.getSelected() != null) {
                    return list.getSelected().getNarration().getString();
                }
            }
            return "";
        });
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("Keychain game test failed: " + what);
    }
}
