package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StatsManager implements Listener {
    private final DatabaseManager databaseManager;
    private final Map<UUID, PlayerStats> playerStats;
    private final Map<UUID, Instant> sessionStart;

    public StatsManager(AMGCore plugin, DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.playerStats = new ConcurrentHashMap<>();
        this.sessionStart = new ConcurrentHashMap<>();

        initializeDatabase();
        loadPlayerStats();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Start auto-save task (runs every 5 minutes)
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveAllPlayerStats, 6000L, 6000L);
    }

    private void initializeDatabase() {
        try (Connection conn = databaseManager.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS player_stats (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    first_join TIMESTAMP,
                    last_seen TIMESTAMP,
                    playtime_seconds BIGINT,
                    deaths INT,
                    kills INT,
                    pvp_kills INT,
                    mob_kills INT
                )
            """);
        } catch (SQLException e) {
            DebugLogger.severe("Failed to initialize player stats table", "StatsManager", e);
        }
    }

    private void loadPlayerStats() {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM player_stats")) {
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
                PlayerStats stats = new PlayerStats(
                    rs.getTimestamp("first_join").toInstant(),
                    rs.getTimestamp("last_seen").toInstant(),
                    Duration.ofSeconds(rs.getLong("playtime_seconds")),
                    rs.getInt("deaths"),
                    rs.getInt("kills"),
                    rs.getInt("pvp_kills"),
                    rs.getInt("mob_kills")
                );
                playerStats.put(playerUuid, stats);
            }
        } catch (SQLException e) {
            DebugLogger.severe("Failed to load player stats", "StatsManager", e);
        }
    }

    private void saveAllPlayerStats() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlaytime(player.getUniqueId());
        }

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("""
                 MERGE INTO player_stats (
                     player_uuid, first_join, last_seen, playtime_seconds,
                     deaths, kills, pvp_kills, mob_kills
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
             """)) {
            for (Map.Entry<UUID, PlayerStats> entry : playerStats.entrySet()) {
                UUID uuid = entry.getKey();
                PlayerStats stats = entry.getValue();

                stmt.setString(1, uuid.toString());
                stmt.setTimestamp(2, java.sql.Timestamp.from(stats.firstJoin));
                stmt.setTimestamp(3, java.sql.Timestamp.from(stats.lastSeen));
                stmt.setLong(4, stats.playtime.getSeconds());
                stmt.setInt(5, stats.deaths);
                stmt.setInt(6, stats.kills);
                stmt.setInt(7, stats.pvpKills);
                stmt.setInt(8, stats.mobKills);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            DebugLogger.severe("Failed to save player stats", "StatsManager", e);
        }
    }

    private void updatePlaytime(UUID uuid) {
        Instant start = sessionStart.get(uuid);
        if (start != null) {
            PlayerStats stats = playerStats.get(uuid);
            if (stats != null) {
                Duration sessionTime = Duration.between(start, Instant.now());
                stats.playtime = stats.playtime.plus(sessionTime);
                sessionStart.put(uuid, Instant.now());
            }
        }
    }

    public PlayerStats getPlayerStats(@NotNull UUID uuid) {
        return playerStats.get(uuid);
    }

    public void addKill(@NotNull UUID uuid, boolean isPvP) {
        PlayerStats stats = playerStats.get(uuid);
        if (stats != null) {
            stats.kills++;
            if (isPvP) {
                stats.pvpKills++;
            } else {
                stats.mobKills++;
            }
        }
    }

    public void addDeath(@NotNull UUID uuid) {
        PlayerStats stats = playerStats.get(uuid);
        if (stats != null) {
            stats.deaths++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Instant now = Instant.now();

        PlayerStats stats = playerStats.get(uuid);
        if (stats == null) {
            stats = new PlayerStats(now, now, Duration.ZERO, 0, 0, 0, 0);
            playerStats.put(uuid, stats);
        }
        stats.lastSeen = now;
        sessionStart.put(uuid, now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        updatePlaytime(uuid);
        sessionStart.remove(uuid);

        PlayerStats stats = playerStats.get(uuid);
        if (stats != null) {
            stats.lastSeen = Instant.now();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        addDeath(victim.getUniqueId());
        if (killer != null) {
            addKill(killer.getUniqueId(), true);
        }
    }

    public static class PlayerStats {
        final Instant firstJoin;
        Instant lastSeen;
        Duration playtime;
        int deaths;
        int kills;
        int pvpKills;
        int mobKills;

        PlayerStats(Instant firstJoin, Instant lastSeen, Duration playtime,
                   int deaths, int kills, int pvpKills, int mobKills) {
            this.firstJoin = firstJoin;
            this.lastSeen = lastSeen;
            this.playtime = playtime;
            this.deaths = deaths;
            this.kills = kills;
            this.pvpKills = pvpKills;
            this.mobKills = mobKills;
        }

        public Instant getFirstJoin() {
            return firstJoin;
        }

        public Instant getLastSeen() {
            return lastSeen;
        }

        public Duration getPlaytime() {
            return playtime;
        }

        public int getDeaths() {
            return deaths;
        }

        public int getKills() {
            return kills;
        }

        public int getPvPKills() {
            return pvpKills;
        }

        public int getMobKills() {
            return mobKills;
        }

        public double getKDRatio() {
            return deaths == 0 ? kills : (double) kills / deaths;
        }
    }
} 