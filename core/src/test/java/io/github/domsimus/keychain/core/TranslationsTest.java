package io.github.domsimus.keychain.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every language file must have exactly the English keys, with the same number of %s arguments. */
class TranslationsTest {
    private static final Path LANG = Path.of("..", "common", "src", "main", "resources", "assets", "keychain", "lang");
    private static final Pattern ARGUMENT = Pattern.compile("%(\\d+\\$)?s");

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static int arguments(String text) {
        Matcher matcher = ARGUMENT.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    @Test
    void translationsMatchEnglish() throws IOException {
        JsonObject english = read(LANG.resolve("en_us.json"));
        List<Path> others;
        try (Stream<Path> files = Files.list(LANG)) {
            others = files.filter(file -> !file.getFileName().toString().equals("en_us.json")).toList();
        }
        assertFalse(others.isEmpty(), "expected at least one translation");
        for (Path file : others) {
            JsonObject translated = read(file);
            assertEquals(english.keySet(), translated.keySet(), file.getFileName() + " keys");
            for (String key : english.keySet()) {
                assertEquals(arguments(english.get(key).getAsString()), arguments(translated.get(key).getAsString()),
                    file.getFileName() + " arguments of " + key);
            }
        }
    }
}
