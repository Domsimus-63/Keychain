package io.github.domsimus.keychain.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.domsimus.keychain.Keychain;
import io.github.domsimus.keychain.compat.Compat;
import io.github.domsimus.keychain.compat.Gfx;
import io.github.domsimus.keychain.compat.KeychainList;
import io.github.domsimus.keychain.compat.KeychainScreen;
import io.github.domsimus.keychain.core.vault.AccountVault;
import io.github.domsimus.keychain.core.vault.BackupCodec;
import io.github.domsimus.keychain.core.vault.VaultData;
import io.github.domsimus.keychain.session.SessionManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Creates and restores password-protected backups of the saved accounts. Backups are files in the
 * per-user Keychain folder; to move accounts to another computer, the file is copied there by hand.
 */
public final class BackupScreen extends KeychainScreen {
    private static final int BUTTON_HEIGHT = 18;

    private enum Mode { CREATE, RESTORE }

    private final Screen parent;
    private final Keychain keychain = Keychain.get();
    private Mode mode = Mode.CREATE;
    private String passwordValue = "", repeatValue = "", restorePasswordValue = "";
    private Path selectedBackup;
    private Component status;
    private int statusColor;
    private boolean working;

    private PasswordBox password, repeat, restorePassword;
    private Button createTab, restoreTab, createButton, restoreButton;
    private BackupList list;
    private int columnX, columnWidth, passwordY, repeatY, statusY, listTop, listBottom;

