package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player playtime tracking.
 */
public class PlaytimeManager implements Listener {
    private final AMGCore plugin;
    private final DatabaseManager databaseManager;
    private final Map<UUID, Instant> sessionStart;
    private final Map<UUID, Long> cachedPlaytime;
    private static final long SAVE_INTERVAL = 300L; // Save playtime every 5 minutes

    /**
     * Creates a new PlaytimeManager.
     *
     * @param plugin The plugin instance
     * @param databaseManager The database manager
     */
    public PlaytimeManager(AMGCore plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.sessionStart = new ConcurrentHashMap<>();
        this.cachedPlaytime = new ConcurrentHashMap<>();
        
        // Initialize database
        initializeDatabase();
        
        // Register events
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        // Start periodic save task
        startSaveTask();
        
        // Load online players (in case of reload)
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerPlaytime(player.getUniqueId());
            sessionStart.put(player.getUniqueId(), Instant.now());
        }
    }
    
    private void initializeDatabase() {
        try (Connection conn = databaseManager.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                // Create playtime table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_playtime (
                        uuid VARCHAR(36) PRIMARY KEY,
                        playtime_seconds BIGINT NOT NULL DEFAULT 0,
                        last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        first_join TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
            }
        } catch (SQLException e) {
            DebugLogger.severe("PlaytimeManager", "Failed to initialize database tables", e);
            throw new RuntimeException("Failed to initialize playtime database tables", e);
        }
    }
    
    private void loadPlayerPlaytime(UUID playerUuid) {
        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT playtime_seconds FROM player_playtime WHERE uuid = ?"
            )) {
                stmt.setString(1, playerUuid.toString());
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    cachedPlaytime.put(playerUuid, rs.getLong("playtime_seconds"));
                } else {
                    // Create new entry for player
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO player_playtime (uuid, playtime_seconds) VALUES (?, 0)"
                    )) {
                        insertStmt.setString(1, playerUuid.toString());
                        insertStmt.executeUpdate();
                    }
                    cachedPlaytime.put(playerUuid, 0L);
                }
            }
        } catch (SQLException e) {
            DebugLogger.severe("PlaytimeManager", "Failed to load playtime for player: " + playerUuid, e);
            cachedPlaytime.put(playerUuid, 0L);
        }
    }
    
    private void startSaveTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveAllPlaytimes, SAVE_INTERVAL * 20L, SAVE_INTERVAL * 20L);
    }
    
    private void saveAllPlaytimes() {
        Map<UUID, Long> updates = new HashMap<>();
        
        // Calculate current playtime for all online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerUuid = player.getUniqueId();
            Instant start = sessionStart.get(playerUuid);
            
            if (start != null) {
                long currentSessionSeconds = Duration.between(start, Instant.now()).getSeconds();
                long totalPlaytime = cachedPlaytime.getOrDefault(playerUuid, 0L) + currentSessionSeconds;
                updates.put(playerUuid, totalPlaytime);
                
                // Update cached value and reset session start
                cachedPlaytime.put(playerUuid, totalPlaytime);
                sessionStart.put(playerUuid, Instant.now());
            }
        }
        
        // Save to database
        if (!updates.isEmpty()) {
            try (Connection conn = databaseManager.getConnection()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "MERGE INTO player_playtime (uuid, playtime_seconds, last_seen) VALUES (?, ?, CURRENT_TIMESTAMP)"
                )) {
                    for (Map.Entry<UUID, Long> entry : updates.entrySet()) {
                        stmt.setString(1, entry.getKey().toString());
                        stmt.setLong(2, entry.getValue());
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            } catch (SQLException e) {
                DebugLogger.severe("PlaytimeManager", "Failed to save playtimes", e);
            }
        }
    }
    
    /**
     * Gets the total playtime for a player.
     *
     * @param playerUuid The player's UUID
     * @return The total playtime in seconds
     */
    public long getPlaytimeSeconds(@NotNull UUID playerUuid) {
        // If player is online, calculate current session time
        if (Bukkit.getPlayer(playerUuid) != null && sessionStart.containsKey(playerUuid)) {
            Instant start = sessionStart.get(playerUuid);
            long currentSessionSeconds = Duration.between(start, Instant.now()).getSeconds();
            return cachedPlaytime.getOrDefault(playerUuid, 0L) + currentSessionSeconds;
        }
        
        // Otherwise return cached value
        return cachedPlaytime.getOrDefault(playerUuid, 0L);
    }
    
    /**
     * Gets the formatted playtime for a player.
     *
     * @param playerUuid The player's UUID
     * @return The formatted playtime string (e.g., "5d 3h 42m")
     */
    public String getFormattedPlaytime(@NotNull UUID playerUuid) {
        long seconds = getPlaytimeSeconds(playerUuid);
        Duration duration = Duration.ofSeconds(seconds);
        
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        sb.append(minutes).append("m");
        
        return sb.toString().trim();
    }
    
    /**
     * Saves the playtime for a player.
     *
     * @param playerUuid The player's UUID
     */
    public void savePlayerPlaytime(@NotNull UUID playerUuid) {
        long playtime = getPlaytimeSeconds(playerUuid);
        
        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                "MERGE INTO player_playtime (uuid, playtime_seconds, last_seen) VALUES (?, ?, CURRENT_TIMESTAMP)"
            )) {
                stmt.setString(1, playerUuid.toString());
                stmt.setLong(2, playtime);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            DebugLogger.severe("PlaytimeManager", "Failed to save playtime for player: " + playerUuid, e);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        loadPlayerPlaytime(playerUuid);
        sessionStart.put(playerUuid, Instant.now());
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        
        // Calculate final playtime for this session
        Instant start = sessionStart.remove(playerUuid);
        if (start != null) {
            long currentSessionSeconds = Duration.between(start, Instant.now()).getSeconds();
            long totalPlaytime = cachedPlaytime.getOrDefault(playerUuid, 0L) + currentSessionSeconds;
            cachedPlaytime.put(playerUuid, totalPlaytime);
            
            // Save to database
            savePlayerPlaytime(playerUuid);
        }
    }
    
    /**
     * Shuts down the playtime manager, saving all playtimes.
     */
    public void shutdown() {
        saveAllPlaytimes();
    }
} 