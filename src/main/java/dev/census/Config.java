package dev.census;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// the flat key: value config.yml
public final class Config {

    public boolean enabled = true;
    public String mode = "sequential";
    public int fakeOnline = -1;
    public int fakeMax = -1;
    public boolean hidePlayers = false;
    public String serverHost = "";
    public boolean geoipEnabled = false;

    private Config() {
    }

    public static Config read(Path file) {
        Map<String, String> values = parse(file);
        Config config = new Config();
        config.enabled = bool(values, "enabled", true);
        config.mode = values.getOrDefault("mode", "sequential").strip().toLowerCase(Locale.ROOT);
        config.fakeOnline = number(values, "fake-online", -1);
        config.fakeMax = number(values, "fake-max", -1);
        config.hidePlayers = bool(values, "hide-players", false);
        config.serverHost = values.getOrDefault("server-host", "");
        config.geoipEnabled = bool(values, "geoip-enabled", false);
        return config;
    }

    private static Map<String, String> parse(Path file) {
        Map<String, String> map = new HashMap<>();
        try {
            if (Files.notExists(file)) {
                return map;
            }
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String value = unquote(line.substring(colon + 1).strip());
                if (!value.isEmpty()) {
                    map.put(line.substring(0, colon).strip(), value);
                }
            }
        } catch (Exception ignored) {
            // defaults are fine
        }
        return map;
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int number(Map<String, String> values, String key, int fallback) {
        try {
            String value = values.get(key);
            return value == null ? fallback : Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