    public BackupScreen(Screen parent) {
        super(Component.translatable("keychain.backup.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        columnWidth = Math.min(340, width - 24);
        columnX = (width - columnWidth) / 2;
        int half = (columnWidth - 4) / 2;
        createTab = addRenderableWidget(Button.builder(Component.translatable("keychain.backup.tab.create"), b -> switchMode(Mode.CREATE))
            .bounds(columnX, 26, half, BUTTON_HEIGHT).build());
        restoreTab = addRenderableWidget(Button.builder(Component.translatable("keychain.backup.tab.restore"), b -> switchMode(Mode.RESTORE))
            .bounds(columnX + half + 4, 26, columnWidth - half - 4, BUTTON_HEIGHT).build());

        password = repeat = restorePassword = null;
        createButton = restoreButton = null;
        list = null;
        if (mode == Mode.CREATE) initCreate();
        else initRestore();

        int footerY = height - 28;
        int each = Math.min(150, (columnWidth - 4) / 2);
        int footerX = (width - 2 * each - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("keychain.backup.open_folder"), b -> openFolder())
            .bounds(footerX, footerY, each, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(footerX + each + 4, footerY, each, 20).build());
        updateButtons();
    }

    private void initCreate() {
        passwordY = 86;
        password = addRenderableWidget(new PasswordBox(font, columnX, passwordY + 11, columnWidth, 20, Component.translatable("keychain.backup.password")));
        password.setValue(passwordValue);
        password.setResponder(value -> {
            passwordValue = value;
            clearStatus();
        });
        repeatY = passwordY + 36;
        repeat = addRenderableWidget(new PasswordBox(font, columnX, repeatY + 11, columnWidth, 20, Component.translatable("keychain.backup.repeat")));
        repeat.setValue(repeatValue);
        repeat.setResponder(value -> {
            repeatValue = value;
            clearStatus();
        });
        statusY = repeatY + 36;
        createButton = addRenderableWidget(Button.builder(Component.translatable("keychain.backup.create"), b -> createBackup())
            .bounds(columnX, statusY + 22, columnWidth, 20).build());
    }

    private void initRestore() {
        listTop = 66;
        int bottomArea = 26 + 11 + 20 + 32;
        listBottom = Math.max(listTop + 26, height - 30 - bottomArea);
        list = new BackupList(minecraft, columnWidth, listBottom - listTop, listTop);
        list.setX(columnX);
        addRenderableWidget(list);
        list.show(backups(), selectedBackup);

        passwordY = listBottom + 8;
        restorePassword = addRenderableWidget(new PasswordBox(font, columnX, passwordY + 11, columnWidth - 94, 20, Component.translatable("keychain.backup.password")));
        restorePassword.setValue(restorePasswordValue);
        restorePassword.setResponder(value -> {
            restorePasswordValue = value;
            clearStatus();
        });
        restoreButton = addRenderableWidget(Button.builder(Component.translatable("keychain.backup.restore"), b -> restoreBackup())
            .bounds(columnX + columnWidth - 90, passwordY + 11, 90, 20).build());
        statusY = passwordY + 37;
    }

    @Override
    protected void setInitialFocus() {
        if (password != null) setInitialFocus(password);
        else if (restorePassword != null) setInitialFocus(restorePassword);
        else super.setInitialFocus();
    }

    private void switchMode(Mode next) {
        if (working || mode == next) return;
        mode = next;
        status = null;
        rebuildWidgets();
    }

    private void clearStatus() {
        if (!working) status = null;
        updateButtons();
    }

    private void setStatus(Component text, int color) {
        status = text;
        statusColor = color;
    }

    private void updateButtons() {
        // Both tabs stay clickable-looking; the open one is marked with a line under it.
        if (createTab != null) createTab.active = !working;
        if (restoreTab != null) restoreTab.active = !working;
        boolean ready = keychain.vault().state() == AccountVault.State.READY;
        if (createButton != null) {
            createButton.active = !working && ready && !keychain.vault().accounts().isEmpty()
                && passwordValue.length() >= BackupCodec.MIN_PASSWORD_LENGTH && passwordValue.equals(repeatValue);
        }
        if (restoreButton != null) restoreButton.active = !working && ready && selectedBackup != null && !restorePasswordValue.isEmpty();
    }

    // ---- create -----------------------------------------------------------------------------

    /** What still keeps the backup from being created, or {@code null} when it is ready to go. */
    private Component createHint() {
        if (keychain.vault().accounts().isEmpty()) return Component.translatable("keychain.backup.nothing");
        if (passwordValue.isEmpty()) return Component.translatable("keychain.backup.password.rules", BackupCodec.MIN_PASSWORD_LENGTH);
        if (passwordValue.length() < BackupCodec.MIN_PASSWORD_LENGTH) return Component.translatable("keychain.backup.password.short", BackupCodec.MIN_PASSWORD_LENGTH);
        if (!passwordValue.equals(repeatValue)) return Component.translatable(repeatValue.isEmpty() ? "keychain.backup.repeat.hint" : "keychain.backup.repeat.mismatch");
        return null;
    }

    private void createBackup() {
        updateButtons();
        if (createButton == null || !createButton.active) return;
        char[] secret = password.password();
        working = true;
        setStatus(Component.translatable("keychain.backup.creating"), Palette.MUTED);
        updateButtons();
        CompletableFuture.supplyAsync(() -> {
            try {
                return keychain.exportBackup(secret);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            } finally {
                Arrays.fill(secret, '\0');
            }
        }).whenComplete((file, failure) -> Minecraft.getInstance().execute(() -> {
            working = false;
            if (failure != null) {
                Keychain.LOGGER.error("Keychain could not create a backup", failure);
                setStatus(Component.translatable("keychain.backup.create.failed"), Palette.BAD);
            } else {
                passwordValue = repeatValue = "";
                if (password != null) password.setValue("");
                if (repeat != null) repeat.setValue("");
                setStatus(Component.translatable("keychain.backup.created", file.getFileName().toString()), Palette.GOOD);
                SessionManager.toast(Component.translatable("keychain.backup.created.toast"), null);
            }
            updateButtons();
        }));
    }

    // ---- restore ----------------------------------------------------------------------------

    private List<Path> backups() {
        Path folder = keychain.paths().backups();
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(BackupCodec.EXTENSION) && Files.isRegularFile(file))
                .sorted(Comparator.comparing(BackupScreen::modified).reversed()).toList();
        } catch (IOException failure) {
            Keychain.LOGGER.warn("Keychain could not list {}", folder, failure);
            return List.of();
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException failure) {
            return 0;
        }
    }

