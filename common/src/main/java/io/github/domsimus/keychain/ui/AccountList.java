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

    @Override
    public void setSelected(Row row) {
        super.setSelected(row);
        if (owner != null) owner.onSelectionChanged(row == null ? null : row.account);
    }

    @Override
    public int getRowWidth() {
        return Math.max(80, width - 16);
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
            boolean queued = sessions.queued().map(next -> next.id().equals(account.id())).orElse(false);
            boolean refreshing = sessions.preparing().map(account.id()::equals).orElse(false);

            if (selected) {
                gfx.fill(x, y, x + w, y + h, Palette.ROW_SELECTED);
                gfx.outline(x, y, w, h, Palette.ROW_SELECTED_EDGE);
            } else if (hovered) {
                gfx.fill(x, y, x + w, y + h, Palette.ROW_HOVER);
            }

            int faceSize = Math.min(24, h - 6);
            gfx.face(SkinCache.get(account), x + 5, y + (h - faceSize) / 2, faceSize);

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
                useWidth = font.width(use) + 12;
                useX = right - useWidth;
                useY = y + (h - 14) / 2;
                boolean overUse = inside(mouseX, mouseY, useX, useY, useWidth, 14);
                gfx.fill(useX, useY, useX + useWidth, useY + 14, overUse ? 0xFF4C8A3F : 0xFF3A3A3A);
                gfx.outline(useX, useY, useWidth, 14, overUse ? 0xFF8CE07A : 0xFF6A6A6A);
                gfx.centered(font, use, useX + useWidth / 2, useY + 3, Palette.TEXT);
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

            int infoX = textX;
            int infoY = y + h / 2 + 2;
            if (account.isMicrosoft()) {
                Palette.microsoftLogo(gfx, infoX, infoY + 1, 7);
                infoX += 10;
            }
            Account.Status status = account.status(Instant.now());
            String info = Messages.kind(account).getString() + "  ·  "
                + (refreshing ? Component.translatable("keychain.status.connecting").getString() : Messages.status(status).getString());
            int infoColor = refreshing ? Palette.MUTED : status == Account.Status.NEEDS_SIGN_IN ? Palette.WARN : Palette.MUTED;
            gfx.text(font, font.plainSubstrByWidth(info, Math.max(10, right - infoX)), infoX, infoY, infoColor);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            // The left button is 0 before 26.3 and 1 from 26.3 (SDL); the constant is right for each build.
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
            double mx = event.x(), my = event.y();
            setSelected(this);
            if (inside(mx, my, starX - 2, starY - 2, 15, 15)) {
                owner.toggleFavorite(account);
            } else if (useX != Integer.MIN_VALUE && inside(mx, my, useX, useY, useWidth, 14)) {
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
