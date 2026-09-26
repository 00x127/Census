package dev.census;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// loads messages.yml and works out which line to show for a ping.
// lines that reference a placeholder we cant fill are skipped, so a raw
// %thing% never ends up on the client.
public final class Ping {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Pattern TOKEN = Pattern.compile("%([a-z_]+)%");

    private final Path file;
    private final AtomicInteger cursor = new AtomicInteger();
    private volatile Lines lines = Lines.EMPTY;
    private volatile long stamp = Long.MIN_VALUE;
    private volatile long lastCheck = 0L;

    public Ping(Path file) {
        this.file = file;
    }

    public int count() {
        return lines.always.size();
    }

    public String line(GeoInfo info, GeoInfo server, String mode) {
        reload(false);

        List<String> pool = new ArrayList<>(lines.always);
        if (info != null && info.timeZone() != null) {
            pool.addAll(bucket(GeoIpService.localHour(info.timeZone())));
        }
        if (pool.isEmpty()) {
            return null;
        }

        List<String> ready = fill(pool, values(info, server));
        return ready.isEmpty() ? null : pick(ready, mode);
    }

    public void reload(boolean force) {
        try {
            if (file == null || Files.notExists(file)) {
                return;
            }
            if (!force) {
                long now = System.currentTimeMillis();
                if (now - lastCheck < 1000L) {
                    return;
                }
                lastCheck = now;
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (!force && modified == stamp) {
                return;
            }

            Map<String, List<String>> sections = read(Files.readAllLines(file, StandardCharsets.UTF_8));
            List<String> always = sections.getOrDefault("messages", List.of());
            if (always.isEmpty()) {
                return;
            }
            lines = new Lines(
                    colour(always),
                    colour(sections.getOrDefault("morning", List.of())),
                    colour(sections.getOrDefault("afternoon", List.of())),
                    colour(sections.getOrDefault("evening", List.of())),
                    colour(sections.getOrDefault("night", List.of())));
            stamp = modified;
        } catch (Exception ignored) {
            // keep whatever we had
        }
    }

    private List<String> bucket(int hour) {
        if (hour >= 5 && hour < 12) {
            return lines.morning;
        }
        if (hour >= 12 && hour < 18) {
            return lines.afternoon;
        }
        if (hour >= 18 && hour < 23) {
            return lines.evening;
        }
        return lines.night;
    }

    private String pick(List<String> options, String mode) {
        return switch (mode) {
            case "random" -> options.get(ThreadLocalRandom.current().nextInt(options.size()));
            case "static" -> options.get(0);
            default -> options.get(Math.floorMod(cursor.getAndIncrement(), options.size()));
        };
    }

    private static Map<String, String> values(GeoInfo info, GeoInfo server) {
        Map<String, String> values = new HashMap<>();
        if (info != null) {
            put(values, "country", info.country());
            put(values, "country_code", info.countryCode());
            put(values, "city", GeoIpService.friendlyCity(info.timeZone()));
            put(values, "db_city", info.city());
            put(values, "region", info.region());
            put(values, "timezone", info.timeZone());
            put(values, "asn", info.asn());
            put(values, "isp", info.isp());
            if (info.timeZone() != null) {
                put(values, "local_time", GeoIpService.localTime(info.timeZone()));
            }
            if (info.latitude() != null && info.longitude() != null
                    && server != null && server.latitude() != null && server.longitude() != null) {
                long km = Math.round(GeoIpService.distanceKm(info.latitude(), info.longitude(),
                        server.latitude(), server.longitude()));
                put(values, "distance", Long.toString(km));
            }
        }
        if (server != null) {
            put(values, "server_city", server.city());
            put(values, "server_country", server.country());
        }
        return values;
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static List<String> fill(List<String> templates, Map<String, String> values) {
        List<String> out = new ArrayList<>(templates.size());
        for (String template : templates) {
            String filled = fillOne(template, values);
            if (filled != null) {
                out.add(filled);
            }
        }
        return out;
    }

    private static String fillOne(String template, Map<String, String> values) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder builder = new StringBuilder(template.length() + 16);
        int end = 0;
        while (matcher.find()) {
            builder.append(template, end, matcher.start());
            String value = values.get(matcher.group(1));
            if (value == null || value.isBlank()) {
                return null;
            }
            builder.append(value);
            end = matcher.end();
        }
        builder.append(template, end, template.length());
        return builder.toString();
    }

    private static List<String> colour(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String message : raw) {
            try {
                out.add(LEGACY.serialize(MINI.deserialize(message)));
            } catch (Throwable throwable) {
                out.add(message);
            }
        }
        return out;
    }

    private static Map<String, List<String>> read(List<String> lines) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ")) {
                if (current != null) {
                    String value = unquote(line.substring(2).strip());
                    if (!value.isBlank()) {
                        current.add(value.strip());
                    }
                }
            } else if (line.endsWith(":")) {
                String name = line.substring(0, line.length() - 1).strip().toLowerCase(Locale.ROOT);
                current = sections.computeIfAbsent(name, key -> new ArrayList<>());
            }
        }
        return sections;
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

    private record Lines(List<String> always, List<String> morning, List<String> afternoon,
                         List<String> evening, List<String> night) {
        static final Lines EMPTY = new Lines(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