    private void restoreBackup() {
        updateButtons();
        if (restoreButton == null || !restoreButton.active) return;
        Path file = selectedBackup;
        char[] secret = restorePassword.password();
        working = true;
        setStatus(Component.translatable("keychain.backup.restoring"), Palette.MUTED);
        updateButtons();
        CompletableFuture.supplyAsync(() -> {
            try {
                return keychain.importBackup(file, secret);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            } finally {
                Arrays.fill(secret, '\0');
            }
        }).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            working = false;
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                if (cause instanceof BackupCodec.BackupException backup) {
                    setStatus(Component.translatable("keychain.backup.problem." + backup.problem().name().toLowerCase(java.util.Locale.ROOT)), Palette.BAD);
                } else {
                    Keychain.LOGGER.error("Keychain could not restore {}", file, cause);
                    setStatus(Component.translatable("keychain.backup.restore.failed"), Palette.BAD);
                }
            } else {
                restorePasswordValue = "";
                if (restorePassword != null) restorePassword.setValue("");
                setStatus(restoredMessage(result), Palette.GOOD);
            }
            updateButtons();
        }));
    }

    private static Component restoredMessage(VaultData.ImportResult result) {
        if (result.added() == 0 && result.renewed() == 0) return Component.translatable("keychain.backup.restored.nothing");
        return Component.translatable("keychain.backup.restored", result.added(), result.renewed(), result.unchanged());
    }

    void onBackupSelected(Path file) {
        // Rebuilding the list (a resize, switching tabs) selects the same file again: keep the message then.
        if (java.util.Objects.equals(file, selectedBackup)) return;
        selectedBackup = file;
        clearStatus();
    }

    private void openFolder() {
        Path folder = keychain.paths().backups();
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            Keychain.LOGGER.warn("Keychain could not create {}", folder, failure);
        }
        Compat.openUri(folder.toUri());
    }

    // ---- lifecycle --------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        updateButtons();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation()) {
            if (mode == Mode.CREATE && (getFocused() == password || getFocused() == repeat)) {
                createBackup();
                return true;
            }
            if (mode == Mode.RESTORE && (getFocused() == restorePassword || getFocused() == list)) {
                restoreBackup();
                return true;
            }
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
        Button open = mode == Mode.CREATE ? createTab : restoreTab;
        if (open != null) gfx.fill(open.getX() + 2, open.getY() + open.getHeight() + 1, open.getX() + open.getWidth() - 2, open.getY() + open.getHeight() + 3, Palette.TEXT);
        if (mode == Mode.CREATE) {
            gfx.wrapped(font, Component.translatable("keychain.backup.create.info"), width / 2, 52, columnWidth, Palette.MUTED, 3);
            gfx.text(font, Component.translatable("keychain.backup.password"), columnX, passwordY, Palette.TEXT);
            gfx.text(font, Component.translatable("keychain.backup.repeat"), columnX, repeatY, Palette.TEXT);
            Component hint = status != null ? status : createHint();
            int color = status != null ? statusColor : Palette.MUTED;
            if (hint == null) {
                hint = Component.translatable("keychain.backup.create.ready");
                color = Palette.GOOD;
            }
            gfx.wrapped(font, hint, width / 2, statusY, columnWidth, color, 2);
        } else {
            gfx.text(font, Component.translatable("keychain.backup.list"), columnX, listTop - 12, Palette.TEXT);
            if (list != null && list.children().isEmpty()) {
                gfx.wrapped(font, Component.translatable("keychain.backup.list.empty"), width / 2, listTop + (listBottom - listTop) / 2 - 10,
                    columnWidth - 16, Palette.MUTED, 3);
            }
            gfx.text(font, Component.translatable("keychain.backup.password.restore"), columnX, passwordY, Palette.TEXT);
            Component hint = status != null ? status : Component.translatable("keychain.backup.restore.info");
            gfx.wrapped(font, hint, width / 2, statusY, columnWidth, status != null ? statusColor : Palette.FAINT, 3);
        }
    }

    // ---- the list of backups -----------------------------------------------------------------

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault());

    private final class BackupList extends KeychainList<BackupList.Row> {
        BackupList(Minecraft minecraft, int width, int height, int top) {
            super(minecraft, width, height, top, 28);
        }

        void show(List<Path> files, Path select) {
            List<Row> rows = new ArrayList<>();
            for (Path file : files) rows.add(new Row(file));
            replaceEntries(rows);
            Row match = rows.stream().filter(row -> row.file.equals(select)).findFirst().orElse(rows.isEmpty() ? null : rows.getFirst());
            setSelected(match);
        }

        @Override
        public void setSelected(Row row) {
            super.setSelected(row);
            onBackupSelected(row == null ? null : row.file);
        }

        final class Row extends KeychainList.Row<Row> {
            final Path file;
            private final String date;

            Row(Path file) {
                this.file = file;
                this.date = DATE.format(java.time.Instant.ofEpochMilli(modified(file)));
            }

            @Override
            public Component getNarration() {
                return Component.literal(date + ", " + file.getFileName());
            }

            @Override
            protected void draw(Gfx gfx, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX(), y = getContentY(), w = getContentWidth(), h = getContentHeight();
                if (getSelected() == this) {
                    gfx.fill(x, y, x + w, y + h, Palette.ROW_SELECTED);
                    gfx.outline(x, y, w, h, Palette.ROW_SELECTED_EDGE);
                } else if (hovered) {
                    gfx.fill(x, y, x + w, y + h, Palette.ROW_HOVER);
                }
                gfx.text(font, font.plainSubstrByWidth(date, w - 12), x + 6, y + 3, Palette.TEXT);
                gfx.text(font, font.plainSubstrByWidth(file.getFileName().toString(), w - 12), x + 6, y + 13, Palette.FAINT);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
                setSelected(this);
                return true;
            }
        }
    }
}
