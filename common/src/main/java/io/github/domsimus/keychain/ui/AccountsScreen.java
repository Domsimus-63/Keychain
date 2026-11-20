package io.github.domsimus.keychain.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.compat.Gfx;
import io.github.domsimus.keychain.compat.KeychainScreen;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.account.AccountQuery;
import io.github.domsimus.keychain.core.auth.AuthException;
import io.github.domsimus.keychain.core.vault.AccountVault;
import io.github.domsimus.keychain.core.vault.VaultData;
import io.github.domsimus.keychain.session.SessionManager;
import io.github.domsimus.keychain.session.SkinCache;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The main Keychain screen: the account list, a preview of the selected account, and actions. */
public final class AccountsScreen extends KeychainScreen {
    private static final int GAP = 4;
    /** The two button rows under the list: a little smaller than vanilla's 20 x 200 buttons. */
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_MAX_WIDTH = 116;
    private static final long UNDO_MILLIS = 10_000;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final Screen parent;
    private final Keychain keychain = Keychain.get();
    private final Runnable vaultListener = () -> dirty = true;
    /** The session when this screen opened, to tell on closing whether the account changed. */
    private final User openedAs;
    private AccountQuery query = AccountQuery.DEFAULT;
    private UUID selectedId;
    private boolean dirty;
    private Account removed;
    private int removedIndex;
    private boolean removedWasLastActive;
    private long removedAt;

    private AccountList list;
    private EditBox search;
    private Button filterButton, sortButton, useButton, favoriteButton, removeButton, copyButton, resetButton, undoButton, launcherButton;
    private PlayerSkinWidget preview;
    private int contentX, contentWidth, listWidth, listTop, listBottom, sideX, sideWidth;

    public AccountsScreen(Screen parent) {
        super(Component.translatable("keychain.title"));
        this.parent = parent;
        this.openedAs = Minecraft.getInstance().getUser();
        UUID current = openedAs.getProfileId();
        if (keychain.vault().find(current).isPresent()) selectedId = current;
    }

