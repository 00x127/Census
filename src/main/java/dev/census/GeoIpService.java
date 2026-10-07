package dev.census;

import com.maxmind.db.Reader;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// offline geoip + asn lookups from the maxmind format db (db-ip lite). cached
// per ip and mmap reads, so a lookup is cheap enough to just do on the ping.
public final class GeoIpService implements Closeable {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final Reader cityReader;
    private final Reader asnReader;
    private final int cacheLimit;
    private final ConcurrentHashMap<String, GeoInfo> cache = new ConcurrentHashMap<>();
    private final Map<String, List<ZonePoint>> zonesByCountry = new HashMap<>();
    private final List<ZonePoint> allZones = new ArrayList<>();
    private volatile GeoInfo serverLocation;

    public GeoIpService(File cityDatabase, File asnDatabase, int cacheLimit, InputStream zoneTable)
            throws IOException {
        this.cacheLimit = Math.max(1000, cacheLimit);
        this.cityReader = new Reader(cityDatabase);
        this.asnReader = new Reader(asnDatabase);
        if (zoneTable != null) {
            loadZones(zoneTable);
        }
    }

    public void setServerLocation(GeoInfo serverLocation) {
        this.serverLocation = serverLocation;
    }

    public GeoInfo getServerLocation() {
        return serverLocation;
    }

    public GeoInfo lookup(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        GeoInfo cached = cache.get(ip);
        if (cached != null) {
            return cached;
        }
        GeoInfo info = resolve(ip);
        if (info != null) {
            if (cache.size() >= cacheLimit) {
                cache.clear();
            }
            cache.put(ip, info);
        }
        return info;
    }

    @SuppressWarnings("unchecked")
    private GeoInfo resolve(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                return null;
            }

            Map<String, Object> city = cityReader.get(address, Map.class);
            Map<String, Object> asn = asnReader.get(address, Map.class);

            String country = null;
            String countryCode = null;
            String cityName = null;
            String region = null;
            Double latitude = null;
            Double longitude = null;

            if (city != null) {
                Map<String, Object> countryObj = asMap(city.get("country"));
                if (countryObj != null) {
                    countryCode = string(countryObj.get("iso_code"));
                    country = english(countryObj);
                }
                cityName = english(asMap(city.get("city")));
                if (city.get("subdivisions") instanceof List<?> subdivisions && !subdivisions.isEmpty()) {
                    region = english(asMap(subdivisions.get(0)));
                }
                Map<String, Object> location = asMap(city.get("location"));
                if (location != null) {
                    latitude = number(location.get("latitude"));
                    longitude = number(location.get("longitude"));
                }
            }

            String asnCode = null;
            String isp = null;
            if (asn != null) {
                if (asn.get("autonomous_system_number") instanceof Number number) {
                    asnCode = "AS" + number.longValue();
                }
                isp = string(asn.get("autonomous_system_organization"));
            }

            if (country == null && countryCode == null && cityName == null && region == null
                    && latitude == null && longitude == null && asnCode == null && isp == null) {
                return null;
            }
            return new GeoInfo(country, countryCode, cityName, region, latitude, longitude,
                    nearestZone(countryCode, latitude, longitude), asnCode, isp);
        } catch (Exception exception) {
            return null;
        }
    }

    private String nearestZone(String countryCode, Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }
        List<ZonePoint> candidates = countryCode != null ? zonesByCountry.get(countryCode) : null;
        if (candidates == null || candidates.isEmpty()) {
            candidates = allZones;
        }
        if (candidates.isEmpty()) {
            return null;
        }
        double lonScale = Math.cos(Math.toRadians(latitude));
        double best = Double.MAX_VALUE;
        String bestZone = null;
        for (ZonePoint point : candidates) {
            double dLat = latitude - point.latitude();
            double dLon = (longitude - point.longitude()) * lonScale;
            double distance = dLat * dLat + dLon * dLon;
            if (distance < best) {
                best = distance;
                bestZone = point.zone();
            }
        }
        return bestZone;
    }

    private void loadZones(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 3) {
                    continue;
                }
                // zone1970.tab coords are iso6709 with no separators. lat is +/-DDMM
                // or +/-DDMMSS, lon gets an extra degree digit, so the whole thing is
                // always 11 or 15 chars.
                String coordinates = parts[1];
                double latitude;
                double longitude;
                if (coordinates.length() == 11) {
                    latitude = parseCoordinate(coordinates.substring(0, 5), false);
                    longitude = parseCoordinate(coordinates.substring(5, 11), true);
                } else if (coordinates.length() == 15) {
                    latitude = parseCoordinate(coordinates.substring(0, 7), false);
                    longitude = parseCoordinate(coordinates.substring(7, 15), true);
                } else {
                    continue;
                }
                ZonePoint point = new ZonePoint(latitude, longitude, parts[2]);
                allZones.add(point);
                for (String code : parts[0].split(",")) {
                    zonesByCountry.computeIfAbsent(code.trim(), key -> new ArrayList<>()).add(point);
                }
            }
        }
    }

    private static double parseCoordinate(String value, boolean longitude) {
        int sign = value.charAt(0) == '-' ? -1 : 1;
        String digits = value.substring(1);
        int degreeDigits = longitude ? 3 : 2;
        int degrees = Integer.parseInt(digits.substring(0, degreeDigits));
        int minutes = Integer.parseInt(digits.substring(degreeDigits, degreeDigits + 2));
        int seconds = digits.length() >= degreeDigits + 4
                ? Integer.parseInt(digits.substring(degreeDigits + 2, degreeDigits + 4))
                : 0;
        return sign * (degrees + minutes / 60.0 + seconds / 3600.0);
    }

    public static String localTime(String timeZone) {
        try {
            return ZonedDateTime.now(ZoneId.of(timeZone)).format(TIME_FORMAT);
        } catch (Exception exception) {
            return null;
        }
    }

    public static int localHour(String timeZone) {
        try {
            return ZonedDateTime.now(ZoneId.of(timeZone)).getHour();
        } catch (Exception exception) {
            return -1;
        }
    }

    // yanks a readable city out of the zone id. more trustworthy than the db city,
    // which is usually just the isp's registered town anyway.
    public static String friendlyCity(String timeZone) {
        if (timeZone == null) {
            return null;
        }
        String[] parts = timeZone.split("/");
        if (parts.length < 2 || parts[0].equalsIgnoreCase("Etc")) {
            return null;
        }
        String city = parts[parts.length - 1].replace('_', ' ').strip();
        if (city.isEmpty() || city.regionMatches(true, 0, "GMT", 0, 3) || city.equalsIgnoreCase("UTC")) {
            return null;
        }
        return city;
    }

    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadiusKm * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : null;
    }

    private static String english(Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        Map<String, Object> names = asMap(node.get("names"));
        return names == null ? null : string(names.get("en"));
    }

    private static String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    @Override
    public void close() throws IOException {
        cityReader.close();
        asnReader.close();
    }

    private record ZonePoint(double latitude, double longitude, String zone) {
    }
}
