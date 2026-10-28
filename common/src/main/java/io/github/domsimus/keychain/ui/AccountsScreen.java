package io.github.domsimus.keychain.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.compat.Gfx;
import io.github.domsimus.keychain.compat.KeychainScreen;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.AccountQuery;
import io.github.domsimus.keychain.core.vault.AccountVault;
import io.github.domsimus.keychain.session.SessionManager;
import io.github.domsimus.keychain.session.SkinCache;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The main Keychain screen: the account list, a preview of the selected account, and actions. */
public final class AccountsScreen extends KeychainScreen {
    private static final int GAP = 4;
    private static final long UNDO_MILLIS = 10_000;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final Screen parent;
    private final Keychain keychain = Keychain.get();
    private final Runnable vaultListener = () -> dirty = true;
    private AccountQuery query = AccountQuery.DEFAULT;
    private UUID selectedId;
    private boolean dirty;
    private Account removed;
    private int removedIndex;
    private long removedAt;

    private AccountList list;
    private EditBox search;
    private Button filterButton, sortButton, useButton, favoriteButton, removeButton, upButton, downButton, copyButton, resetButton, undoButton;
    private PlayerSkinWidget preview;
    private int contentX, contentWidth, listWidth, listTop, listBottom, sideX, sideWidth;

    public AccountsScreen(Screen parent) {
        super(Component.translatable("keychain.title"));
        this.parent = parent;
        UUID current = minecraftInstance().getUser().getProfileId();
        if (keychain.vault().find(current).isPresent()) selectedId = current;
    }

    private static net.minecraft.client.Minecraft minecraftInstance() {
        return net.minecraft.client.Minecraft.getInstance();
    }

