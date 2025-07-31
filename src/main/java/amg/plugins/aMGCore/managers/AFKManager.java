package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AFKManager implements Listener {
    private final AMGCore plugin;
    private final DatabaseManager databaseManager;
    private final MiniMessage miniMessage;
    private final Map<UUID, AFKData> afkPlayers;
    private final Map<UUID, Location> lastLocations;
    private final Map<UUID, Instant> lastActivity;
    private final LocaleManager localeManager;

    private static final Duration AFK_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration KICK_TIMEOUT = Duration.ofMinutes(30);

    public AFKManager(AMGCore plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.miniMessage = MiniMessage.miniMessage();
        this.afkPlayers = new ConcurrentHashMap<>();
        this.lastLocations = new ConcurrentHashMap<>();
        this.lastActivity = new ConcurrentHashMap<>();
        this.localeManager = plugin.getLocaleManager();

        initializeDatabase();
        loadAFKPlayers();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Start AFK check task (runs every 30 seconds)
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkAFKPlayers, 600L, 600L);
    }

    private void initializeDatabase() {
        try (Connection conn = databaseManager.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS afk_players (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    afk_since TIMESTAMP,
                    reason VARCHAR(255)
                )
            """);
        } catch (SQLException e) {
            DebugLogger.severe("Failed to initialize AFK table", "AFKManager", e);
        }
    }

    private void loadAFKPlayers() {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM afk_players")) {
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
                Instant afkSince = rs.getTimestamp("afk_since").toInstant();
                String reason = rs.getString("reason");

                afkPlayers.put(playerUuid, new AFKData(playerUuid, afkSince, reason));
            }
        } catch (SQLException e) {
            DebugLogger.severe("Failed to load AFK players", "AFKManager", e);
        }
    }

    private void checkAFKPlayers() {
        Instant now = Instant.now();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            // Skip players who are already AFK
            if (isAFK(player)) {
                AFKData data = afkPlayers.get(uuid);
                if (data != null && !player.hasPermission("amgcore.afk.exempt")) {
                    Duration afkDuration = Duration.between(data.afkSince, now);
                    if (afkDuration.compareTo(KICK_TIMEOUT) >= 0) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (localeManager != null) {
                                player.kick(localeManager.getComponent("afk.kick_message"));
                            } else {
                                player.kick(miniMessage.deserialize("<red>You have been kicked for being AFK too long."));
                            }
                            setAFK(player, false, null);
                        });
                    }
                }
                continue;
            }

            // Check if player hasn't moved
            Location lastLoc = lastLocations.get(uuid);
            Location currentLoc = player.getLocation();
            lastLocations.put(uuid, currentLoc);

            if (lastLoc != null && lastLoc.equals(currentLoc)) {
                Instant lastActive = lastActivity.get(uuid);
                if (lastActive != null) {
                    Duration idle = Duration.between(lastActive, now);
                    if (idle.compareTo(AFK_TIMEOUT) >= 0) {
                        setAFK(player, true, "Auto-detected");
                    }
                }
            } else {
                lastActivity.put(uuid, now);
            }
        }
    }

    public void setAFK(@NotNull Player player, boolean afk, String reason) {
        UUID uuid = player.getUniqueId();
        boolean wasAFK = isAFK(player);

        if (afk && !wasAFK) {
            AFKData data = new AFKData(uuid, Instant.now(), reason);
            afkPlayers.put(uuid, data);

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "MERGE INTO afk_players (player_uuid, afk_since, reason) VALUES (?, ?, ?)"
                 )) {
                stmt.setString(1, uuid.toString());
                stmt.setTimestamp(2, java.sql.Timestamp.from(data.afkSince));
                stmt.setString(3, reason);
                stmt.executeUpdate();
            } catch (SQLException e) {
                DebugLogger.severe("Failed to save AFK state", "AFKManager", e);
            }

            // Update tab list name
            String name = player.getName();
            if (localeManager != null) {
                String tabPrefix = localeManager.getMessage("afk.tab_prefix");
                player.playerListName(miniMessage.deserialize(tabPrefix + name));
            } else {
                player.playerListName(miniMessage.deserialize("<gray>[AFK] " + name));
            }

            // Broadcast AFK message
            if (localeManager != null) {
                String reasonSuffix = reason != null ? localeManager.getMessage("afk.reason_suffix", reason) : "";
                Component message = localeManager.getComponent("afk.enabled", name, reasonSuffix);
                Bukkit.broadcast(message);
            } else {
                Component message = miniMessage.deserialize("<yellow>" + name + " is now AFK" +
                    (reason != null ? " (" + reason + ")" : ""));
                Bukkit.broadcast(message);
            }
        } else if (!afk && wasAFK) {
            afkPlayers.remove(uuid);

            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM afk_players WHERE player_uuid = ?")) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                DebugLogger.severe("Failed to remove AFK state", "AFKManager", e);
            }

            // Restore tab list name
            String name = player.getName();
            player.playerListName(Component.text(name));

            // Broadcast return message
            if (localeManager != null) {
                Component message = localeManager.getComponent("afk.disabled", name);
                Bukkit.broadcast(message);
            } else {
                Component message = miniMessage.deserialize("<yellow>" + name + " is no longer AFK");
                Bukkit.broadcast(message);
            }
        }
    }

    public boolean isAFK(@NotNull Player player) {
        return afkPlayers.containsKey(player.getUniqueId());
    }

    public Duration getAFKTime(@NotNull Player player) {
        AFKData data = afkPlayers.get(player.getUniqueId());
        if (data == null) {
            return Duration.ZERO;
        }
        return Duration.between(data.afkSince, Instant.now());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition()) {
            updateActivity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        updatePlayerActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        // Don't count /afk command as activity
        if (!event.getMessage().toLowerCase().startsWith("/afk")) {
            updateActivity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        updateActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lastLocations.put(player.getUniqueId(), player.getLocation());
        lastActivity.put(player.getUniqueId(), Instant.now());

        // If player was AFK when they disconnected, update their tab list name
        if (isAFK(player)) {
            if (localeManager != null) {
                String tabPrefix = localeManager.getMessage("afk.tab_prefix");
                player.playerListName(miniMessage.deserialize(tabPrefix + player.getName()));
            } else {
                player.playerListName(miniMessage.deserialize("<gray>[AFK] " + player.getName()));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastLocations.remove(uuid);
        lastActivity.remove(uuid);
    }

    private void updateActivity(Player player) {
        if (isAFK(player)) {
            setAFK(player, false, null);
        }
        lastActivity.put(player.getUniqueId(), Instant.now());
    }

    private void updatePlayerActivity(Player player) {
        if (isAFK(player)) {
            setAFK(player, false, null);
        }
        lastActivity.put(player.getUniqueId(), Instant.now());
    }

    public static class AFKData {
        private final UUID uuid;
        private final Instant afkSince;
        private final String reason;

        public AFKData(UUID uuid, Instant afkSince, @Nullable String reason) {
            this.uuid = uuid;
            this.afkSince = afkSince;
            this.reason = reason;
        }

        public UUID getUuid() {
            return uuid;
        }

        public Instant getAfkSince() {
            return afkSince;
        }

        public String getReason() {
            return reason;
        }
    }
} 