package com.example.accountswitcher.gui;

import com.example.accountswitcher.AccountSwitcherMod;
import com.example.accountswitcher.auth.Account;
import com.example.accountswitcher.auth.AccountQuery;
import com.example.accountswitcher.auth.MicrosoftAuth;
import com.example.accountswitcher.session.SessionUtil;
import java.time.Instant;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

public final class AccountSwitcherScreen extends Screen {
    private final Screen parent;
    private AccountListWidget list;
    private Account selectedAccount;
    private EditBox searchField;
    private Button filterButton, sortButton, switchButton, favoriteButton, removeButton;
    private Button onlineButton, offlineButton;
    private PlayerSkinWidget model;
    private AccountQuery.Filter filter = AccountQuery.Filter.ALL;
    private AccountQuery.Sort sort = AccountQuery.Sort.SAVED;
    private String search = "", toastText;
    private long toastUntil;
    private boolean switching, refreshListPending;
    private int refreshGeneration;
    private int contentX, contentWidth, listWidth, listTop, listHeight, panelX, panelWidth;

    public AccountSwitcherScreen(Screen parent) {
        super(Component.literal("Keychain"));
        this.parent = parent;
    }

    public void showToast(String text) {
        toastText = text;
        toastUntil = System.currentTimeMillis() + 7000;
    }

