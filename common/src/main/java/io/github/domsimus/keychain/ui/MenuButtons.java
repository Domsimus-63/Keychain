package io.github.domsimus.keychain.ui;

import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.core.Settings;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Adds the Keychain key button to the title screen, pause menu and server list. The button sits
 * next to a well-known vanilla button and moves aside if another mod already uses that spot.
 */
public final class MenuButtons {
    private static final int SIZE = 20;
    private static final int GAP = 4;

    private MenuButtons() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            Settings settings = Keychain.get().settings();
            if (screen instanceof TitleScreen && settings.titleScreenButton) {
                add(screen, "menu.multiplayer");
            } else if (screen instanceof PauseScreen && settings.pauseMenuButton) {
                // Skip the button-less pause overlay (F3+Esc).
                if (find(screen, "menu.returnToGame").isPresent()) add(screen, "menu.returnToGame");
            } else if (screen instanceof JoinMultiplayerScreen && settings.multiplayerButton) {
                add(screen, "selectServer.select");
            }
        });
    }

    private static void add(Screen screen, String anchorKey) {
        Minecraft minecraft = Minecraft.getInstance();
        SpriteIconButton button = SpriteIconButton.builder(Component.translatable("keychain.title"),
                pressed -> Compat.setScreen(new AccountsScreen(screen)), true)
            .sprite(Palette.ICON, 16, 16)
            .size(SIZE, SIZE)
            .build();
        button.setTooltip(Tooltip.create(Component.translatable("keychain.button.tooltip", minecraft.getUser().getName())));
        int[] spot = place(screen, find(screen, anchorKey).orElse(null));
        button.setPosition(spot[0], spot[1]);
        Compat.addWidget(screen, button);
    }

    private static int[] place(Screen screen, AbstractWidget anchor) {
        List<AbstractWidget> widgets = Compat.widgets(screen);
        if (anchor != null) {
            int[][] candidates = {
                {anchor.getX() - SIZE - GAP, anchor.getY()},
                {anchor.getX() + anchor.getWidth() + GAP, anchor.getY()},
            };
            for (int[] candidate : candidates) {
                if (fits(screen, widgets, candidate[0], candidate[1])) return candidate;
            }
        }
        for (int x = GAP; x + SIZE <= screen.width; x += SIZE + GAP) {
            if (fits(screen, widgets, x, GAP)) return new int[] {x, GAP};
        }
        return new int[] {GAP, GAP};
    }

    private static boolean fits(Screen screen, List<AbstractWidget> widgets, int x, int y) {
        if (x < 0 || y < 0 || x + SIZE > screen.width || y + SIZE > screen.height) return false;
        for (AbstractWidget widget : widgets) {
            if (!widget.visible) continue;
            boolean overlaps = x < widget.getX() + widget.getWidth() && x + SIZE > widget.getX()
                && y < widget.getY() + widget.getHeight() && y + SIZE > widget.getY();
            if (overlaps) return false;
        }
        return true;
    }

    private static Optional<AbstractWidget> find(Screen screen, String translationKey) {
        for (AbstractWidget widget : Compat.widgets(screen)) {
            if (widget.getMessage().getContents() instanceof TranslatableContents contents && contents.getKey().equals(translationKey)) {
                return Optional.of(widget);
            }
        }
        return Optional.empty();
    }
}
