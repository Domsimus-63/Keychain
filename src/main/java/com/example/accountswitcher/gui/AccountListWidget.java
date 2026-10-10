package com.example.accountswitcher.gui;

import com.example.accountswitcher.AccountSwitcherMod;
import com.example.accountswitcher.auth.Account;
import com.example.accountswitcher.auth.AccountQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class AccountListWidget extends ObjectSelectionList<AccountListWidget.Entry> {
    private final AccountSwitcherScreen parent;

    public AccountListWidget(AccountSwitcherScreen parent, Minecraft mc, int width, int height, int top, int itemHeight) {
        super(mc, width, height, top, itemHeight);
        this.parent = parent;
    }

    public void refresh() { refresh(false); }

    public void refresh(boolean resetScroll) {
        Account selected = parent.selectedAccount();
        UUID previous = selected == null ? null : selected.uuid;
        double scroll = scrollAmount();
        clearEntries();
        Entry preferred = null, first = null, current = null;
        for (Account account : AccountQuery.select(AccountSwitcherMod.storage().getAccounts(),
            parent.searchQuery(), parent.accountFilter(), parent.accountSort())) {
            Entry entry = new Entry(account);
            addEntry(entry);
            if (first == null) first = entry;
            if (Objects.equals(account.uuid, previous)) preferred = entry;
            if (Objects.equals(account.uuid, minecraft.getUser().getProfileId())) current = entry;
        }
        setSelected(preferred != null ? preferred : current != null ? current : first);
        setScrollAmount(resetScroll ? 0 : scroll);
    }

    @Override
    public void setSelected(Entry entry) {
        super.setSelected(entry);
        if (parent != null) parent.selectAccount(entry == null ? null : entry.account);
    }

    @Override
    public int getRowWidth() { return Math.max(60, width - 14); }

    static String formatRelative(Instant time) {
        if (time == null) return null;
        long seconds = Math.max(0, Duration.between(time, Instant.now()).getSeconds());
        if (seconds < 60) return "just now";
        if (seconds < 3600) return seconds / 60 + "m ago";
        if (seconds < 86400) return seconds / 3600 + "h ago";
        long days = seconds / 86400;
        if (days < 30) return days + "d ago";
        return days < 365 ? days / 30 + "mo ago" : days / 365 + "y ago";
    }

    public final class Entry extends ObjectSelectionList.Entry<Entry> {
        public final Account account;
        Entry(Account account) { this.account = account; }

        @Override
        public Component getNarration() {
            return Component.literal(account.username + ", "
                + (account.type == Account.Type.MICROSOFT ? "Microsoft" : "Offline")
                + (account.favorite ? ", favorite" : "")
                + (Objects.equals(account.uuid, minecraft.getUser().getProfileId()) ? ", current account" : "")
                + (account.isExpired() ? ", session needs refresh" : ""));
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (parent.isSwitching()) return true;
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                setSelected(this);
                parent.switchTo(account);
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mx, int my, boolean hovered, float delta) {
            int x = getContentX(), y = getContentY(), w = getContentWidth(), h = getContentHeight();
            boolean active = Objects.equals(account.uuid, minecraft.getUser().getProfileId());
            boolean selected = getSelected() == this;
            g.fill(x, y, x + w, y + h, selected ? 0xFF42453D : hovered ? 0xFF383838 : 0xEE292929);
            int edge = selected ? KeychainUi.GREEN : hovered ? 0xFF969696 : 0xFF565656;
            g.fill(x, y, x + w, y + 1, edge);
            g.fill(x, y + h - 1, x + w, y + h, 0xFF111111);
            g.fill(x, y, x + 1, y + h, edge);
            g.fill(x + w - 1, y, x + w, y + h, 0xFF111111);
            int size = Math.min(28, h - 8);
            PlayerFaceExtractor.extractRenderState(g, SkinPreviewCache.get(account), x + 6, y + 5, size);
            int textX = x + size + 14;
            String badge = active ? "Current" : account.favorite ? "*" : "";
            int badgeWidth = badge.isEmpty() ? 0 : minecraft.font.width(badge) + 12;
            g.text(minecraft.font, minecraft.font.plainSubstrByWidth(account.username,
                Math.max(8, x + w - textX - badgeWidth - 6)), textX, y + 6, KeychainUi.TEXT);
            if (!badge.isEmpty()) g.text(minecraft.font, badge,
                x + w - badgeWidth + 4, y + 6, active ? KeychainUi.GREEN : KeychainUi.GOLD);
            String info = account.type == Account.Type.MICROSOFT ? "Microsoft" : "Offline";
            info += account.isExpired() ? " | Needs refresh" : account.favorite ? " | Favorite" : "";
            g.text(minecraft.font, minecraft.font.plainSubstrByWidth(info, Math.max(8, x + w - textX - 6)),
                textX, y + 21, account.isExpired() ? KeychainUi.GOLD : KeychainUi.MUTED);
        }
    }
}
