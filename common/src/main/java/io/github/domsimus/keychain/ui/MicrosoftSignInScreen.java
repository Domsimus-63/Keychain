package io.github.domsimus.keychain.ui;

import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.compat.Gfx;
import io.github.domsimus.keychain.compat.KeychainScreen;
import io.github.domsimus.keychain.core.account.Account;
import io.github.domsimus.keychain.core.auth.AuthException;
import io.github.domsimus.keychain.core.auth.Cancellation;
import io.github.domsimus.keychain.core.auth.MicrosoftAuth;
import io.github.domsimus.keychain.session.SessionManager;
import java.net.URI;
import java.util.Locale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Microsoft sign-in in the browser, like the official launcher: Keychain opens Microsoft's page,
 * the browser returns to a local page when done, and the account is added and switched to.
 */
public final class MicrosoftSignInScreen extends KeychainScreen {
    private enum Phase { WAITING, WORKING, FAILED }

    private static final MicrosoftAuth.Step[] STEPS = {
        MicrosoftAuth.Step.MICROSOFT, MicrosoftAuth.Step.XBOX_LIVE, MicrosoftAuth.Step.MINECRAFT, MicrosoftAuth.Step.PROFILE
    };

    private final Screen parent;
    private final Keychain keychain = Keychain.get();
    private Cancellation cancellation;
    private int attempt;
    private Phase phase = Phase.WAITING;
    private MicrosoftAuth.Step step;
    private String browserUrl;
    private AuthException failure;
    private boolean started;
    private int panelX, panelY, panelWidth, panelHeight;

