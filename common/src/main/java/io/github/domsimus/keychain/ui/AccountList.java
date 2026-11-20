package io.github.domsimus.keychain.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Gfx;
import io.github.domsimus.keychain.compat.KeychainList;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.session.SessionManager;
import io.github.domsimus.keychain.session.SkinCache;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The saved accounts. Click a row to select it, double-click (or press Enter, or click the hover
 * "Use" button) to switch, and click the star to favorite it.
 */
final class AccountList extends KeychainList<AccountList.Row> {
    static final int ROW_HEIGHT = 36;
    private static final int USE_HEIGHT = 16;
    private final AccountsScreen owner;

    AccountList(AccountsScreen owner, Minecraft minecraft, int width, int height, int top) {
        super(minecraft, width, height, top, ROW_HEIGHT);
        this.owner = owner;
    }

    /** Replaces the rows, keeping the selection on {@code select} when it is still listed. */
    void show(List<Account> accounts, UUID select) {
        double scroll = scrollAmount();
        replaceEntries(accounts.stream().map(Row::new).toList());
        Row match = children().stream().filter(row -> row.account.id().equals(select)).findFirst()
            .orElse(children().isEmpty() ? null : children().getFirst());
        setSelected(match);
        setScrollAmount(scroll);
    }

    Account selectedAccount() {
        Row row = getSelected();
        return row == null ? null : row.account;
    }

    /** Moves the selection one row up or down and keeps it in view (arrow keys from the search box). */
    void moveSelection(int direction) {
        List<Row> rows = children();
        if (rows.isEmpty()) return;
        int target = Math.max(0, Math.min(rows.size() - 1, rows.indexOf(getSelected()) + direction));
        setSelected(rows.get(target));
        ensureSelectedVisible();
    }

    /** Scrolls just far enough to show the selected row. */
    void ensureSelectedVisible() {
        Row row = getSelected();
        if (row != null) scrollToEntry(row);
    }

    /** Scrolls the selected row to the middle of the list (when the screen opens). */
    void centerOnSelected() {
        Row row = getSelected();
        if (row != null) centerScrollOn(row);
    }

    @Override
    public void setSelected(Row row) {
        super.setSelected(row);
        if (owner != null) owner.onSelectionChanged(row == null ? null : row.account);
    }

    final class Row extends KeychainList.Row<Row> {
        final Account account;
        private int starX = Integer.MIN_VALUE, starY, useX = Integer.MIN_VALUE, useY, useWidth;

        Row(Account account) {
            this.account = account;
        }

        @Override
        public Component getNarration() {
            Account.Status status = account.status(Instant.now());
            return Component.translatable("keychain.narration.row", account.name(), Messages.kind(account), Messages.status(status),
                Keychain.get().sessions().isActive(account) ? Component.translatable("keychain.badge.active") : Component.empty());
        }

        @Override
        protected void draw(Gfx gfx, int mouseX, int mouseY, boolean hovered, float delta) {
            Font font = minecraft.font;
            int x = getContentX(), y = getContentY(), w = getContentWidth(), h = getContentHeight();
            SessionManager sessions = Keychain.get().sessions();
            boolean selected = getSelected() == this;
            boolean active = sessions.isActive(account);
            boolean queued = sessions.isQueued(account);
            boolean refreshing = sessions.preparing().map(account.id()::equals).orElse(false);

            if (selected) {
                gfx.fill(x, y, x + w, y + h, Palette.ROW_SELECTED);
                gfx.outline(x, y, w, h, Palette.ROW_SELECTED_EDGE);
            } else if (hovered) {
                gfx.fill(x, y, x + w, y + h, Palette.ROW_HOVER);
            }

            int faceSize = Math.min(24, h - 6);
            int faceY = y + (h - faceSize) / 2;
            gfx.face(SkinCache.get(account), x + 5, faceY, faceSize);
            // Signed out (or very likely to be): a small badge on the head, explained on hover.
            Component warning = refreshing ? null : Messages.warning(account, Instant.now());
            if (warning != null) {
                int badgeX = x + 5 + faceSize - 7, badgeY = faceY + faceSize - 7;
                Palette.warningBadge(gfx, badgeX, badgeY);
                if (inside(mouseX, mouseY, badgeX - 1, badgeY - 1, 12, 12)) gfx.tooltip(font, warning, mouseX, mouseY);
            }

            // Right side: favorite star, and a "Use" button while hovering.
            int right = x + w - 4;
            starX = right - 11;
            starY = y + (h - 11) / 2;
            if (account.favorite()) gfx.sprite(Palette.STAR, starX, starY, 11, 11);
            else if (hovered) gfx.sprite(Palette.STAR_EMPTY, starX, starY, 11, 11);
            right = starX - 6;
            useX = Integer.MIN_VALUE;
            if (hovered && !active && !refreshing) {
                Component use = Component.translatable("keychain.row.use");
                useWidth = Math.max(40, font.width(use) + 16);
                useX = right - useWidth;
                useY = y + (h - USE_HEIGHT) / 2;
                Palette.button(gfx, font, use, useX, useY, useWidth, USE_HEIGHT, inside(mouseX, mouseY, useX, useY, useWidth, USE_HEIGHT));
                right = useX - 6;
            }

            int textX = x + faceSize + 12;
            int nameY = y + h / 2 - 10;
            Component badge = active ? Component.translatable("keychain.badge.active")
                : queued ? Component.translatable("keychain.badge.next") : null;
            int badgeWidth = badge == null ? 0 : font.width(badge) + 12;
            String name = font.plainSubstrByWidth(account.name(), Math.max(10, right - textX - badgeWidth));
            gfx.text(font, name, textX, nameY, Palette.TEXT);
            if (badge != null) {
                int badgeX = textX + font.width(name) + 6;
                int color = active ? Palette.GOOD : Palette.WARN;
                Palette.dot(gfx, badgeX, nameY + 2, color);
                gfx.text(font, badge, badgeX + 8, nameY, color);
            }

            // Second line: the kind of account in gray, then its status, colored only when it needs attention.
            int infoX = textX;
            int infoY = y + h / 2 + 2;
            if (account.isMicrosoft()) {
                Palette.microsoftLogo(gfx, infoX, infoY + 1, 7);
                infoX += 10;
            }
            Account.Status status = account.status(Instant.now());
            String kind = Messages.kind(account).getString() + "  ·  ";
            String state = refreshing ? Component.translatable("keychain.status.connecting").getString() : Messages.status(status).getString();
            int stateColor = !refreshing && status == Account.Status.NEEDS_SIGN_IN ? Palette.WARN : Palette.MUTED;
            int room = Math.max(10, right - infoX);
            if (font.width(kind) + font.width(state) <= room) {
                gfx.text(font, kind, infoX, infoY, Palette.MUTED);
                gfx.text(font, state, infoX + font.width(kind), infoY, stateColor);
            } else {
                gfx.text(font, font.plainSubstrByWidth(kind + state, room), infoX, infoY, stateColor);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            // The left button is 0 before 26.3 and 1 from 26.3 (SDL); the constant is right for each build.
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
            double mx = event.x(), my = event.y();
            setSelected(this);
            if (inside(mx, my, starX - 2, starY - 2, 15, 15)) {
                owner.toggleFavorite(account);
            } else if (useX != Integer.MIN_VALUE && inside(mx, my, useX, useY, useWidth, USE_HEIGHT)) {
                owner.use(account);
            } else if (doubleClick) {
                owner.use(account);
            }
            return true;
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