    @Override
    protected void init() {
        contentWidth = Math.min(width - 20, 720);
        contentX = (width - contentWidth) / 2;
        panelWidth = width >= 500 && height >= 300 ? Math.min(184, contentWidth / 3) : 0;
        listWidth = contentWidth - (panelWidth == 0 ? 0 : panelWidth + 8);
        panelX = contentX + listWidth + 8;
        boolean compact = panelWidth == 0;
        int toolbarY = compact ? 44 : 48;
        listTop = compact ? 84 : 94;
        listHeight = Math.max(32, height - listTop - (compact ? 75 : 83));
        int filterWidth = Math.min(94, contentWidth / 4);
        int sortWidth = Math.min(122, contentWidth / 3);
        int searchWidth = contentWidth - filterWidth - sortWidth - 8;
        searchField = addRenderableWidget(new EditBox(font, contentX, toolbarY, searchWidth, 20,
            Component.literal("Search accounts")));
        searchField.setMaxLength(64);
        searchField.setHint(Component.literal("Search accounts..."));
        searchField.setValue(search);
        searchField.setResponder(value -> {
            search = value;
            if (list != null) list.refresh(true);
        });
        filterButton = addRenderableWidget(Button.builder(Component.literal("Show: " + filter.label), button -> {
            filter = filter.next();
            button.setMessage(Component.literal("Show: " + filter.label));
            list.refresh(true);
        }).bounds(contentX + searchWidth + 4, toolbarY, filterWidth, 20)
            .tooltip(Tooltip.create(Component.literal("Show all, online, offline, or favorite accounts"))).build());
        sortButton = addRenderableWidget(Button.builder(Component.literal(sort.label), button -> {
            sort = sort.next();
            button.setMessage(Component.literal(sort.label));
            list.refresh(true);
        }).bounds(contentX + searchWidth + filterWidth + 8, toolbarY, sortWidth, 20)
            .tooltip(Tooltip.create(Component.literal("Favorites stay first. Cycle saved order, name, and last used."))).build());
        list = new AccountListWidget(this, minecraft, listWidth, listHeight, listTop, compact ? 38 : 42);
        list.setX(contentX);
        addRenderableWidget(list);
        if (panelWidth > 0) {
            model = new PlayerSkinWidget(panelWidth - 12, Math.max(36, listHeight - 88),
                minecraft.getEntityModels(), () -> selectedAccount == null
                    ? DefaultPlayerSkin.getDefaultSkin() : SkinPreviewCache.get(selectedAccount));
            model.setX(panelX + 6);
            model.setY(listTop + 34);
            addRenderableWidget(model);
        } else model = null;
        int gap = 4, buttonWidth = (contentWidth - 2 * gap) / 3;
        int actionsY = height - (compact ? 50 : 58);
        int bottomY = height - (compact ? 26 : 32);
        switchButton = addRenderableWidget(Button.builder(Component.literal("Use selected"), button -> {
            if (selectedAccount != null) switchTo(selectedAccount);
        }).bounds(contentX, actionsY, buttonWidth, 20).build());
        favoriteButton = addRenderableWidget(Button.builder(Component.literal("Favorite"), button -> {
            if (selectedAccount != null && !switching) {
                if (AccountSwitcherMod.storage().toggleFavorite(selectedAccount)) {
                    showToast(selectedAccount.favorite ? "Added to favorites" : "Removed from favorites");
                    list.refresh(false);
                } else showToast("Could not save favorites.");
            }
        }).bounds(contentX + buttonWidth + gap, actionsY, buttonWidth, 20).build());
        removeButton = addRenderableWidget(Button.builder(Component.literal("Remove..."), button -> {
            if (selectedAccount != null && !switching)
                minecraft.setScreen(new ConfirmRemoveScreen(this, selectedAccount));
        }).bounds(contentX + 2 * (buttonWidth + gap), actionsY,
            contentWidth - 2 * (buttonWidth + gap), 20).build());
        onlineButton = addRenderableWidget(Button.builder(Component.literal("Add Microsoft"), button -> {
            if (!switching) minecraft.setScreen(new AddMicrosoftScreen(this));
        }).bounds(contentX, bottomY, buttonWidth, 20).build());
        offlineButton = addRenderableWidget(Button.builder(Component.literal("Add Offline"), button -> {
            if (!switching) minecraft.setScreen(new OfflineAccountScreen(this));
        }).bounds(contentX + buttonWidth + gap, bottomY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(contentX + 2 * (buttonWidth + gap), bottomY,
                contentWidth - 2 * (buttonWidth + gap), 20).build());
        list.refresh(false);
        selectAccount(selectedAccount);
    }

    String searchQuery() { return search; }
    AccountQuery.Filter accountFilter() { return filter; }
    AccountQuery.Sort accountSort() { return sort; }
    Account selectedAccount() { return selectedAccount; }

    public void switchTo(Account account) {
        if (switching || account == null) return;
        if (isCurrentSession(account) && !account.isExpired()) {
            showToast("Already using " + account.username);
            return;
        }
        if (account.type != Account.Type.MICROSOFT || !account.isExpired()) {
            finishSwitch(account);
            return;
        }
        if (account.refreshToken == null || account.refreshToken.isBlank()) {
            showToast("Session expired. Add this Microsoft account again.");
            return;
        }
        switching = true;
        int generation = ++refreshGeneration;
        selectAccount(selectedAccount);
        showToast("Refreshing " + account.username + "...");
        Thread worker = new Thread(() -> {
            try {
                Account refreshed = MicrosoftAuth.refresh(account, message -> {});
                minecraft.execute(() -> {
                    if (generation != refreshGeneration) return;
                    switching = false;
                    finishSwitch(refreshed);
                    selectAccount(selectedAccount);
                });
            } catch (Exception error) {
                AccountSwitcherMod.LOGGER.warn("Microsoft refresh failed for {}", account.username, error);
                minecraft.execute(() -> {
                    if (generation != refreshGeneration) return;
                    switching = false;
                    selectAccount(selectedAccount);
                    showToast("Refresh failed. Add this Microsoft account again to sign in.");
                });
            }
        }, "Keychain-Refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void finishSwitch(Account account) {
        try {
            SessionUtil.apply(account);
            Instant previous = account.lastUsedAt;
            account.lastUsedAt = Instant.now();
            boolean saved = AccountSwitcherMod.storage().add(account);
            if (!saved) account.lastUsedAt = previous;
            refreshListPending = saved;
            showToast(saved ? "Now using " + account.username
                + (minecraft.getConnection() == null ? "" : ". Reconnect to use this account on the server.")
                : "Account switched, but the account list could not be saved.");
            selectAccount(selectedAccount);
        } catch (Exception error) {
            AccountSwitcherMod.LOGGER.error("Could not switch account", error);
            showToast("Could not switch account. Your current session was kept.");
        }
    }

    public void removeAndRefresh(Account account) {
        if (AccountSwitcherMod.storage().remove(account)) {
            list.refresh(false);
            showToast("Removed " + account.username + ". Current session kept.");
        } else showToast("Could not save account removal.");
    }

    @Override
    public void tick() {
        super.tick();
        if (refreshListPending && list != null) {
            refreshListPending = false;
            list.refresh(false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        KeychainUi.line(g, font, "Keychain", width / 2, 12, contentWidth, KeychainUi.TEXT);
        KeychainUi.line(g, font, "Current account: " + minecraft.getUser().getName(),
            width / 2, 28, contentWidth, KeychainUi.GREEN);
        g.drawString(font, list.children().size() + " / " + AccountSwitcherMod.storage().getAccounts().size()
            + " accounts", contentX + 2, listTop - 16, KeychainUi.MUTED);
        String hint = switching ? "Refreshing session..." : "Click to switch | Ctrl+F to search";
        int hintWidth = font.width(hint);
        if (hintWidth + 92 < contentWidth)
            g.drawString(font, hint, contentX + contentWidth - hintWidth - 2, listTop - 16, KeychainUi.MUTED);
        if (list.children().isEmpty()) {
            int cx = contentX + listWidth / 2, y = listTop + listHeight / 2 - 12;
            boolean unreadable = AccountSwitcherMod.storage().isUnreadable();
            boolean empty = AccountSwitcherMod.storage().getAccounts().isEmpty();
            KeychainUi.line(g, font, unreadable ? "Saved accounts could not be read."
                : empty ? "Your keychain is empty." : "No matching accounts.",
                cx, y, listWidth - 16, unreadable ? KeychainUi.RED : KeychainUi.TEXT);
            KeychainUi.line(g, font, unreadable ? "Your original vault has been kept."
                : empty ? "Add an account below to get started." : "Try another search or filter.",
                cx, y + 14, listWidth - 16, KeychainUi.MUTED);
        }
        if (panelWidth > 0) {
            int cx = panelX + panelWidth / 2;
            KeychainUi.line(g, font, selectedAccount == null ? "Account preview" : selectedAccount.username,
                cx, listTop + 10, panelWidth - 16, KeychainUi.TEXT);
            if (selectedAccount != null) {
                String type = selectedAccount.type == Account.Type.MICROSOFT ? "Microsoft" : "Offline";
                KeychainUi.line(g, font, type + (selectedAccount.favorite ? " | Favorite" : ""),
                    cx, listTop + 23, panelWidth - 16, KeychainUi.MUTED);
                KeychainUi.line(g, font, isCurrent(selectedAccount) ? "Current account"
                    : selectedAccount.isExpired() ? "Refreshes when used" : "Ready to use",
                    cx, listTop + listHeight - 42, panelWidth - 16,
                    selectedAccount.isExpired() ? KeychainUi.GOLD : KeychainUi.GREEN);
                KeychainUi.line(g, font, SkinPreviewCache.status(selectedAccount),
                    cx, listTop + listHeight - 27, panelWidth - 16, KeychainUi.MUTED);
                String used = AccountListWidget.formatRelative(selectedAccount.lastUsedAt);
                KeychainUi.line(g, font, used == null ? "Never used" : "Used " + used,
                    cx, listTop + listHeight - 14, panelWidth - 16, KeychainUi.MUTED);
            } else KeychainUi.line(g, font, "Select an account", cx,
                listTop + listHeight / 2, panelWidth - 16, KeychainUi.MUTED);
        }
        String status = toastText != null && System.currentTimeMillis() < toastUntil ? toastText
            : "Keychain 3.0 | " + (selectedAccount == null ? "Accounts saved on this device"
                : selectedAccount.username + (isCurrent(selectedAccount) ? " | Current account" : " | Selected"));
        KeychainUi.line(g, font, status, width / 2, height - (panelWidth == 0 ? 65 : 75), contentWidth - 8, KeychainUi.GOLD);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        super.renderBackground(g, mx, my, delta);
        KeychainUi.panel(g, contentX, listTop, listWidth, listHeight);
        if (panelWidth > 0) KeychainUi.panel(g, panelX, listTop, panelWidth, listHeight);
    }

    private boolean isCurrent(Account account) { return account.uuid.equals(minecraft.getUser().getProfileId()); }

    private boolean isCurrentSession(Account account) {
        return isCurrent(account) && java.util.Objects.equals(account.accessToken, minecraft.getUser().getAccessToken())
            && account.username.equals(minecraft.getUser().getName());
    }

    void selectAccount(Account account) {
        selectedAccount = account;
        boolean usable = account != null && !switching;
        if (switchButton != null) {
            switchButton.active = usable && (!isCurrentSession(account) || account.isExpired());
            switchButton.setMessage(Component.literal(switching ? "Refreshing..."
                : account != null && isCurrentSession(account) && !account.isExpired() ? "Current account" : "Use selected"));
        }
        if (favoriteButton != null) {
            favoriteButton.active = usable;
            favoriteButton.setMessage(Component.literal(account != null && account.favorite ? "Unfavorite" : "Favorite"));
        }
        if (removeButton != null) removeButton.active = usable;
        if (model != null) model.visible = account != null;
        if (onlineButton != null) onlineButton.active = !switching && !AccountSwitcherMod.storage().isUnreadable();
        if (offlineButton != null) offlineButton.active = !switching && !AccountSwitcherMod.storage().isUnreadable();
        if (filterButton != null) filterButton.active = !switching;
        if (sortButton != null) sortButton.active = !switching;
        if (searchField != null) searchField.active = !switching;
    }

    boolean isSwitching() { return switching; }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_F && event.hasControlDown()) {
            setFocused(searchField);
            searchField.setFocused(true);
            return true;
        }
        if (event.isEscape() && searchField.isFocused() && !searchField.getValue().isEmpty()) {
            searchField.setValue("");
            return true;
        }
        if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && getFocused() == list) {
            switchTo(selectedAccount);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        ++refreshGeneration;
        switching = false;
        super.removed();
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