    public MicrosoftSignInScreen(Screen parent) {
        super(Component.translatable("keychain.signin.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(340, width - 24);
        panelHeight = 150;
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(44, (height - panelHeight) / 2 - 14);
        if (!started) {
            started = true;
            start();
        }
        int inner = panelWidth - 24;
        int half = (inner - 4) / 2;
        if (phase == Phase.WAITING) {
            int buttonsY = panelY + panelHeight - 28;
            addRenderableWidget(Button.builder(Component.translatable("keychain.signin.open_again"), b -> openBrowser())
                .bounds(panelX + 12, buttonsY, half, 20).build()).active = browserUrl != null;
            addRenderableWidget(Button.builder(Component.translatable("keychain.signin.copy_link"), b -> copy(browserUrl, "keychain.toast.copied_link"))
                .bounds(panelX + 16 + half, buttonsY, inner - half - 4, 20).build()).active = browserUrl != null;
        }

        int footerY = height - 28;
        if (phase == Phase.FAILED) {
            int footerWidth = Math.min(panelWidth + 60, width - 24);
            int footerX = (width - footerWidth) / 2;
            int third = (footerWidth - 8) / 3;
            addRenderableWidget(Button.builder(Component.translatable("keychain.signin.retry"), b -> restart())
                .bounds(footerX, footerY, third, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("keychain.signin.copy_details"), b -> copy(details(), "keychain.toast.copied_details"))
                .bounds(footerX + third + 4, footerY, third, 20).build());
            addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(footerX + 2 * (third + 4), footerY, footerWidth - 2 * (third + 4), 20).build());
        } else {
            addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 - 100, footerY, 200, 20).build());
        }
    }

    private void restart() {
        start();
        rebuildWidgets();
    }

    private void start() {
        if (cancellation != null) cancellation.cancel();
        Cancellation current = new Cancellation();
        cancellation = current;
        int run = ++attempt;
        phase = Phase.WAITING;
        step = null;
        browserUrl = null;
        failure = null;
        MicrosoftAuth.Listener listener = new MicrosoftAuth.Listener() {
            @Override
            public void step(MicrosoftAuth.Step next) {
                onMain(run, () -> {
                    step = next;
                    Phase previous = phase;
                    phase = next == MicrosoftAuth.Step.WAITING_FOR_USER ? Phase.WAITING : Phase.WORKING;
                    if (previous != phase) rebuildWidgets();
                });
            }

            @Override
            public void browserUrl(String url) {
                onMain(run, () -> {
                    browserUrl = url;
                    openBrowser();
                    rebuildWidgets();
                });
            }
        };
        keychain.auth().signInWithBrowser(listener, current).whenComplete((account, error) -> onMain(run, () -> {
            if (error == null) finish(account);
            else fail(MicrosoftAuth.unwrap(error));
        }));
    }

    /** Runs on the game thread, and only if this attempt is still the current one. */
    private void onMain(int run, Runnable action) {
        minecraft.execute(() -> {
            if (run == attempt && !cancellation.isCancelled()) action.run();
        });
    }

    private void finish(Account account) {
        if (!keychain.vault().upsert(account)) {
            fail(new AuthException(AuthException.Reason.SERVICE_ERROR, "Signed in, but the account could not be saved to " + keychain.vault().file()));
            return;
        }
        Account saved = keychain.vault().find(account.id()).orElse(account);
        Compat.setScreen(parent);
        keychain.sessions().switchTo(saved);
    }

    private void fail(AuthException error) {
        if (error.reason() == AuthException.Reason.CANCELLED) return;
        Keychain.LOGGER.warn("Keychain Microsoft sign-in failed ({}): {}", error.reason(), error.getMessage());
        failure = error;
        phase = Phase.FAILED;
        rebuildWidgets();
    }

    private void openBrowser() {
        if (browserUrl == null) return;
        // Set only by the in-game test run, which must not open the developer's real browser.
        if (Boolean.getBoolean("keychain.test.noBrowser")) return;
        try {
            Compat.openUri(URI.create(browserUrl));
        } catch (RuntimeException failure) {
            // "Copy link" still works; the page tells the user to use it.
            Keychain.LOGGER.warn("Keychain could not open the browser", failure);
        }
    }

    private void copy(String text, String toastKey) {
        if (text == null) return;
        minecraft.keyboardHandler.setClipboard(text);
        SessionManager.toast(Component.translatable(toastKey), null);
    }

    private String details() {
        if (failure == null) return "";
        return "Keychain " + Keychain.version() + " Microsoft sign-in failed\n"
            + "Reason: " + failure.reason() + "\n"
            + "Client ID: " + (keychain.settings().usesCustomClientId() ? "custom" : "default") + "\n"
            + "Detail: " + failure.getMessage();
    }

    @Override
    public void removed() {
        if (cancellation != null) cancellation.cancel();
        super.removed();
    }

    @Override
    public void onClose() {
        if (cancellation != null) cancellation.cancel();
        Compat.setScreen(parent);
    }

    @Override
    protected void drawBackdrop(Gfx gfx, int mouseX, int mouseY, float delta) {
        Palette.panel(gfx, panelX, panelY, panelWidth, panelHeight);
    }

    @Override
    protected void drawForeground(Gfx gfx, int mouseX, int mouseY, float delta) {
        int center = width / 2;
        int titleWidth = font.width(title) + 12;
        Palette.microsoftLogo(gfx, center - titleWidth / 2, 15, 8);
        gfx.text(font, title, center - titleWidth / 2 + 12, 15, Palette.TEXT);
        gfx.centered(font, Component.translatable("keychain.signin.subtitle"), center, 29, Palette.MUTED);

        int inner = panelWidth - 24;
        int y = panelY + 12;
        switch (phase) {
            case FAILED -> {
                gfx.centered(font, Component.translatable("keychain.signin.failed"), center, y, Palette.BAD);
                y += 16;
                y += gfx.wrapped(font, Messages.reason(failure.reason()), center, y, inner, Palette.TEXT, 5) + 6;
                gfx.wrapped(font, Component.literal(failure.getMessage() == null ? "" : failure.getMessage()), center, y, inner, Palette.FAINT, 3);
            }
            case WAITING -> {
                gfx.centered(font, Component.translatable(browserUrl == null ? "keychain.signin.preparing" : "keychain.signin.browser"), center, y, Palette.TEXT);
                gfx.wrapped(font, Component.translatable("keychain.signin.browser.hint"), center, y + 16, inner, Palette.MUTED, 4);
                drawSpinner(gfx, center, y + 66);
            }
            case WORKING -> {
                gfx.centered(font, Component.translatable("keychain.signin.working"), center, y, Palette.TEXT);
                drawSteps(gfx, center, y + 22);
            }
        }
    }

    private void drawSteps(Gfx gfx, int center, int y) {
        int current = -1;
        for (int i = 0; i < STEPS.length; i++) if (STEPS[i] == step) current = i;
        int labelWidth = 0;
        for (MicrosoftAuth.Step value : STEPS) labelWidth = Math.max(labelWidth, font.width(stepLabel(value)));
        int x = center - (labelWidth + 14) / 2;
        for (int i = 0; i < STEPS.length; i++) {
            int rowY = y + i * 14;
            if (i < current) {
                gfx.text(font, Component.literal("✔"), x, rowY, Palette.GOOD);
                gfx.text(font, stepLabel(STEPS[i]), x + 14, rowY, Palette.MUTED);
            } else if (i == current) {
                gfx.text(font, Component.literal(System.currentTimeMillis() / 300 % 2 == 0 ? "»" : "›"), x, rowY, Palette.WARN);
                gfx.text(font, stepLabel(STEPS[i]), x + 14, rowY, Palette.TEXT);
            } else {
                gfx.text(font, Component.literal("·"), x + 2, rowY, Palette.FAINT);
                gfx.text(font, stepLabel(STEPS[i]), x + 14, rowY, Palette.FAINT);
            }
        }
    }

    private static Component stepLabel(MicrosoftAuth.Step step) {
        return Component.translatable("keychain.signin.step." + step.name().toLowerCase(Locale.ROOT));
    }

    private void drawSpinner(Gfx gfx, int center, int y) {
        int segments = 8;
        long tick = System.currentTimeMillis() / 120;
        for (int i = 0; i < segments; i++) {
            int color = (int) (((tick - i) % segments + segments) % segments) == 0 ? Palette.TEXT : Palette.FAINT;
            gfx.fill(center - segments * 3 + i * 6, y, center - segments * 3 + i * 6 + 4, y + 4, color);
        }
    }
}
