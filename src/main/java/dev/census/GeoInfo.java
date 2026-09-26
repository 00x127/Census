package dev.census;

// a geoip lookup result. fields are null when we dont have em
public record GeoInfo(
        String country,
        String countryCode,
        String city,
        String region,
        Double latitude,
        Double longitude,
        String timeZone,
        String asn,
        String isp
) {
}
