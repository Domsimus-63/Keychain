package io.github.domsimus.keychain.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.compat.Gfx;
import io.github.domsimus.keychain.compat.KeychainList;
import io.github.domsimus.keychain.compat.KeychainScreen;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Picks the language of Keychain's own screens: a scrolling list, applied as soon as it is clicked. */
public final class LanguageScreen extends KeychainScreen {
    private final Screen parent;
    private final Keychain keychain = Keychain.get();
    private LanguageList list;
    private int listTop, listBottom, columnX, columnWidth;

    public LanguageScreen(Screen parent) {
        super(Component.translatable("keychain.language.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        double scroll = list == null ? -1 : list.scrollAmount();
        columnWidth = Math.min(280, width - 24);
        columnX = (width - columnWidth) / 2;
        listTop = 38;
        listBottom = height - 36;
        list = new LanguageList(minecraft, columnWidth, listBottom - listTop, listTop);
        list.setX(columnX);
        list.populate();
        addRenderableWidget(list);
        if (scroll >= 0) list.setScrollAmount(scroll);
        else list.ensureSelectedVisible();
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    protected void setInitialFocus() {
        setInitialFocus(list);
    }

    /**
     * Applies the language right away. Nothing has to be rebuilt: translated text is looked up
     * again as soon as the language table changes, so this screen switches over on the next frame.
     */
    void choose(String code) {
        if (code.equals(KeychainLanguage.selected())) return;
        keychain.settings().language = code;
        keychain.settings().save();
        KeychainLanguage.select(code);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && getFocused() == list && list.getSelected() != null) {
            choose(list.getSelected().code);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Compat.setScreen(parent);
    }

    @Override
    protected void drawForeground(Gfx gfx, int mouseX, int mouseY, float delta) {
        gfx.centered(font, title, width / 2, Palette.TITLE_Y, Palette.TEXT);
        gfx.centered(font, Component.translatable("keychain.language.subtitle"), width / 2, Palette.SUBTITLE_Y, Palette.MUTED);
    }

    private final class LanguageList extends KeychainList<LanguageList.Row> {
        LanguageList(Minecraft minecraft, int width, int height, int top) {
            super(minecraft, width, height, top, 28);
        }

        /** Called once the list has its final position: rows take their place from it when added. */
        void populate() {
            List<Row> rows = new ArrayList<>();
            rows.add(new Row("", Component.translatable("keychain.language.game"), Component.translatable("keychain.language.game.hint")));
            for (KeychainLanguage.Option option : KeychainLanguage.OPTIONS) {
                rows.add(new Row(option.code(), Component.literal(option.nativeName()), Component.literal(option.englishName())));
            }
            replaceEntries(rows);
            String current = KeychainLanguage.selected();
            setSelected(rows.stream().filter(row -> row.code.equals(current)).findFirst().orElse(rows.getFirst()));
        }

        void ensureSelectedVisible() {
            Row row = getSelected();
            if (row != null) scrollToEntry(row);
        }

        final class Row extends KeychainList.Row<Row> {
            final String code;
            private final Component name, detail;

            Row(String code, Component name, Component detail) {
                this.code = code;
                this.name = name;
                this.detail = detail;
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", name);
            }

            @Override
            protected void draw(Gfx gfx, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX(), y = getContentY(), w = getContentWidth(), h = getContentHeight();
                boolean active = code.equals(KeychainLanguage.selected());
                if (getSelected() == this) {
                    gfx.fill(x, y, x + w, y + h, Palette.ROW_SELECTED);
                    gfx.outline(x, y, w, h, Palette.ROW_SELECTED_EDGE);
                } else if (hovered) {
                    gfx.fill(x, y, x + w, y + h, Palette.ROW_HOVER);
                }
                if (active) Palette.dot(gfx, x + 5, y + 5, Palette.GOOD);
                gfx.text(font, name, x + 16, y + 3, active ? Palette.GOOD : Palette.TEXT);
                gfx.text(font, font.plainSubstrByWidth(detail.getString(), w - 22), x + 16, y + 13, Palette.FAINT);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
                setSelected(this);
                choose(code);
                return true;
            }
        }
    }
}