    @Override
    protected void init() {
        keychain.removeListener(vaultListener);
        keychain.addListener(vaultListener);
        // Whatever kept the vault from opening may have been temporary: try again whenever the screen (re)opens.
        if (!vaultReady()) keychain.vault().load();
        // Keep the scroll position across resizes; a newly opened screen centers on the selection.
        double scroll = list == null ? -1 : list.scrollAmount();

        contentWidth = Math.min(width - 16, 760);
        contentX = (width - contentWidth) / 2;
        // The preview panel needs ~400 scaled pixels: that is a 1080p window at the default GUI scale.
        boolean wide = contentWidth >= 400 && height >= 220;
        sideWidth = wide ? Math.max(120, Math.min(200, contentWidth * 3 / 10)) : 0;
        listWidth = contentWidth - (wide ? sideWidth + 6 : 0);
        sideX = contentX + listWidth + 6;
        int footerY = height - Palette.BOTTOM_MARGIN - BUTTON_HEIGHT;
        int actionsY = footerY - BUTTON_HEIGHT - GAP;
        int toolbarY = 36;
        listTop = 74;
        listBottom = actionsY - 6;

        // Wide enough for the longest label in the current language, but never crowding out the search box.
        int cycleWidth = Math.min(Math.max(widestCycleLabel() + 12, 70), (listWidth - GAP * 2) * 3 / 10);
        int searchWidth = listWidth - 2 * (cycleWidth + GAP);
        search = addRenderableWidget(new EditBox(font, contentX, toolbarY, searchWidth, 20, Component.translatable("keychain.search")));
        search.setHint(Component.translatable("keychain.search.hint"));
        search.setMaxLength(40);
        search.setValue(query.search());
        search.setResponder(value -> {
            query = query.withSearch(value);
            rebuildList();
            list.ensureSelectedVisible();
        });
        filterButton = addRenderableWidget(Button.builder(filterLabel(), button -> {
            query = query.withFilter(query.filter().next());
            button.setMessage(filterLabel());
            rebuildList();
            list.ensureSelectedVisible();
        }).bounds(contentX + searchWidth + GAP, toolbarY, cycleWidth, 20).tooltip(Tooltip.create(Component.translatable("keychain.filter.tooltip"))).build());
        sortButton = addRenderableWidget(Button.builder(sortLabel(), button -> {
            query = query.withSort(query.sort().next());
            button.setMessage(sortLabel());
            rebuildList();
            list.ensureSelectedVisible();
        }).bounds(contentX + searchWidth + cycleWidth + 2 * GAP, toolbarY, cycleWidth, 20).tooltip(Tooltip.create(Component.translatable("keychain.sort.tooltip"))).build());

        list = new AccountList(this, minecraft, listWidth, listBottom - listTop, listTop);
        list.setX(contentX);
        addRenderableWidget(list);

        Component undo = Component.translatable("keychain.action.undo");
        int undoWidth = font.width(undo) + 14;
        undoButton = addRenderableWidget(Button.builder(undo, b -> undoRemove())
            .bounds(contentX + listWidth - undoWidth, listTop - 16, undoWidth, 14).build());
        undoButton.visible = undoAvailable();

        if (wide) {
            List<Button> actions = buttonRow(actionsY,
                Button.builder(Component.translatable("keychain.action.use"), b -> useSelected()),
                Button.builder(Component.translatable("keychain.action.favorite"), b -> favoriteSelected()),
                Button.builder(Component.translatable("keychain.action.copy_uuid"), b -> copySelectedUuid()),
                Button.builder(Component.translatable("keychain.action.remove"), b -> removeSelected()));
            useButton = actions.get(0);
            favoriteButton = actions.get(1);
            copyButton = actions.get(2);
            removeButton = actions.get(3);
            int previewTop = listTop + 32;
            int previewHeight = listBottom - 32 - previewTop;
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
            List<Button> actions = buttonRow(actionsY,
                Button.builder(Component.translatable("keychain.action.use"), b -> useSelected()),
                Button.builder(Component.translatable("keychain.action.favorite"), b -> favoriteSelected()),
                Button.builder(Component.translatable("keychain.action.remove"), b -> removeSelected()));
            useButton = actions.get(0);
            favoriteButton = actions.get(1);
            removeButton = actions.get(2);
            copyButton = null;
            preview = null;
        }

        List<Button> footer = buttonRow(footerY,
            Button.builder(Component.translatable("keychain.action.add_microsoft"), b -> Compat.setScreen(new MicrosoftSignInScreen(this))),
            Button.builder(Component.translatable("keychain.action.add_offline"), b -> Compat.setScreen(new OfflineAccountScreen(this))),
            Button.builder(Component.translatable("keychain.action.settings"), b -> Compat.setScreen(new SettingsScreen(this))),
            Button.builder(CommonComponents.GUI_DONE, b -> onClose()));
        footer.get(0).active = vaultReady();
        footer.get(1).active = vaultReady();

        resetButton = null;
        if (!vaultReady()) {
            resetButton = addRenderableWidget(Button.builder(Component.translatable("keychain.vault.reset"), b -> resetVault())
                .bounds(contentX + listWidth / 2 - 80, listTop + (listBottom - listTop) / 2 + 22, 160, 20).build());
        }

        // Added last so keyboard focus still starts at the search box. It sits in the header line, right
        // after "Playing as ...", and follows that text when it changes (see header()).
        launcherButton = null;
        keychain.sessions().launcher().ifPresent(launcher -> {
            Component label = Component.translatable("keychain.action.launcher", launcher.getName());
            int buttonWidth = Math.min(font.width(label) + 12, contentWidth / 2);
            launcherButton = addRenderableWidget(Button.builder(label, b -> useLauncher())
                .bounds(contentX, Palette.SUBTITLE_Y - 3, buttonWidth, 14)
                .tooltip(Tooltip.create(Component.translatable("keychain.action.launcher.tooltip"))).build());
        });

        if (launcherButton != null) launcherButton.setX(header().x() + 12 + font.width(header().text()) + 8);

        rebuildList();
        if (scroll >= 0) list.setScrollAmount(scroll);
        else list.centerOnSelected();
    }

