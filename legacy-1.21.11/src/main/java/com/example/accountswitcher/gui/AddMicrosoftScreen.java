/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.client.gui.components.Button
 *  net.minecraft.client.gui.components.events.GuiEventListener
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.network.chat.Component
 *  net.minecraft.util.Util
 */
package com.example.accountswitcher.gui;

import com.example.accountswitcher.AccountSwitcherMod;
import com.example.accountswitcher.auth.Account;
import com.example.accountswitcher.auth.BrowserLauncher;
import com.example.accountswitcher.auth.MicrosoftAuth;
import com.example.accountswitcher.gui.AccountSwitcherScreen;
import com.example.accountswitcher.gui.AzureSetupScreen;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public final class AddMicrosoftScreen
extends Screen {
    private final AccountSwitcherScreen parent;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<State> state = new AtomicReference<State>(State.RUNNING);
    private volatile String authUrl;
    private volatile String progress = "Preparing\u2026";
    private volatile String successName;
    private volatile String error;
    private final List<String> log = new ArrayList<String>();
    private Thread worker;
    private Button reopenButton;
    private Button copyLinkButton;
    private Button retryButton;
    private Button reconfigureButton;
    private Button copyErrorButton;
    private int formWidth;
    private int formX;
    private int panelY;

    public AddMicrosoftScreen(AccountSwitcherScreen parent) {
        super((Component)Component.literal((String)"Sign in with Microsoft"));
        this.parent = parent;
    }

    protected void init() {
        boolean failed;
        this.formWidth = Math.min(350, this.width - 24);
        this.formX = (this.width - this.formWidth) / 2;
        this.panelY = Math.max(52, (this.height - 170) / 2);
        int actionWidth = (this.formWidth - 8) / 3;
        this.reopenButton = (Button)this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Open Browser"), b -> {
            if (this.authUrl != null) {
                this.openUrl(this.authUrl);
            }
        }).bounds(this.formX, this.height - 34, actionWidth, 20).build());
        this.reopenButton.active = this.authUrl != null && this.state.get() == State.RUNNING;
        this.copyLinkButton = (Button)this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Copy Link"), b -> {
            if (this.authUrl != null && this.minecraft != null) {
                this.minecraft.keyboardHandler.setClipboard(this.authUrl);
                this.progress = "Link copied. Paste it into your browser.";
            }
        }).bounds(this.formX + actionWidth + 4, this.height - 34, actionWidth, 20).build());
        this.copyLinkButton.active = this.authUrl != null && this.state.get() == State.RUNNING;
        this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Cancel"), b -> {
            this.cancelled.set(true);
            this.minecraft.setScreen((Screen)this.parent);
        }).bounds(this.formX + 2 * (actionWidth + 4), this.height - 34, actionWidth, 20).build());
        this.retryButton = this.addRenderableWidget(Button.builder(Component.literal("Try again"), b -> this.minecraft.setScreen(new AddMicrosoftScreen(this.parent))).bounds(this.formX, this.height - 58, actionWidth, 20).build());
        this.copyErrorButton = (Button)this.addRenderableWidget(Button.builder((Component)Component.literal((String)"Copy error"), b -> {
            if (this.error != null && this.minecraft != null) {
                StringBuilder sb = new StringBuilder("Keychain \u2014 sign-in error\n\n");
                sb.append(this.error).append("\n\nLog:\n");
                List<String> list = this.log;
                synchronized (list) {
                    for (String s : this.log) {
                        sb.append("\u2022 ").append(s).append('\n');
                    }
                }
                this.minecraft.keyboardHandler.setClipboard(sb.toString());
            }
        }).bounds(this.formX + actionWidth + 4, this.height - 58, actionWidth, 20).build());
        this.reconfigureButton = this.addRenderableWidget(Button.builder(Component.literal("Azure ID..."), b -> this.minecraft.setScreen(new AzureSetupScreen(this.parent, true))).bounds(this.formX + 2 * (actionWidth + 4), this.height - 58, actionWidth, 20).build());
        this.retryButton.visible = failed = this.state.get() == State.FAILED;
        this.copyErrorButton.visible = failed;
        this.reconfigureButton.visible = failed;
        if (this.worker == null) {
            this.worker = new Thread(this::doFlow, "Keychain-MSLogin");
            this.worker.setDaemon(true);
            this.worker.start();
        }
    }

    private void doFlow() {
        Account account;
        try {
            account = MicrosoftAuth.loginWithBrowser(url -> {
                this.authUrl = url;
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        if (this.reopenButton != null && this.state.get() == State.RUNNING) {
                            this.reopenButton.active = true;
                        }
                        if (this.copyLinkButton != null && this.state.get() == State.RUNNING) {
                            this.copyLinkButton.active = true;
                        }
                    });
                }
            }, msg -> {
                this.progress = msg;
                List<String> list = this.log;
                synchronized (list) {
                    this.log.add((String)msg);
                }
            }, this.cancelled::get);
        }
        catch (Throwable var4) {
            if (this.cancelled.get()) {
                return;
            }
            AccountSwitcherMod.LOGGER.error("Microsoft login failed", var4);
            if (this.state.compareAndSet(State.RUNNING, State.FAILED)) {
                this.progress = this.error = var4.getMessage() != null ? var4.getMessage() : "Login failed.";
                if (this.minecraft != null) {
                    this.minecraft.execute(this::showFailureButtons);
                }
            }
            return;
        }
        if (this.cancelled.get()) return;
        if (this.minecraft != null && !this.cancelled.get()) {
            this.minecraft.execute(() -> {
                if (this.cancelled.get()) return;
                if (!AccountSwitcherMod.storage().add(account)) {
                    this.progress = this.error = "Sign-in succeeded, but Keychain could not save the account.";
                    this.state.set(State.FAILED);
                    this.showFailureButtons();
                    return;
                }
                this.state.compareAndSet(State.RUNNING, State.SUCCESS);
                this.successName = account.username;
                this.progress = "Signed in as " + account.username + ".";
                this.minecraft.setScreen((Screen)this.parent);
                this.parent.switchTo(account);
            });
        }
    }

    private void showFailureButtons() {
        if (this.retryButton != null) {
            this.retryButton.visible = true;
        }
        if (this.copyErrorButton != null) {
            this.copyErrorButton.visible = true;
        }
        if (this.reconfigureButton != null) {
            this.reconfigureButton.visible = true;
        }
        if (this.reopenButton != null) {
            this.reopenButton.active = false;
        }
        if (this.copyLinkButton != null) {
            this.copyLinkButton.active = false;
        }
    }

    private void openUrl(String url) {
        try {
            BrowserLauncher.open(URI.create(url));
            this.progress = "Browser requested. Complete sign-in there.";
        }
        catch (Exception error) {
            AccountSwitcherMod.LOGGER.warn("Could not open sign-in link", error);
            this.progress = "Could not open browser. Use Copy Link.";
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        KeychainUi.line(g, font, "Sign in with Microsoft", width / 2, 20, width - 24, KeychainUi.TEXT);
        KeychainUi.line(g, font, "Finish signing in securely in your browser", width / 2, 35, width - 24, KeychainUi.MUTED);
        State current = state.get();
        int color = current == State.FAILED ? KeychainUi.RED : current == State.SUCCESS ? KeychainUi.GREEN : KeychainUi.GOLD;
        String title = current == State.FAILED ? "Sign-in failed" : current == State.SUCCESS ? "Signed in" : authUrl == null ? "Preparing sign-in" : "Waiting for Microsoft";
        KeychainUi.line(g, font, title, width / 2, panelY + 12, formWidth - 24, color);
        String message = current == State.FAILED ? (error == null ? "Login failed." : error) : progress;
        int lineY = panelY + 32;
        for (int line = 0; !message.isEmpty() && line < 4; ++line) {
            int count = maxCharsForWidth(message, formWidth - 24);
            String chunk = message.substring(0, Math.min(count, message.length()));
            KeychainUi.line(g, font, chunk, width / 2, lineY + line * 12, formWidth - 24, current == State.FAILED ? KeychainUi.RED : KeychainUi.TEXT);
            message = message.substring(chunk.length());
        }
        if (current == State.RUNNING) {
            int barX = formX + 12, barWidth = formWidth - 24;
            int segment = Math.max(12, barWidth / 5);
            int offset = (int) (System.currentTimeMillis() / 12 % Math.max(1, barWidth - segment));
            g.fill(barX, panelY + 86, barX + barWidth, panelY + 90, 0xFF151515);
            g.fill(barX + offset, panelY + 86, barX + offset + segment, panelY + 90, KeychainUi.GREEN);
            KeychainUi.line(g, font, authUrl == null ? "1. Create link  >  2. Sign in  >  3. Play" : "Use Open Browser or Copy Link below", width / 2, panelY + 103, formWidth - 24, KeychainUi.MUTED);
        } else {
            KeychainUi.line(g, font, "Try again or copy the error for troubleshooting", width / 2, panelY + 103, formWidth - 24, KeychainUi.MUTED);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        super.renderBackground(g, mx, my, dt);
        KeychainUi.panel(g, formX, panelY, formWidth, 126);
    }
    private int maxCharsForWidth(String s, int maxPx) {
        int i;
        int width = 0;
        for (i = 0; i < s.length(); ++i) {
            int cw = this.font.width(String.valueOf(s.charAt(i)));
            if (width + cw > maxPx) {
                return Math.max(1, i);
            }
            width += cw;
        }
        return i;
    }

    public void onClose() {
        this.cancelled.set(true);
        this.minecraft.setScreen((Screen)this.parent);
    }

    @Override
    public void removed() {
        this.cancelled.set(true);
        if (this.worker != null) this.worker.interrupt();
        super.removed();
    }

    private static enum State {
        RUNNING,
        SUCCESS,
        FAILED;

    }
}
