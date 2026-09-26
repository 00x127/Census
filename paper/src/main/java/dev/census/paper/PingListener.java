package dev.census.paper;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.List;
import java.util.UUID;

public final class PingListener implements Listener {

    private static final UUID SAMPLE_ID = new UUID(0L, 0L);
    private final CensusPaperPlugin plugin;

    public PingListener(CensusPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(ServerListPingEvent event) {
        if (!plugin.isCensusEnabled()) {
            return;
        }
        try {
            if (event instanceof PaperServerListPingEvent paper) {
                handlePaper(paper);
                return;
            }
        } catch (Throwable ignored) {
            // plain spigot, paper event isnt around, fall through to max only
        }
        if (plugin.fakeMax() >= 0) {
            event.setMaxPlayers(plugin.fakeMax());
        }
    }

    @SuppressWarnings("removal")
    private void handlePaper(PaperServerListPingEvent event) {
        if (plugin.fakeOnline() >= 0) {
            event.setNumPlayers(plugin.fakeOnline());
        }
        if (plugin.fakeMax() >= 0) {
            event.setMaxPlayers(plugin.fakeMax());
        }
        if (plugin.hidePlayers()) {
            event.setHidePlayers(true);
            return;
        }
        String message = plugin.buildMessage(event.getAddress());
        if (message == null) {
            return;
        }
        try {
            List<PaperServerListPingEvent.ListedPlayerInfo> listed = event.getListedPlayers();
            listed.clear();
            listed.add(new PaperServerListPingEvent.ListedPlayerInfo(message, SAMPLE_ID));
        } catch (Throwable throwable) {
            // older paper, the deprecated sample list is what counts there
            List<PlayerProfile> sample = event.getPlayerSample();
            sample.clear();
            sample.add(Bukkit.createProfile(SAMPLE_ID, message));
        }
    }
}