    /** Lays out equal buttons in one centered row under the content area. */
    private List<Button> buttonRow(int y, Button.Builder... builders) {
        int count = builders.length;
        int each = Math.min(BUTTON_MAX_WIDTH, (contentWidth - (count - 1) * GAP) / count);
        int x = contentX + (contentWidth - count * each - (count - 1) * GAP) / 2;
        List<Button> row = new ArrayList<>();
        for (Button.Builder builder : builders) {
            row.add(addRenderableWidget(builder.bounds(x, y, each, BUTTON_HEIGHT).build()));
            x += each + GAP;
        }
        return row;
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
        boolean stay = active && sessions.queuedName().isPresent() && !queuedHere;
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
                else SessionManager.toast(Component.translatable("keychain.toast.restore_failed", account.name()), Messages.reason(AuthException.Reason.SESSION_REVOKED));
            }
            updateButtons();
        });
        updateButtons();
    }

    private void useSelected() {
        Account account = selected();
        if (account == null) return;
        if (keychain.sessions().isActive(account) && keychain.sessions().queuedName().isPresent()) {
            keychain.sessions().cancelQueued();
            updateButtons();
            return;
        }
        use(account);
    }

    private void useLauncher() {
        keychain.sessions().switchToLauncher();
        updateButtons();
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
        if (other != null && keychain.vault().placeNextTo(account.id(), other.id(), direction > 0)) {
            rebuildList();
            list.ensureSelectedVisible();
        }
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
            if (confirmed) remove(account.id());
            Compat.setScreen(this);
        }, Component.translatable("keychain.remove.title", account.name()), message));
    }

    private void remove(UUID id) {
        // Remember the account as it is saved right now (its tokens may have been renewed since it
        // was selected), so Undo puts back exactly what was removed.
        VaultData before = keychain.vault().data();
        Account current = before.find(id).orElse(null);
        if (current == null) return;
        if (!keychain.vault().remove(id)) {
            SessionManager.toast(Component.translatable("keychain.toast.save_failed"), null);
            return;
        }
        removed = current;
        removedIndex = before.accounts().indexOf(current);
        removedWasLastActive = id.equals(before.lastActive());
        removedAt = System.currentTimeMillis();
        if (keychain.sessions().isQueued(current)) keychain.sessions().cancelQueued();
    }

    private void undoRemove() {
        if (!undoAvailable()) return;
        Account account = removed;
        int index = removedIndex;
        boolean lastActive = removedWasLastActive;
        removed = null;
        boolean restored = keychain.vault().update(vault -> {
            VaultData back = vault.restore(account, index);
            // "Remember account" pointed at it before it was removed: point it there again.
            return lastActive && back.lastActive() == null ? back.withLastActive(account.id()) : back;
        });
        if (restored) {
            selectedId = account.id();
            rebuildList();
            list.ensureSelectedVisible();
        } else {
            SessionManager.toast(Component.translatable("keychain.toast.save_failed"), null);
        }
    }

    private void resetVault() {
        try {
            boolean moved = keychain.resetVault() != null;
            SessionManager.toast(Component.translatable(moved ? "keychain.vault.reset.done" : "keychain.vault.recovered"), null);
        } catch (java.io.IOException failure) {
            Keychain.LOGGER.error("Keychain could not move the unreadable vault aside", failure);
            SessionManager.toast(Component.translatable("keychain.toast.save_failed"), null);
        }
        rebuildWidgets();
    }

    /** Width of the longest "Show: ..." or "Sort: ..." label, so no option ever has to scroll. */
    private int widestCycleLabel() {
        int widest = 0;
        for (AccountQuery.Filter filter : AccountQuery.Filter.values()) {
            widest = Math.max(widest, font.width(Component.translatable("keychain.filter", Component.translatable("keychain.filter." + filter.name().toLowerCase(Locale.ROOT)))));
        }
        for (AccountQuery.Sort sort : AccountQuery.Sort.values()) {
            widest = Math.max(widest, font.width(Component.translatable("keychain.sort", Component.translatable("keychain.sort." + sort.name().toLowerCase(Locale.ROOT)))));
        }
        return widest;
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
        boolean readable = vaultReady();
        boolean launcherOffered = keychain.sessions().launcher().isPresent();
        // The reset button and the launcher button come and go with the state they belong to.
        if ((resetButton != null) == readable || (launcherButton != null) != launcherOffered) rebuildWidgets();
        else if (dirty) rebuildList();
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
        boolean searching = getFocused() == search;
        if (searching && !event.hasAltDown() && (event.isUp() || event.isDown())) {
            // Pick a result without leaving the search box.
            list.moveSelection(event.isUp() ? -1 : 1);
            return true;
        }
        // Enter in the search box uses the highlighted result, but only once something was typed
        // (or there is just one account), so a stray Enter never switches accounts.
        if (getFocused() == list || (searching && (!search.getValue().isBlank() || list.children().size() == 1))) {
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

    /**
     * Rebuilding (a resize, or a button appearing after a switch) recreates every widget and clears
     * the focus. Put it back, so typing in the search box or stepping through the list carries on.
     */
    @Override
    protected void rebuildWidgets() {
        boolean searching = search != null && getFocused() == search;
        boolean listing = list != null && getFocused() == list;
        super.rebuildWidgets();
        if (searching) setFocused(search);
        else if (listing) setFocused(list);
    }

    @Override
    public void removed() {
        keychain.removeListener(vaultListener);
        super.removed();
    }

    @Override
    public void onClose() {
        // The title screen this was opened from still shows the old account's Realms invitations
        // and news: after a switch, return to a fresh one.
        boolean switched = minecraft.getUser() != openedAs;
        Compat.setScreen(switched && parent instanceof TitleScreen ? new TitleScreen() : parent);
    }

    // ---- drawing -----------------------------------------------------------------------------

    @Override
    protected void drawBackdrop(Gfx gfx, int mouseX, int mouseY, float delta) {
        // The same frame as the list next to it, edge to edge.
        if (sideWidth > 0) Palette.panel(gfx, sideX, listTop, sideWidth, listBottom - listTop);
    }

    @Override
    protected void drawForeground(Gfx gfx, int mouseX, int mouseY, float delta) {
        gfx.centered(font, title, width / 2, Palette.TITLE_Y, Palette.TEXT);
        drawCurrentAccount(gfx);

        int listCenter = contentX + listWidth / 2;
        int listMiddle = listTop + (listBottom - listTop) / 2;
        AccountVault vault = keychain.vault();
        if (vault.state() == AccountVault.State.UNREADABLE) {
            gfx.centered(font, Component.translatable("keychain.vault.unreadable"), listCenter, listMiddle - 18, Palette.BAD);
            gfx.centered(font, Component.translatable("keychain.vault.unreadable.hint"), listCenter, listMiddle - 4, Palette.MUTED);
            // The technical reason, so "made by a newer Keychain" is not mistaken for a lost key.
            String problem = vault.problem();
            if (problem != null) gfx.centered(font, Component.literal(font.plainSubstrByWidth(problem, listWidth - 16)), listCenter, listMiddle + 9, Palette.FAINT);
        } else if (vault.accounts().isEmpty()) {
            gfx.centered(font, Component.translatable("keychain.empty"), listCenter, listMiddle - 10, Palette.TEXT);
            gfx.centered(font, Component.translatable("keychain.empty.hint"), listCenter, listMiddle + 4, Palette.MUTED);
        } else if (list.children().isEmpty()) {
            gfx.centered(font, Component.translatable("keychain.no_matches"), listCenter, listMiddle - 4, Palette.MUTED);
        }

        if (undoAvailable()) {
            Component gone = Component.translatable("keychain.removed", removed.name());
            gfx.text(font, Component.literal(font.plainSubstrByWidth(gone.getString(), listWidth - undoButton.getWidth() - 10)), contentX + 2, listTop - 13, Palette.MUTED);
        } else if (!vault.accounts().isEmpty()) {
            Component count = Component.translatable("keychain.count", list.children().size(), vault.accounts().size());
            gfx.text(font, count, contentX + 2, listTop - 13, Palette.MUTED);
            int used = font.width(count);
            // A small warning when accounts have to be signed in again, so it is seen before a switch fails.
            Instant now = Instant.now();
            long signedOut = vault.accounts().stream().filter(account -> account.status(now) == Account.Status.NEEDS_SIGN_IN).count();
            if (signedOut > 0) {
                Component warning = Component.translatable("keychain.count.signed_out", signedOut);
                int x = contentX + 2 + used + 8;
                Palette.warningBadge(gfx, x, listTop - 14);
                gfx.text(font, warning, x + 12, listTop - 13, Palette.WARN);
                used += 20 + font.width(warning);
                if (mouseX >= x && mouseX < x + 12 + font.width(warning) && mouseY >= listTop - 15 && mouseY < listTop - 3) {
                    gfx.tooltip(font, Component.translatable("keychain.warning.signed_out.tooltip"), mouseX, mouseY);
                }
            }
            Component hint = Component.translatable("keychain.hint", MAC ? "Cmd" : "Ctrl");
            if (used + font.width(hint) + 24 < listWidth) {
                gfx.text(font, hint, contentX + listWidth - font.width(hint) - 2, listTop - 13, Palette.FAINT);
            }
        }

        if (sideWidth > 0) drawSidePanel(gfx);
    }

    /** The "Playing as ..." line under the title, centered together with the launcher button after it. */
    private record Header(String text, boolean waiting, int x) {}

    private Header header() {
        User user = minecraft.getUser();
        String next = keychain.sessions().queuedName().orElse(null);
        // While a switch waits, say only who is next: that is what matters then, and the short line
        // leaves room for the toast that appears in the top right corner at the same moment.
        Component line = next != null ? Component.translatable("keychain.header.queued.short", next)
            : Component.translatable("keychain.header.playing_as", user.getName());
        int buttonSpace = launcherButton != null ? launcherButton.getWidth() + 8 : 0;
        int room = Math.max(20, contentWidth - buttonSpace - 12);
        String text = font.plainSubstrByWidth(line.getString(), room);
        int group = 12 + font.width(text) + buttonSpace;
        return new Header(text, next != null, contentX + (contentWidth - group) / 2);
    }

    private void drawCurrentAccount(Gfx gfx) {
        Header header = header();
        int y = Palette.SUBTITLE_Y;
        gfx.face(SkinCache.forProfile(minecraft.getUser().getProfileId()), header.x(), y, 8);
        gfx.text(font, header.text(), header.x() + 12, y, header.waiting() ? Palette.WARN : Palette.MUTED);
        if (launcherButton != null) launcherButton.setX(header.x() + 12 + font.width(header.text()) + 8);
    }

    private void drawSidePanel(Gfx gfx) {
        Account account = selected();
        int center = sideX + sideWidth / 2;
        int inner = sideWidth - 16;
        if (account == null) {
            // With nothing in the list there is nothing to select; the list itself explains what to do.
            if (list.children().isEmpty()) return;
            gfx.centered(font, Component.translatable("keychain.preview.none"), center, listTop + (listBottom - listTop) / 2 - 4, Palette.FAINT);
            return;
        }
        gfx.centered(font, Component.literal(font.plainSubstrByWidth(account.name(), inner)), center, listTop + 8, Palette.TEXT);
        Component kind = Messages.kind(account);
        int kindWidth = font.width(kind) + (account.isMicrosoft() ? 11 : 0);
        int kindX = center - kindWidth / 2;
        if (account.isMicrosoft()) {
            Palette.microsoftLogo(gfx, kindX, listTop + 21, 7);
            kindX += 11;
        }
        gfx.text(font, kind, kindX, listTop + 20, Palette.MUTED);

        boolean active = keychain.sessions().isActive(account);
        boolean busy = keychain.sessions().preparing().map(account.id()::equals).orElse(false);
        Account.Status status = account.status(Instant.now());
        Component statusLine = busy ? Component.translatable("keychain.status.connecting")
            : active ? Component.translatable("keychain.badge.active") : Messages.status(status);
        if (!busy && account.session() != null && status == Account.Status.READY) {
            statusLine = Component.translatable("keychain.preview.with_time", statusLine, Messages.remaining(account.session().expiresAt()));
        }
        int statusColor = busy ? Palette.MUTED : active ? Palette.GOOD : Messages.statusColor(status);
        int y = listBottom - 26;
        int statusWidth = font.width(statusLine) + 8;
        Palette.dot(gfx, center - statusWidth / 2, y + 2, statusColor);
        gfx.text(font, statusLine, center - statusWidth / 2 + 8, y, statusColor);
        Component used = account.lastUsed() == null ? Component.translatable("keychain.preview.never_used")
            : Component.translatable("keychain.preview.last_used", Messages.lastUsed(account.lastUsed()));
        gfx.centered(font, Component.literal(font.plainSubstrByWidth(used.getString(), inner)), center, y + 12, Palette.MUTED);
        if (preview != null && account.isMicrosoft() && SkinCache.state(account) == SkinCache.State.LOADING) {
            gfx.centered(font, Component.translatable("keychain.preview.loading"), center, listTop + 44, Palette.FAINT);
        }
    }
}
