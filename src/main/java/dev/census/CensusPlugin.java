package dev.census;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.server.ServerPing;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Plugin(
        id = "census",
        name = "Census",
        version = "1.0.0",
        description = "Shows rotating messages when someone hovers the player count on the server list.",
        authors = {"0x127"}
)
public final class CensusPlugin {

    private static final UUID SAMPLE_ID = new UUID(0L, 0L);

    private final Logger logger;
    private final Path dataDirectory;
    private final Config config;
    private final Ping ping;
    private GeoIpService geo;

    @Inject
    public CensusPlugin(Logger logger, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        copyDefault("config.yml");
        copyDefault("messages.yml");

        this.config = Config.read(dataDirectory.resolve("config.yml"));
        this.ping = new Ping(dataDirectory.resolve("messages.yml"));
        ping.reload(true);

        if (config.geoipEnabled) {
            loadGeo();
        }
        if (!config.serverHost.isBlank() && geo != null) {
            CompletableFuture.runAsync(this::resolveServerLocation);
        }
        logger.info("census on, mode " + config.mode + ", " + ping.count() + " hover messages");
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        if (!config.enabled) {
            return;
        }

        ServerPing.Builder builder = event.getPing().asBuilder();
        if (config.fakeOnline >= 0) {
            builder.onlinePlayers(config.fakeOnline);
        }
        if (config.fakeMax >= 0) {
            builder.maximumPlayers(config.fakeMax);
        }
        if (config.hidePlayers) {
            builder.nullPlayers();
            event.setPing(builder.build());
            return;
        }

        GeoInfo info = lookup(event);
        GeoInfo server = geo != null ? geo.getServerLocation() : null;
        String line = ping.line(info, server, config.mode);
        if (line == null) {
            return;
        }

        builder.clearSamplePlayers();
        builder.samplePlayers(new ServerPing.SamplePlayer(line, SAMPLE_ID));
        event.setPing(builder.build());
    }

    private GeoInfo lookup(ProxyPingEvent event) {
        try {
            InetSocketAddress remote = event.getConnection().getRemoteAddress();
            if (geo == null || remote == null || remote.getAddress() == null) {
                return null;
            }
            return geo.lookup(remote.getAddress().getHostAddress());
        } catch (Exception exception) {
            return null;
        }
    }

    private void loadGeo() {
        try {
            Path city = dataDirectory.resolve("geoip-city.mmdb");
            Path asn = dataDirectory.resolve("geoip-asn.mmdb");
            if (!Files.isRegularFile(city) || !Files.isRegularFile(asn)) {
                logger.warn("geoip databases are missing, geo/isp messages are off");
                return;
            }
            try (InputStream zoneTable = getClass().getResourceAsStream("/zone1970.tab")) {
                geo = new GeoIpService(city.toFile(), asn.toFile(), 50000, zoneTable);
            }
            logger.info("geoip databases loaded");
        } catch (Exception exception) {
            geo = null;
            logger.warn("couldnt load the geoip databases, geo/isp messages are off");
        }
    }

    private void resolveServerLocation() {
        try {
            GeoInfo location = geo.lookup(InetAddress.getByName(config.serverHost).getHostAddress());
            geo.setServerLocation(location);
            if (location != null && location.city() != null) {
                logger.info("server location resolved to " + location.city());
            }
        } catch (Exception exception) {
            logger.warn("couldnt resolve server-host '" + config.serverHost + "'");
        }
    }

    private void copyDefault(String name) {
        try {
            Files.createDirectories(dataDirectory);
            Path target = dataDirectory.resolve(name);
            if (Files.notExists(target)) {
                try (InputStream in = getClass().getResourceAsStream("/" + name)) {
                    if (in != null) {
                        Files.copy(in, target);
                    }
                }
            }
        } catch (IOException exception) {
            logger.warn("couldnt write the default " + name);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (geo != null) {
            try {
                geo.close();
            } catch (IOException exception) {
                logger.warn("couldnt close the geoip databases");
            }
        }
    }
}