    @Override
    protected void init() {
        keychain.removeListener(vaultListener);
        keychain.addListener(vaultListener);

        contentWidth = Math.min(width - 16, 760);
        contentX = (width - contentWidth) / 2;
        // The preview panel needs ~400 scaled pixels: that is a 1080p window at the default GUI scale.
        boolean wide = contentWidth >= 400 && height >= 220;
        sideWidth = wide ? Math.max(120, Math.min(200, contentWidth * 3 / 10)) : 0;
        listWidth = contentWidth - (wide ? sideWidth + 6 : 0);
        sideX = contentX + listWidth + 6;
        int footerY = height - 26;
        int actionsY = footerY - 24;
        int toolbarY = 33;
        listTop = 68;
        listBottom = actionsY - 6;

        int cycleWidth = Math.min(96, (listWidth - GAP * 2) / 4);
        int searchWidth = listWidth - 2 * (cycleWidth + GAP);
        search = addRenderableWidget(new EditBox(font, contentX, toolbarY, searchWidth, 20, Component.translatable("keychain.search")));
        search.setHint(Component.translatable("keychain.search.hint"));
        search.setMaxLength(40);
        search.setValue(query.search());
        search.setResponder(value -> {
            query = query.withSearch(value);
            rebuildList();
        });
        filterButton = addRenderableWidget(Button.builder(filterLabel(), button -> {
            query = query.withFilter(query.filter().next());
            button.setMessage(filterLabel());
            rebuildList();
        }).bounds(contentX + searchWidth + GAP, toolbarY, cycleWidth, 20).tooltip(Tooltip.create(Component.translatable("keychain.filter.tooltip"))).build());
        sortButton = addRenderableWidget(Button.builder(sortLabel(), button -> {
            query = query.withSort(query.sort().next());
            button.setMessage(sortLabel());
            rebuildList();
        }).bounds(contentX + searchWidth + cycleWidth + 2 * GAP, toolbarY, cycleWidth, 20).tooltip(Tooltip.create(Component.translatable("keychain.sort.tooltip"))).build());

        list = new AccountList(this, minecraft, listWidth, listBottom - listTop, listTop);
        list.setX(contentX);
        addRenderableWidget(list);

        Component undo = Component.translatable("keychain.action.undo");
        int undoWidth = font.width(undo) + 14;
        undoButton = addRenderableWidget(Button.builder(undo, b -> undoRemove())
            .bounds(contentX + listWidth - undoWidth, listTop - 14, undoWidth, 12).build());
        undoButton.visible = undoAvailable();

        if (wide) {
            int arrow = 20;
            int each = (contentWidth - 2 * arrow - 5 * GAP) / 4;
            int x = contentX;
            useButton = addRenderableWidget(Button.builder(Component.translatable("keychain.action.use"), b -> useSelected())
                .bounds(x, actionsY, each, 20).build());
            x += each + GAP;
            favoriteButton = addRenderableWidget(Button.builder(Component.translatable("keychain.action.favorite"), b -> favoriteSelected())
                .bounds(x, actionsY, each, 20).build());
            x += each + GAP;
            upButton = addRenderableWidget(Button.builder(Component.literal("▲"), b -> moveSelected(-1))
                .bounds(x, actionsY, arrow, 20).tooltip(Tooltip.create(Component.translatable("keychain.action.move_up"))).build());
            x += arrow + GAP;
            downButton = addRenderableWidget(Button.builder(Component.literal("▼"), b -> moveSelected(1))
                .bounds(x, actionsY, arrow, 20).tooltip(Tooltip.create(Component.translatable("keychain.action.move_down"))).build());
            x += arrow + GAP;
            copyButton = addRenderableWidget(Button.builder(Component.translatable("keychain.action.copy_uuid"), b -> copySelectedUuid())
                .bounds(x, actionsY, each, 20).build());
            x += each + GAP;
            removeButton = addRenderableWidget(Button.builder(Component.translatable("keychain.action.remove"), b -> removeSelected())
                .bounds(x, actionsY, contentX + contentWidth - x, 20).build());
            int previewTop = listTop + 30;
            int previewHeight = listBottom - 30 - previewTop;
            preview = null;
            if (previewHeight >= 40) {
                preview = new PlayerSkinWidget(sideWidth - 8, previewHeight, minecraft.getEntityModels(), () -> {
                    Account account = selected();
                    return account == null ? DefaultPlayerSkin.getDefaultSkin() : SkinCache.get(account);
                });
                preview.setPosition(sideX + 4, previewTop);
                addRenderableWidget(preview);
            }
        } else {
            int third = (contentWidth - 2 * GAP) / 3;
            useButton = addRenderableWidget(Button.builder(Component.translatable("keychain.action.use"), b -> useSelected())
                .bounds(contentX, actionsY, third, 20).build());
            favoriteButton = addRenderableWidget(Button.builder(Component.translatable("keychain.action.favorite"), b -> favoriteSelected())
                .bounds(contentX + third + GAP, actionsY, third, 20).build());
            removeButton = addRenderableWidget(Button.builder(Component.translatable("keychain.action.remove"), b -> removeSelected())
                .bounds(contentX + 2 * (third + GAP), actionsY, contentWidth - 2 * (third + GAP), 20).build());
            upButton = downButton = copyButton = null;
            preview = null;
        }

        int quarter = (contentWidth - 3 * GAP) / 4;
        addRenderableWidget(Button.builder(Component.translatable("keychain.action.add_microsoft"), b -> Compat.setScreen(new MicrosoftSignInScreen(this)))
            .bounds(contentX, footerY, quarter, 20).build()).active = vaultReady();
        addRenderableWidget(Button.builder(Component.translatable("keychain.action.add_offline"), b -> Compat.setScreen(new OfflineAccountScreen(this)))
            .bounds(contentX + quarter + GAP, footerY, quarter, 20).build()).active = vaultReady();
        addRenderableWidget(Button.builder(Component.translatable("keychain.action.settings"), b -> Compat.setScreen(new SettingsScreen(this)))
            .bounds(contentX + 2 * (quarter + GAP), footerY, quarter, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(contentX + 3 * (quarter + GAP), footerY, contentWidth - 3 * (quarter + GAP), 20).build());

        resetButton = null;
        if (!vaultReady()) {
            resetButton = addRenderableWidget(Button.builder(Component.translatable("keychain.vault.reset"), b -> resetVault())
                .bounds(contentX + listWidth / 2 - 80, listTop + (listBottom - listTop) / 2 + 22, 160, 20).build());
        }

        rebuildList();
    }

    // ---- state -------------------------------------------------------------------------------

    private boolean vaultReady() {
        return keychain.vault().state() == AccountVault.State.READY;
    }

    private Account selected() {
        return selectedId == null ? null : keychain.vault().find(selectedId).orElse(null);
    }

    private void rebuildList() {
        if (list == null) return;
        dirty = false;
        list.show(query.apply(keychain.vault().accounts()), selectedId);
        updateButtons();
    }

    void onSelectionChanged(Account account) {
        selectedId = account == null ? null : account.id();
        updateButtons();
    }

    private void updateButtons() {
        Account account = selected();
        boolean has = account != null;
        SessionManager sessions = keychain.sessions();
        boolean busy = has && sessions.preparing().map(account.id()::equals).orElse(false);
        boolean active = has && sessions.isActive(account);
        boolean queuedHere = has && sessions.isQueued(account);
        // While another account waits for the player to leave, the active one offers to cancel that.
        boolean stay = active && sessions.queued().isPresent() && !queuedHere;
        Account.Status status = has ? account.status(Instant.now()) : null;
        if (useButton != null) {
            useButton.active = has && !busy && !queuedHere && (stay || !(active && status == Account.Status.READY));
            useButton.setMessage(Component.translatable(busy ? "keychain.status.connecting"
                : stay ? "keychain.action.stay"
                : queuedHere ? "keychain.action.queued"
                : active ? "keychain.action.in_use"
                : status == Account.Status.NEEDS_SIGN_IN ? "keychain.action.sign_in" : "keychain.action.use"));
        }
        if (favoriteButton != null) {
            favoriteButton.active = has;
            favoriteButton.setMessage(Component.translatable(has && account.favorite() ? "keychain.action.unfavorite" : "keychain.action.favorite"));
        }
        if (removeButton != null) removeButton.active = has;
        if (copyButton != null) copyButton.active = has;
        if (upButton != null) upButton.active = has && neighbor(account, -1) != null;
        if (downButton != null) downButton.active = has && neighbor(account, 1) != null;
        if (undoButton != null) undoButton.visible = undoAvailable();
        if (preview != null) preview.visible = has;
    }

    /**
     * The account next to {@code account} in the visible list, if moving past it is meaningful:
     * only in the custom order, and favorites and the rest are reordered within their own group.
     */
    private Account neighbor(Account account, int direction) {
        if (!query.allowsReordering()) return null;
        List<Account> shown = query.apply(keychain.vault().accounts());
        int index = -1;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).id().equals(account.id())) index = i;
        int target = index + direction;
        if (index < 0 || target < 0 || target >= shown.size()) return null;
        Account other = shown.get(target);
        return other.favorite() == account.favorite() ? other : null;
    }

    private boolean undoAvailable() {
        return removed != null && System.currentTimeMillis() - removedAt < UNDO_MILLIS && vaultReady();
    }

    // ---- actions -----------------------------------------------------------------------------

    void use(Account account) {
        keychain.sessions().switchTo(account).thenAccept(outcome -> {
            if (outcome == SessionManager.Outcome.NEEDS_SIGN_IN) {
                // Only take over the screen if the player is still looking at Keychain.
                if (Compat.currentScreen() == this) Compat.setScreen(new MicrosoftSignInScreen(this));
                else SessionManager.toast(Component.translatable("keychain.toast.restore_failed", account.name()), Messages.reason(io.github.domsimus.keychain.core.auth.AuthException.Reason.SESSION_REVOKED));
            }
            updateButtons();
        });
        updateButtons();
    }

    private void useSelected() {
        Account account = selected();
        if (account == null) return;
        if (keychain.sessions().isActive(account) && keychain.sessions().queued().isPresent()) {
            keychain.sessions().cancelQueued();
            updateButtons();
            return;
        }
        use(account);
    }

    void toggleFavorite(Account account) {
        if (!keychain.vault().setFavorite(account.id(), !account.favorite())) {
            SessionManager.toast(Component.translatable("keychain.toast.save_failed"), null);
        }
    }

    private void favoriteSelected() {
        Account account = selected();
        if (account != null) toggleFavorite(account);
    }

    private void moveSelected(int direction) {
        Account account = selected();
        Account other = account == null ? null : neighbor(account, direction);
        if (other != null) keychain.vault().placeNextTo(account.id(), other.id(), direction > 0);
    }

    private void copySelectedUuid() {
        Account account = selected();
        if (account == null) return;
        minecraft.keyboardHandler.setClipboard(account.id().toString());
        SessionManager.toast(Component.translatable("keychain.toast.copied_uuid", account.name()), null);
    }

    private void removeSelected() {
        Account account = selected();
        if (account == null) return;
        Component message = Component.translatable(keychain.sessions().isActive(account)
            ? "keychain.remove.message_active" : "keychain.remove.message", account.name());
        Compat.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                int index = keychain.vault().accounts().indexOf(account);
                if (keychain.vault().remove(account.id())) {
                    removed = account;
                    removedIndex = index;
                    removedAt = System.currentTimeMillis();
                } else {
                    SessionManager.toast(Component.translatable("keychain.toast.save_failed"), null);
                }
            }
            if (confirmed && keychain.sessions().queued().map(next -> next.id().equals(account.id())).orElse(false)) {
                keychain.sessions().cancelQueued();
            }
            Compat.setScreen(this);
        }, Component.translatable("keychain.remove.title", account.name()), message));
    }

    private void undoRemove() {
        if (!undoAvailable()) return;
        Account account = removed;
        removed = null;
        if (keychain.vault().update(vault -> vault.restore(account, removedIndex))) {
            selectedId = account.id();
            rebuildList();
        }
    }

    private void resetVault() {
        try {
            keychain.resetVault();
            SessionManager.toast(Component.translatable("keychain.vault.reset.done"), null);
        } catch (java.io.IOException failure) {
            Keychain.LOGGER.error("Keychain could not move the unreadable vault aside", failure);
            SessionManager.toast(Component.translatable("keychain.toast.save_failed"), null);
        }
        rebuildWidgets();
    }

    private Component filterLabel() {
        return Component.translatable("keychain.filter", Component.translatable("keychain.filter." + query.filter().name().toLowerCase(Locale.ROOT)));
    }

    private Component sortLabel() {
        return Component.translatable("keychain.sort", Component.translatable("keychain.sort." + query.sort().name().toLowerCase(Locale.ROOT)));
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (dirty) {
            boolean readable = vaultReady();
            if ((resetButton != null) == readable) rebuildWidgets();
            else rebuildList();
        }
        updateButtons();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (Compat.isShortcut(event, 'f')) {
            setFocused(search);
            return true;
        }
        if (Compat.isShortcut(event, 'z') && !search.isFocused() && undoAvailable()) {
            undoRemove();
            return true;
        }
        if (event.isEscape() && search.isFocused() && !search.getValue().isEmpty()) {
            search.setValue("");
            return true;
        }
        if (getFocused() == list || (getFocused() == search && list.children().size() == 1)) {
            if (event.isConfirmation()) {
                useSelected();
                return true;
            }
            if (getFocused() == list && (Compat.isKey(event, InputConstants.KEY_DELETE) || (MAC && Compat.isKey(event, InputConstants.KEY_BACKSPACE)))) {
                removeSelected();
                return true;
            }
            if (getFocused() == list && event.hasAltDown() && (event.isUp() || event.isDown())) {
                moveSelected(event.isUp() ? -1 : 1);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        keychain.removeListener(vaultListener);
        super.removed();
    }

    @Override
    public void onClose() {
        Compat.setScreen(parent);
    }

    // ---- drawing -----------------------------------------------------------------------------

    @Override
    protected void drawBackdrop(Gfx gfx, int mouseX, int mouseY, float delta) {
        if (sideWidth > 0) Palette.panel(gfx, sideX, listTop, sideWidth, listBottom - listTop);
    }

    @Override
    protected void drawForeground(Gfx gfx, int mouseX, int mouseY, float delta) {
        gfx.centered(font, title, width / 2, 8, Palette.TEXT);
        drawCurrentAccount(gfx);

        int listCenter = contentX + listWidth / 2;
        int listMiddle = listTop + (listBottom - listTop) / 2;
        AccountVault vault = keychain.vault();
        if (vault.state() == AccountVault.State.UNREADABLE) {
            gfx.centered(font, Component.translatable("keychain.vault.unreadable"), listCenter, listMiddle - 18, Palette.BAD);
            gfx.centered(font, Component.translatable("keychain.vault.unreadable.hint"), listCenter, listMiddle - 4, Palette.MUTED);
        } else if (vault.accounts().isEmpty()) {
            gfx.centered(font, Component.translatable("keychain.empty"), listCenter, listMiddle - 10, Palette.TEXT);
            gfx.centered(font, Component.translatable("keychain.empty.hint"), listCenter, listMiddle + 4, Palette.MUTED);
        } else if (list.children().isEmpty()) {
            gfx.centered(font, Component.translatable("keychain.no_matches"), listCenter, listMiddle - 4, Palette.MUTED);
        }

        if (undoAvailable()) {
            Component gone = Component.translatable("keychain.removed", removed.name());
            gfx.text(font, Component.literal(font.plainSubstrByWidth(gone.getString(), listWidth - undoButton.getWidth() - 10)), contentX + 2, listTop - 11, Palette.MUTED);
        } else if (keychain.migrationProblem() != null) {
            gfx.centered(font, Component.translatable("keychain.migration_failed"), width / 2, listTop - 11, Palette.WARN);
        } else {
            Component count = Component.translatable("keychain.count", list.children().size(), vault.accounts().size());
            gfx.text(font, count, contentX + 2, listTop - 11, Palette.FAINT);
            Component hint = Component.translatable("keychain.hint", MAC ? "Cmd" : "Ctrl");
            if (font.width(count) + font.width(hint) + 24 < listWidth) {
                gfx.text(font, hint, contentX + listWidth - font.width(hint) - 2, listTop - 11, Palette.FAINT);
            }
        }

        if (sideWidth > 0) drawSidePanel(gfx);
    }

    private void drawCurrentAccount(Gfx gfx) {
        SessionManager sessions = keychain.sessions();
        Component line = sessions.queued()
            .map(next -> (Component) Component.translatable("keychain.header.queued", minecraft.getUser().getName(), next.name()))
            .orElseGet(() -> Component.translatable("keychain.header.playing_as", minecraft.getUser().getName()));
        int color = sessions.queued().isPresent() ? Palette.WARN : Palette.MUTED;
        int lineWidth = font.width(line);
        int x = width / 2 - (lineWidth + 12) / 2;
        Account current = keychain.vault().find(minecraft.getUser().getProfileId()).orElse(null);
        if (current != null) gfx.face(SkinCache.get(current), x, 20, 8);
        else gfx.face(SkinCache.forProfile(minecraft.getUser().getProfileId()), x, 20, 8);
        gfx.text(font, line, x + 12, 20, color);
    }

    private void drawSidePanel(Gfx gfx) {
        Account account = selected();
        int center = sideX + sideWidth / 2;
        int inner = sideWidth - 16;
        if (account == null) {
            gfx.centered(font, Component.translatable("keychain.preview.none"), center, listTop + (listBottom - listTop) / 2 - 30, Palette.FAINT);
            return;
        }
        gfx.centered(font, Component.literal(font.plainSubstrByWidth(account.name(), inner)), center, listTop + 6, Palette.TEXT);
        Component kind = Messages.kind(account);
        int kindWidth = font.width(kind) + (account.isMicrosoft() ? 11 : 0);
        int kindX = center - kindWidth / 2;
        if (account.isMicrosoft()) {
            Palette.microsoftLogo(gfx, kindX, listTop + 18, 7);
            kindX += 11;
        }
        gfx.text(font, kind, kindX, listTop + 17, Palette.MUTED);

        boolean active = keychain.sessions().isActive(account);
        boolean busy = keychain.sessions().preparing().map(account.id()::equals).orElse(false);
        Account.Status status = account.status(Instant.now());
        Component statusLine = busy ? Component.translatable("keychain.status.connecting")
            : active ? Component.translatable("keychain.badge.active") : Messages.status(status);
        if (!busy && account.session() != null && status == Account.Status.READY) {
            statusLine = Component.translatable("keychain.preview.with_time", statusLine, Messages.remaining(account.session().expiresAt()));
        }
        int statusColor = busy ? Palette.MUTED : active ? Palette.GOOD : Messages.statusColor(status);
        int y = listBottom - 25;
        int statusWidth = font.width(statusLine) + 8;
        Palette.dot(gfx, center - statusWidth / 2, y + 2, statusColor);
        gfx.text(font, statusLine, center - statusWidth / 2 + 8, y, statusColor);
        Component used = Component.translatable("keychain.preview.last_used", Messages.lastUsed(account.lastUsed()));
        gfx.centered(font, Component.literal(font.plainSubstrByWidth(used.getString(), inner)), center, y + 12, Palette.FAINT);
        if (preview != null && account.isMicrosoft() && SkinCache.state(account) == SkinCache.State.LOADING) {
            gfx.centered(font, Component.translatable("keychain.preview.loading"), center, listTop + 40, Palette.FAINT);
        }
    }
}
