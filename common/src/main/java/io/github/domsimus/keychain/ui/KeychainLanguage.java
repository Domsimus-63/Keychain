package io.github.domsimus.keychain.ui;

import io.github.domsimus.keychain.Keychain;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * Lets Keychain's own screens use a different language than the rest of the game.
 *
 * <p>By default nothing happens and Keychain simply follows the game's language. When a language
 * is picked in Keychain's settings, the game's language table is wrapped: Keychain's own keys come
 * from the picked language, every other key still comes from the game.
 */
public final class KeychainLanguage {
    /** A language Keychain is translated into, with its name in that language and in English. */
    public record Option(String code, String nativeName, String englishName) {}

    public static final List<Option> OPTIONS = List.of(
        new Option("en_us", "English", "English"),
        new Option("de_de", "Deutsch", "German"),
        new Option("es_es", "Español", "Spanish"),
        new Option("fr_fr", "Français", "French"),
        new Option("it_it", "Italiano", "Italian"),
        new Option("pl_pl", "Polski", "Polish"),
        new Option("pt_br", "Português (Brasil)", "Portuguese (Brazil)"),
        new Option("ru_ru", "Русский", "Russian"),
        new Option("tr_tr", "Türkçe", "Turkish"),
        new Option("zh_cn", "简体中文", "Chinese (Simplified)"),
        new Option("ja_jp", "日本語", "Japanese"));

    private static volatile String selected = "";
    private static volatile Map<String, String> table = Map.of();

    private KeychainLanguage() {}

    public static Optional<Option> option(String code) {
        return OPTIONS.stream().filter(option -> option.code().equals(code)).findFirst();
    }

    /** The picked language's code, or an empty string while Keychain follows the game. */
    public static String selected() {
        return selected;
    }

    /** Picks the language for Keychain's screens; an empty or unknown code follows the game again. */
    public static void select(String code) {
        String next = code == null || option(code).isEmpty() ? "" : code;
        Map<String, String> loaded = Map.of();
        if (!next.isEmpty()) {
            // English first, so a key missing from a translation still shows readable text.
            Map<String, String> merged = new HashMap<>(load("en_us"));
            merged.putAll(load(next));
            loaded = Map.copyOf(merged);
        }
        table = loaded;
        selected = next;
        apply();
    }

    /**
     * Puts the overlay on top of whatever language table the game currently uses, or takes it off.
     * Cheap when nothing changed, so it can run every tick: the game replaces its table on every
     * resource reload, and the overlay has to be put back on the new one.
     */
    public static void apply() {
        Language current = Language.getInstance();
        Language base = current instanceof Overlay overlay ? overlay.base : current;
        if (selected.isEmpty()) {
            if (current != base) Language.inject(base);
            return;
        }
        if (current instanceof Overlay overlay && overlay.table == table) return;
        Language.inject(new Overlay(base, table));
    }

    private static Map<String, String> load(String code) {
        Map<String, String> values = new HashMap<>();
        try (InputStream in = KeychainLanguage.class.getResourceAsStream("/assets/keychain/lang/" + code + ".json")) {
            if (in != null) Language.loadFromJson(in, values::put);
        } catch (IOException | RuntimeException failure) {
            Keychain.LOGGER.warn("Keychain could not load its {} translation", code, failure);
        }
        return values;
    }

    /** The game's language table, with Keychain's own keys taken from the picked language. */
    private static final class Overlay extends Language {
        private final Language base;
        private final Map<String, String> table;

        Overlay(Language base, Map<String, String> table) {
            this.base = base;
            this.table = table;
        }

        @Override
        public String getOrDefault(String key, String fallback) {
            String value = table.get(key);
            return value != null ? value : base.getOrDefault(key, fallback);
        }

        @Override
        public boolean has(String key) {
            return table.containsKey(key) || base.has(key);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return base.isDefaultRightToLeft();
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText text) {
            return base.getVisualOrder(text);
        }
    }
}
