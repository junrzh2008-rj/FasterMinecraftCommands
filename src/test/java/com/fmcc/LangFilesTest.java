package com.fmcc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the bilingual resources: both language files must be valid JSON, expose exactly the same
 * key set and use the same number of {@code %s} placeholders per key.
 */
class LangFilesTest {
    private static JsonObject lang(String code) throws Exception {
        try (InputStream in = LangFilesTest.class.getResourceAsStream("/assets/fmcc/lang/" + code + ".json")) {
            assertNotNull(in, "missing language file " + code);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    @DisplayName("en_us and zh_cn are valid JSON with identical key sets")
    void keyParity() throws Exception {
        JsonObject en = lang("en_us");
        JsonObject zh = lang("zh_cn");
        assertTrue(en.size() > 20, "unexpectedly small en_us file");
        assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(zh.keySet()));
    }

    @Test
    @DisplayName("placeholders match between languages")
    void placeholderParity() throws Exception {
        JsonObject en = lang("en_us");
        JsonObject zh = lang("zh_cn");
        Set<String> keys = new TreeSet<>(en.keySet());
        for (String key : keys) {
            assertEquals(countPlaceholders(en.get(key).getAsString()), countPlaceholders(zh.get(key).getAsString()),
                    "placeholder mismatch for " + key);
        }
    }

    @Test
    @DisplayName("required menu/config/dialog keys exist in both languages")
    void requiredKeys() throws Exception {
        Set<String> required = Set.of(
                "key.fmcc.open", "category.fmcc",
                "fmcc.menu.configure", "fmcc.menu.delete", "fmcc.menu.add", "fmcc.menu.count",
                "fmcc.config.back", "fmcc.config.add", "fmcc.config.placeholder",
                "fmcc.add.title", "fmcc.add.confirm", "fmcc.add.cancel", "fmcc.add.invalid",
                "fmcc.status.not_in_world", "fmcc.status.sent", "fmcc.status.empty", "fmcc.status.deleted");
        for (String code : new String[] {"en_us", "zh_cn"}) {
            JsonObject json = lang(code);
            for (String key : required) {
                assertTrue(json.has(key), code + " is missing " + key);
            }
        }
    }

    private static int countPlaceholders(String text) {
        int count = 0;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '%' && text.charAt(i + 1) == 's') {
                count++;
            }
        }
        return count;
    }
}
