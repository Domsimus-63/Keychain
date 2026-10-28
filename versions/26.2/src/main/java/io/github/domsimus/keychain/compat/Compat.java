package io.github.domsimus.keychain.compat;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.FriendsService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minecraft 26.2 specifics. Every versions/&lt;mc&gt; folder has a {@code Compat} with the same methods. */
public final class Compat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Keychain");

    private Compat() {}

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    public static Screen currentScreen() {
        return Minecraft.getInstance().gui.screen();
    }

    public static KeyMapping registerKey(KeyMapping key) {
        return KeyMappingHelper.registerKeyMapping(key);
    }

    public static ToastManager toasts() {
        return Minecraft.getInstance().gui.toastManager();
    }

    public static void openUri(URI uri) {
        Util.getPlatform().openUri(uri);
    }

    public static void addWidget(Screen screen, AbstractWidget widget) {
        Screens.getWidgets(screen).add(widget);
    }

    public static java.util.List<AbstractWidget> widgets(Screen screen) {
        return Screens.getWidgets(screen);
    }

    /** Ctrl (Cmd on macOS) plus {@code key}, using this version's key codes. */
    public static boolean isShortcut(KeyEvent event, int key) {
        return event.hasControlDownWithQuirk() && event.key() == key;
    }

    public static boolean isKey(KeyEvent event, int key) {
        return event.key() == key;
    }

    /**
     * Makes {@code user} the signed-in account. Besides the user itself this rebuilds everything
     * Minecraft derived from the old account at startup: the services API client, chat signing keys
     * (without this, servers that enforce secure chat reject the new account's messages), the
     * player-reporting context, the social manager and the cached profile used for the player's skin.
     * Only call while not connected to a world or server.
     */
    public static void applySession(Minecraft minecraft, User user, boolean online) {
        // Build everything first and assign at the end, so a failure leaves the old session intact.
        YggdrasilAuthenticationService authentication = online ? new YggdrasilAuthenticationService(minecraft.getProxy()) : YggdrasilAuthenticationService.createOffline(minecraft.getProxy());
        UserApiService api = UserApiService.OFFLINE;
        if (online) {
            try {
                api = authentication.createUserApiService(user.getAccessToken());
            } catch (Exception failure) {
                LOGGER.warn("Keychain could not reach Minecraft services for {}; chat signing stays off", user.getName(), failure);
            }
        }
        UserApiService service = api;
        CompletableFuture<UserApiService.UserProperties> properties = CompletableFuture.supplyAsync(() -> {
            try {
                return service.fetchProperties();
            } catch (Exception failure) {
                return UserApiService.OFFLINE_PROPERTIES;
            }
        }, Util.nonCriticalIoPool());
        CompletableFuture<ProfileResult> profile = online
            ? CompletableFuture.supplyAsync(() -> minecraft.services().sessionService().fetchProfile(user.getProfileId(), true), Util.nonCriticalIoPool())
            : CompletableFuture.<ProfileResult>completedFuture(null);
        ProfileKeyPairManager keys = ProfileKeyPairManager.create(service, user, minecraft.gameDirectory.toPath());
        ReportingContext reporting = ReportingContext.create(ReportEnvironment.local(), service);
        // The friends list belongs to the account too: poll for the new one, stop polling for the old one.
        FriendsService friends = authentication.createFriendsService(user.getAccessToken());
        RemoteFriendListUpdateHandler updates = new RemoteFriendListUpdateHandler(friends, minecraft);
        PlayerSocialManager social = new PlayerSocialManager(minecraft, service, friends, updates);
        RemoteFriendListUpdateHandler oldUpdates = minecraft.remoteFriendListUpdateHandler;

        minecraft.user = user;
        minecraft.userApiService = service;
        minecraft.userPropertiesFuture = properties;
        minecraft.profileFuture = profile;
        minecraft.profileKeyPairManager = keys;
        minecraft.reportingContext = reporting;
        minecraft.remoteFriendListUpdateHandler = updates;
        minecraft.playerSocialManager = social;

        try {
            oldUpdates.close();
        } catch (RuntimeException failure) {
            LOGGER.debug("Keychain could not stop the previous friend list updates", failure);
        }
        if (social.isFriendListEnabled()) updates.start();
    }
}
