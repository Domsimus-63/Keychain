package io.github.domsimus.keychain.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.MicrosoftSession;
import io.github.domsimus.keychain.core.vault.AccountVault;
import io.github.domsimus.keychain.session.SessionManager;
import io.github.domsimus.keychain.ui.AccountsScreen;
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

        // Switching from the title screen applies immediately and rebuilds the session services.
        AtomicReference<Object> keysBefore = new AtomicReference<>();
        context.runOnClient(minecraft -> {
            keysBefore.set(minecraft.getProfileKeyPairManager());
            Account alex = Keychain.get().vault().find(Account.offlineUuid("Alex_Test")).orElseThrow();
            SessionManager.Outcome outcome = Keychain.get().sessions().switchTo(alex).join();
            check(outcome == SessionManager.Outcome.SWITCHED, "switch outcome was " + outcome);
            check(minecraft.getUser().getName().equals("Alex_Test"), "user is " + minecraft.getUser().getName());
            check(minecraft.getUser().getProfileId().equals(Account.offlineUuid("Alex_Test")), "offline UUID applied");
            check(minecraft.getProfileKeyPairManager() != keysBefore.get(), "chat keys were rebuilt");
            check(Keychain.get().vault().data().lastActive().equals(alex.id()), "last active saved");
        });
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
            Compat.applySession(minecraft, before, false);
            check(minecraft.getUser().getName().equals("Alex_Test"), "restored");
        });

        context.runOnClient(minecraft -> check(java.util.Arrays.stream(minecraft.options.keyMappings)
            .anyMatch(key -> key.getName().equals("key.keychain.open")), "Open Keychain key registered"));

        // Typing in search filters the list. (The test harness sends keys without modifier flags, so
        // Ctrl+F itself cannot be simulated here; focus the search box directly.)
        context.runOnClient(minecraft -> io.github.domsimus.keychain.compat.Compat.currentScreen().children().stream()
            .filter(child -> child instanceof net.minecraft.client.gui.components.EditBox).findFirst()
            .ifPresent(io.github.domsimus.keychain.compat.Compat.currentScreen()::setFocused));
        context.getInput().typeChars("build");
        context.waitTicks(3);
        context.takeScreenshot("keychain-05-search");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(2);
        check(context.computeOnClient(minecraft -> io.github.domsimus.keychain.compat.Compat.currentScreen() instanceof AccountsScreen), "Escape clears search before closing");

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
        context.clickScreenButton("gui.done");
        context.waitForScreen(AccountsScreen.class);

        // Browser sign-in: the local sign-in page starts and the Microsoft link is ready to open or
        // copy. (The test run never opens a real browser, and nothing is signed in.)
        context.clickScreenButton("keychain.action.add_microsoft");
        context.waitForScreen(MicrosoftSignInScreen.class);
        context.waitTicks(20);
        context.takeScreenshot("keychain-08-microsoft");
        context.clickScreenButton("keychain.signin.copy_link");
        String link = context.computeOnClient(minecraft -> minecraft.keyboardHandler.getClipboard());
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

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("Keychain game test failed: " + what);
    }
}
