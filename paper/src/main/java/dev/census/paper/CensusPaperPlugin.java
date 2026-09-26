package dev.census.paper;

import dev.census.Config;
import dev.census.GeoInfo;
import dev.census.GeoIpService;
import dev.census.Ping;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CensusPaperPlugin extends JavaPlugin {

    private Config config;
    private Ping ping;
    private GeoIpService geo;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) {
            saveResource("messages.yml", false);
        }

        config = Config.read(new File(getDataFolder(), "config.yml").toPath());
        ping = new Ping(new File(getDataFolder(), "messages.yml").toPath());
        ping.reload(true);

        if (config.geoipEnabled) {
            loadGeo();
        }
        if (!config.serverHost.isBlank() && geo != null) {
            getServer().getScheduler().runTaskAsynchronously(this, this::resolveServerLocation);
        }

        getServer().getPluginManager().registerEvents(new PingListener(this), this);
        getLogger().info("census on, mode " + config.mode + ", " + ping.count() + " hover messages");
    }

    @Override
    public void onDisable() {
        if (geo != null) {
            try {
                geo.close();
            } catch (Exception ignored) {
                // nothing to do on shutdown
            }
        }
    }

    private void loadGeo() {
        try {
            Path dir = getDataFolder().toPath();
            Path city = dir.resolve("geoip-city.mmdb");
            Path asn = dir.resolve("geoip-asn.mmdb");
            if (!Files.isRegularFile(city) || !Files.isRegularFile(asn)) {
                getLogger().warning("geoip databases are missing, geo/isp messages are off");
                return;
            }
            try (InputStream zoneTable = getResource("zone1970.tab")) {
                geo = new GeoIpService(city.toFile(), asn.toFile(), 50000, zoneTable);
            }
            getLogger().info("geoip databases loaded");
        } catch (Exception exception) {
            geo = null;
            getLogger().warning("couldnt load the geoip databases, geo/isp messages are off");
        }
    }

    private void resolveServerLocation() {
        try {
            GeoInfo location = geo.lookup(InetAddress.getByName(config.serverHost).getHostAddress());
            geo.setServerLocation(location);
            if (location != null && location.city() != null) {
                getLogger().info("server location resolved to " + location.city());
            }
        } catch (Exception exception) {
            getLogger().warning("couldnt resolve server-host '" + config.serverHost + "'");
        }
    }

    public String buildMessage(InetAddress address) {
        GeoInfo info = geo != null && address != null ? geo.lookup(address.getHostAddress()) : null;
        GeoInfo server = geo != null ? geo.getServerLocation() : null;
        return ping.line(info, server, config.mode);
    }

    public boolean isCensusEnabled() {
        return config.enabled;
    }

    public boolean hidePlayers() {
        return config.hidePlayers;
    }

    public int fakeOnline() {
        return config.fakeOnline;
    }

    public int fakeMax() {
        return config.fakeMax;
    }
}
