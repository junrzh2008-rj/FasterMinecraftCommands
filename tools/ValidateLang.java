import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/** Validates FMCC language files: valid JSON, same key set, same format-arg counts. */
public final class ValidateLang {
    public static void main(String[] args) throws Exception {
        Path en = Path.of(args[0]);
        Path zh = Path.of(args[1]);
        JsonObject enJson = JsonParser.parseString(Files.readString(en, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject zhJson = JsonParser.parseString(Files.readString(zh, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> enKeys = new TreeSet<>();
        enJson.keySet().forEach(enKeys::add);
        Set<String> zhKeys = new TreeSet<>(zhJson.keySet());
        System.out.println("en keys=" + enKeys.size() + ", zh keys=" + zhKeys.size());
        boolean ok = true;
        for (String key : enKeys) {
            if (!zhKeys.contains(key)) {
                System.out.println("MISSING in zh: " + key);
                ok = false;
            } else if (!sameArgs(enJson.get(key).getAsString(), zhJson.get(key).getAsString())) {
                System.out.println("ARG MISMATCH: " + key + " en='" + enJson.get(key).getAsString()
                        + "' zh='" + zhJson.get(key).getAsString() + "'");
                ok = false;
            }
        }
        for (String key : zhKeys) {
            if (!enKeys.contains(key)) {
                System.out.println("MISSING in en: " + key);
                ok = false;
            }
        }
        System.out.println(ok ? "LANG OK" : "LANG PROBLEMS");
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean sameArgs(String a, String b) {
        return countArgs(a) == countArgs(b);
    }

    private static int countArgs(String text) {
        int count = 0;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '%' && text.charAt(i + 1) == 's') {
                count++;
            }
        }
        return count;
    }
}
