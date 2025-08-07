package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerManager implements Listener {
    private final AMGCore plugin;
    private final DatabaseManager databaseManager;
    private final Set<UUID> vanishedPlayers;
    private final Set<UUID> godModePlayers;
    private final LocaleManager localeManager;

    public PlayerManager(AMGCore plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.vanishedPlayers = new HashSet<>();
        this.godModePlayers = new HashSet<>();
        this.localeManager = LocaleManager.getInstance();

        initializeDatabase();
        loadVanishedPlayers();
        loadGodModePlayers();
        
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void initializeDatabase() {
        try (Connection conn = databaseManager.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS player_states (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    vanished BOOLEAN DEFAULT FALSE,
                    god_mode BOOLEAN DEFAULT FALSE,
                    fly_enabled BOOLEAN DEFAULT FALSE,
                    walk_speed FLOAT DEFAULT 0.2,
                    fly_speed FLOAT DEFAULT 0.1
                )
            """);
        } catch (SQLException e) {
            DebugLogger.severe("Failed to initialize player states table", "PlayerManager", e);
        }
    }

    private void loadVanishedPlayers() {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT player_uuid FROM player_states WHERE vanished = TRUE")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                vanishedPlayers.add(UUID.fromString(rs.getString("player_uuid")));
            }
        } catch (SQLException e) {
            DebugLogger.severe("Failed to load vanished players", "PlayerManager", e);
        }
    }

    private void loadGodModePlayers() {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT player_uuid FROM player_states WHERE god_mode = TRUE")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                godModePlayers.add(UUID.fromString(rs.getString("player_uuid")));
            }
        } catch (SQLException e) {
            DebugLogger.severe("Failed to load god mode players", "PlayerManager", e);
        }
    }

    public boolean toggleVanish(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        boolean vanished = !vanishedPlayers.contains(uuid);

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "MERGE INTO player_states (player_uuid, vanished) VALUES (?, ?)"
             )) {
            stmt.setString(1, uuid.toString());
            stmt.setBoolean(2, vanished);
            stmt.executeUpdate();

            if (vanished) {
                vanishedPlayers.add(uuid);
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (!onlinePlayer.hasPermission("amgcore.command.vanish")) {
                        onlinePlayer.hidePlayer(plugin, player);
                    }
                }
                player.setGameMode(GameMode.SPECTATOR);
                broadcastStaffMessage(localeManager.getComponent("player.vanish.enabled_other", player.getName()));
            } else {
                vanishedPlayers.remove(uuid);
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    onlinePlayer.showPlayer(plugin, player);
                }
                player.setGameMode(GameMode.SURVIVAL);
                broadcastStaffMessage(localeManager.getComponent("player.vanish.disabled_other", player.getName()));
            }

            return vanished;
        } catch (SQLException e) {
            DebugLogger.severe("Failed to update vanish state", "PlayerManager", e);
            return !vanished;
        }
    }

    public boolean toggleGodMode(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        boolean godMode = !godModePlayers.contains(uuid);

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "MERGE INTO player_states (player_uuid, god_mode) VALUES (?, ?)"
             )) {
            stmt.setString(1, uuid.toString());
            stmt.setBoolean(2, godMode);
            stmt.executeUpdate();

            if (godMode) {
                godModePlayers.add(uuid);
                player.sendMessage(localeManager.getComponent("player.god.enabled"));
            } else {
                godModePlayers.remove(uuid);
                player.sendMessage(localeManager.getComponent("player.god.disabled"));
            }

            return godMode;
        } catch (SQLException e) {
            DebugLogger.severe("Failed to update god mode state", "PlayerManager", e);
            return !godMode;
        }
    }

    public void setFly(@NotNull Player player, boolean enabled) {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "MERGE INTO player_states (player_uuid, fly_enabled) VALUES (?, ?)"
             )) {
            stmt.setString(1, player.getUniqueId().toString());
            stmt.setBoolean(2, enabled);
            stmt.executeUpdate();

            player.setAllowFlight(enabled);
            player.setFlying(enabled);
            player.sendMessage(localeManager.getComponent(enabled ? "player.fly.enabled" : "player.fly.disabled"));
        } catch (SQLException e) {
            DebugLogger.severe("Failed to update fly state", "PlayerManager", e);
        }
    }

    public void setSpeed(@NotNull Player player, float speed, boolean flying) {
        if (speed < 0.0f || speed > 1.0f) {
            throw new IllegalArgumentException("Speed must be between 0.0 and 1.0");
        }

        if (flying) {
            player.setFlySpeed(speed);
        } else {
            player.setWalkSpeed(speed);
        }

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "MERGE INTO player_states (player_uuid, " + (flying ? "fly_speed" : "walk_speed") + ") VALUES (?, ?)"
             )) {
            stmt.setString(1, player.getUniqueId().toString());
            stmt.setFloat(2, speed);
            stmt.executeUpdate();
        } catch (SQLException e) {
            DebugLogger.severe("Failed to update speed state", "PlayerManager", e);
        }

        player.sendMessage(localeManager.getComponent("player.speed.self", flying ? "fly" : "walk", String.valueOf(speed)));
    }

    public void heal(@NotNull Player player) {
        player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
        player.setFireTicks(0);
        player.sendMessage(localeManager.getComponent("player.heal.self"));
    }

    public void feed(@NotNull Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.sendMessage(localeManager.getComponent("player.feed.self"));
    }

    public boolean isVanished(@NotNull Player player) {
        return vanishedPlayers.contains(player.getUniqueId());
    }

    public boolean isGodMode(@NotNull Player player) {
        return godModePlayers.contains(player.getUniqueId());
    }

    private void broadcastStaffMessage(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("amgcore.command.vanish")) {
                player.sendMessage(message);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT vanished, god_mode, fly_enabled, walk_speed, fly_speed FROM player_states WHERE player_uuid = ?"
             )) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                if (rs.getBoolean("vanished")) {
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        if (!onlinePlayer.hasPermission("amgcore.command.vanish")) {
                            onlinePlayer.hidePlayer(plugin, player);
                        }
                    }
                    player.setGameMode(GameMode.SPECTATOR);
                }

                if (rs.getBoolean("fly_enabled")) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                }

                player.setWalkSpeed(rs.getFloat("walk_speed"));
                player.setFlySpeed(rs.getFloat("fly_speed"));
            }
        } catch (SQLException e) {
            DebugLogger.severe("Failed to load player states", "PlayerManager", e);
        }

        // Hide vanished players from the joining player
        if (!player.hasPermission("amgcore.command.vanish")) {
            for (UUID vanishedUuid : vanishedPlayers) {
                Player vanishedPlayer = Bukkit.getPlayer(vanishedUuid);
                if (vanishedPlayer != null) {
                    player.hidePlayer(plugin, vanishedPlayer);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "MERGE INTO player_states (player_uuid, walk_speed, fly_speed) VALUES (?, ?, ?)"
             )) {
            stmt.setString(1, uuid.toString());
            stmt.setFloat(2, player.getWalkSpeed());
            stmt.setFloat(3, player.getFlySpeed());
            stmt.executeUpdate();
        } catch (SQLException e) {
            DebugLogger.severe("Failed to save player states", "PlayerManager", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && godModePlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && godModePlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
} 