package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TeleportManager implements Listener {
    private final AMGCore plugin;
    private final DatabaseManager databaseManager;
    private final LocaleManager localeManager;
    private final Map<UUID, TeleportRequest> teleportRequests;
    private final Map<UUID, Map<String, Location>> homes;
    private final Map<String, Location> warps;
    private Location spawnLocation;
    private final Map<UUID, Location> lastLocations;
    private final Map<UUID, String> lastUsedHomes; // Track last used home for each player
    private static final long REQUEST_TIMEOUT = TimeUnit.MINUTES.toMillis(1); // 1 minute timeout 
    private static final int MAX_HOMES = 3; // Maximum number of homes per player

    public TeleportManager(@NotNull AMGCore plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
        this.localeManager = plugin.getLocaleManager();
        this.teleportRequests = new ConcurrentHashMap<>();
        this.homes = new ConcurrentHashMap<>();
        this.warps = new ConcurrentHashMap<>();
        this.lastLocations = new ConcurrentHashMap<>();
        this.lastUsedHomes = new ConcurrentHashMap<>();

        // Initialize database tables
        try (Connection conn = databaseManager.getConnection()) {
            // Create player_homes table
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_homes (
                        uuid VARCHAR(36) NOT NULL,
                        name VARCHAR(50) NOT NULL,
                        world VARCHAR(50) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL,
                        pitch FLOAT NOT NULL,
                        PRIMARY KEY (uuid, name)
                    )
                """);
            }

            // Create last_used_homes table
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS last_used_homes (
                        uuid VARCHAR(36) PRIMARY KEY,
                        home_name VARCHAR(50) NOT NULL
                    )
                """);
            }

            // Create warps table
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS warps (
                        name VARCHAR(50) PRIMARY KEY,
                        world VARCHAR(50) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL,
                        pitch FLOAT NOT NULL
                    )
                """);
            }
        } catch (SQLException e) {
            DebugLogger.severe("TeleportManager", "Failed to initialize database tables", e);
        }

        // Load warps and spawn location
        loadWarps();
        loadSpawnLocation();
        
        // Register events
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        // Start request cleanup task
        startCleanupTask();
    }

    private void loadWarps() {
        try (Connection conn = databaseManager.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT * FROM warps");
                while (rs.next()) {
                    String name = rs.getString("name");
                    World world = Bukkit.getWorld(rs.getString("world"));
                    if (world != null) {
                        Location loc = new Location(
                            world,
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z"),
                            rs.getFloat("yaw"),
                            rs.getFloat("pitch")
                        );
                        warps.put(name, loc);
                    }
                }
            }
        } catch (SQLException e) {
            DebugLogger.severe("TeleportManager", localeManager.getMessage("teleport.error.load_warps"), e);
        }
    }

    private void loadSpawnLocation() {
        try (Connection conn = databaseManager.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT * FROM spawn_location WHERE id = 1");
                if (rs.next()) {
                    World world = Bukkit.getWorld(rs.getString("world"));
                    if (world != null) {
                        spawnLocation = new Location(
                            world,
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z"),
                            rs.getFloat("yaw"),
                            rs.getFloat("pitch")
                        );
                    }
                } else {
                    // Use default world spawn if no spawn is set
                    World defaultWorld = Bukkit.getWorlds().get(0);
                    spawnLocation = defaultWorld.getSpawnLocation();
                    setSpawnLocation(spawnLocation);
                }
            }
        } catch (SQLException e) {
            DebugLogger.severe("TeleportManager", localeManager.getMessage("teleport.error.load_spawn"), e);
            // Use default world spawn as fallback
            World defaultWorld = Bukkit.getWorlds().get(0);
            spawnLocation = defaultWorld.getSpawnLocation();
        }
    }

    private void startCleanupTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            teleportRequests.values().removeIf(request -> {
                if (now - request.timestamp > REQUEST_TIMEOUT) {
                    Player requester = Bukkit.getPlayer(request.requester);
                    Player target = Bukkit.getPlayer(request.target);
                    if (requester != null) {
                        requester.sendMessage("§cYour teleport request to " + 
                            (target != null ? target.getName() : "offline player") + 
                            " has expired.");
                    }
                    return true;
                }
                return false;
            });
        }, 20L, 20L); // Run every second
    }

    public void loadPlayerHomes(@NotNull UUID playerUuid) {
        try (Connection conn = databaseManager.getConnection()) {
            // Load homes
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM player_homes WHERE uuid = ?")) {
                stmt.setString(1, playerUuid.toString());
                ResultSet rs = stmt.executeQuery();
                
                Map<String, Location> playerHomes = new HashMap<>();
                while (rs.next()) {
                    String name = rs.getString("name");
                    World world = Bukkit.getWorld(rs.getString("world"));
                    if (world != null) {
                        Location loc = new Location(
                            world,
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z"),
                            rs.getFloat("yaw"),
                            rs.getFloat("pitch")
                        );
                        playerHomes.put(name, loc);
                    }
                }
                homes.put(playerUuid, playerHomes);
            }

            // Load last used home
            try (PreparedStatement stmt = conn.prepareStatement("SELECT home_name FROM last_used_homes WHERE uuid = ?")) {
                stmt.setString(1, playerUuid.toString());
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    lastUsedHomes.put(playerUuid, rs.getString("home_name"));
                }
            }
        } catch (SQLException e) {
            DebugLogger.severe("TeleportManager", localeManager.getMessage("teleport.error.load_homes", playerUuid), e);
        }
    }

    public void unloadPlayerHomes(@NotNull UUID playerUuid) {
        homes.remove(playerUuid);
        lastUsedHomes.remove(playerUuid);
    }

    public boolean setHome(@NotNull Player player, @NotNull String name, @NotNull Location location) {
        UUID playerUuid = player.getUniqueId();
        Map<String, Location> playerHomes = homes.computeIfAbsent(playerUuid, k -> new HashMap<>());
        
        if (!playerHomes.containsKey(name) && playerHomes.size() >= MAX_HOMES) {
            return false;
        }

        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                MERGE INTO player_homes (uuid, name, world, x, y, z, yaw, pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, name);
                stmt.setString(3, location.getWorld().getName());
                stmt.setDouble(4, location.getX());
                stmt.setDouble(5, location.getY());
                stmt.setDouble(6, location.getZ());
                stmt.setFloat(7, location.getYaw());
                stmt.setFloat(8, location.getPitch());
                stmt.executeUpdate();
                
                playerHomes.put(name, location.clone());
                return true;
            }
        } catch (SQLException e) {
            DebugLogger.severe("TeleportManager", localeManager.getMessage("teleport.error.set_home", player.getName()), e);
            return false;
        }
    }

    public boolean deleteHome(@NotNull Player player, @NotNull String name) {
        UUID playerUuid = player.getUniqueId();
        Map<String, Location> playerHomes = homes.get(playerUuid);
        if (playerHomes == null || !playerHomes.containsKey(name)) {
            return false;
        }

        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                "DELETE FROM player_homes WHERE uuid = ? AND name = ?"
            )) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, name);
                int affected = stmt.executeUpdate();
                
                if (affected > 0) {
                    playerHomes.remove(name);
                    return true;
                }
                return false;
            }
        } catch (SQLException e) {
            DebugLogger.severe("TeleportManager", localeManager.getMessage("teleport.error.delete_home", player.getName()), e);
            return false;
        }
    }

    // Update getHome to save last used home to database
    @Nullable
    public Location getHome(@NotNull Player player, @NotNull String name) {
        Map<String, Location> playerHomes = homes.get(player.getUniqueId());
        if (playerHomes == null) {
            return null;
        }
        Location loc = playerHomes.get(name);
        if (loc != null) {
            lastUsedHomes.put(player.getUniqueId(), name);
            // Save last used home to database
            try (Connection conn = databaseManager.getConnection()) {
                try (PreparedStatement stmt = conn.prepareStatement("""
                    MERGE INTO last_used_homes (uuid, home_name)
                    VALUES (?, ?)
                """)) {
                    stmt.setString(1, player.getUniqueId().toString());
                    stmt.setString(2, name);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                DebugLogger.warning("TeleportManager", "Failed to save last used home", e);
            }
            return loc.clone();
        }
        return null;
    }

    @NotNull
    public Set<String> getHomeNames(@NotNull Player player) {
        Map<String, Location> playerHomes = homes.get(player.getUniqueId());
        return playerHomes != null ? Collections.unmodifiableSet(playerHomes.keySet()) : Collections.emptySet();
    }

    public boolean setWarp(@NotNull String name, @NotNull Location location) {
        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                MERGE INTO warps (name, world, x, y, z, yaw, pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
                stmt.setString(1, name);
                stmt.setString(2, location.getWorld().getName());
                stmt.setDouble(3, location.getX());
                stmt.setDouble(4, location.getY());
                stmt.setDouble(5, location.getZ());
                stmt.setFloat(6, location.getYaw());
                stmt.setFloat(7, location.getPitch());
                stmt.executeUpdate();
                
                warps.put(name, location.clone());
                return true;
            }
        } catch (SQLException e) {
            DebugLogger.severe("TeleportManager", localeManager.getMessage("teleport.error.set_warp", name), e);
            return false;
        }
    }

    public boolean deleteWarp(@NotNull String name) {
        if (!warps.containsKey(name)) {
            return false;
        }

        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM warps WHERE name = ?")) {
                stmt.setString(1, name);
                int affected = stmt.executeUpdate();
                
                if (affected > 0) {
                    warps.remove(name);
                    return true;
                }
                return false;
            }
        } catch (SQLException e) {
            DebugLogger.severe("TeleportManager", localeManager.getMessage("teleport.error.delete_warp", name), e);
            return false;
        }
    }

    @Nullable
    public Location getWarp(@NotNull String name) {
        Location loc = warps.get(name);
        return loc != null ? loc.clone() : null;
    }

    @NotNull
    public Set<String> getWarpNames() {
        return Collections.unmodifiableSet(warps.keySet());
    }

    /**
     * Records a player's last location before teleporting
     * @param player The player whose location to record
     */
    public void recordLastLocation(@NotNull Player player) {
        lastLocations.put(player.getUniqueId(), player.getLocation().clone());
    }

    /**
     * Gets a player's last location
     * @param player The player whose last location to get
     * @return The last location, or null if none recorded
     */
    @Nullable
    public Location getLastLocation(@NotNull Player player) {
        Location loc = lastLocations.get(player.getUniqueId());
        if (loc != null) {
            lastLocations.remove(player.getUniqueId()); // One-time use
            return loc.clone();
        }
        return null;
    }

    public void setSpawnLocation(@NotNull Location location) {
        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                MERGE INTO spawn_location (id, world, x, y, z, yaw, pitch, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """)) {
                stmt.setString(1, location.getWorld().getName());
                stmt.setDouble(2, location.getX());
                stmt.setDouble(3, location.getY());
                stmt.setDouble(4, location.getZ());
                stmt.setFloat(5, location.getYaw());
                stmt.setFloat(6, location.getPitch());
                stmt.executeUpdate();
                
                spawnLocation = location.clone();
            }
        } catch (SQLException e) {
            DebugLogger.severe("TeleportManager", localeManager.getMessage("teleport.error.set_spawn"), e);
        }
    }

    @NotNull
    public Location getSpawnLocation() {
        return spawnLocation.clone();
    }

    public void sendTeleportRequest(@NotNull Player requester, @NotNull Player target) {
        createTeleportRequest(requester, target, false);
    }

    public boolean acceptTeleportRequest(@NotNull Player target) {
        UUID targetUuid = target.getUniqueId();
        
        // Find request where this player is the target
        Map.Entry<UUID, TeleportRequest> entry = teleportRequests.entrySet().stream()
            .filter(e -> e.getValue().target.equals(targetUuid))
            .findFirst()
            .orElse(null);
        
        if (entry != null) {
            UUID requesterUuid = entry.getKey();
            Player requester = Bukkit.getPlayer(requesterUuid);
            TeleportRequest request = entry.getValue();
            
            if (requester != null && requester.isOnline()) {
                if (request.tpaHere) {
                    // TpaHere - teleport target to requester
                    recordLastLocation(target);
                    target.teleport(requester.getLocation());
                    target.sendMessage(localeManager.getMessage("teleport.tpaccept.success"));
                    requester.sendMessage(localeManager.getMessage("teleport.tpaccept.accepted", target.getName()));
                } else {
                    // Regular tpa - teleport requester to target
                    recordLastLocation(requester);
                    requester.teleport(target.getLocation());
                    requester.sendMessage(localeManager.getMessage("teleport.tpaccept.success"));
                    target.sendMessage(localeManager.getMessage("teleport.tpaccept.accepted", requester.getName()));
                }
                
                // Remove the request
                teleportRequests.remove(requesterUuid);
                return true;
            }
        }
        
        return false;
    }

    public boolean denyTeleportRequest(@NotNull Player target) {
        UUID targetUuid = target.getUniqueId();
        
        // Find request where this player is the target
        Map.Entry<UUID, TeleportRequest> entry = teleportRequests.entrySet().stream()
            .filter(e -> e.getValue().target.equals(targetUuid))
            .findFirst()
            .orElse(null);
        
        if (entry != null) {
            UUID requesterUuid = entry.getKey();
            Player requester = Bukkit.getPlayer(requesterUuid);
            
            if (requester != null) {
                requester.sendMessage(localeManager.getMessage("teleport.tpdeny.denied", target.getName()));
            }
            
            target.sendMessage(localeManager.getMessage("teleport.tpdeny.success"));
            
            // Remove the request
            teleportRequests.remove(requesterUuid);
            return true;
        }
        
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        
        // Remove any teleport requests involving this player
        teleportRequests.entrySet().removeIf(entry -> {
            TeleportRequest request = entry.getValue();
            if (request.requester.equals(playerUuid) || request.target.equals(playerUuid)) {
                Player otherPlayer = Bukkit.getPlayer(
                    request.requester.equals(playerUuid) ? request.target : request.requester
                );
                if (otherPlayer != null) {
                    otherPlayer.sendMessage(localeManager.getMessage("teleport.request.cancelled_quit", player.getName()));
                }
                return true;
            }
            return false;
        });
        
        // Unload player's homes
        unloadPlayerHomes(playerUuid);
        lastUsedHomes.remove(playerUuid); // Clear last used home on quit
    }

    private static class TeleportRequest {
        private final UUID requester;
        private final UUID target;
        private final long timestamp;
        private final boolean tpaHere; // true if target should teleport to requester, false if requester should teleport to target

        public TeleportRequest(UUID requester, UUID target, boolean tpaHere) {
            this.requester = requester;
            this.target = target;
            this.timestamp = System.currentTimeMillis();
            this.tpaHere = tpaHere;
        }
    }

    public void createTeleportRequest(@NotNull Player requester, @NotNull Player target, boolean tpaHere) {
        UUID requesterUuid = requester.getUniqueId();
        UUID targetUuid = target.getUniqueId();
        
        // Cancel any existing request from this player
        TeleportRequest existingRequest = teleportRequests.get(requesterUuid);
        if (existingRequest != null) {
            Player existingTarget = Bukkit.getPlayer(existingRequest.target);
            if (existingTarget != null) {
                existingTarget.sendMessage(localeManager.getMessage("teleport.request.cancelled_sender", requester.getName()));
            }
        }
        
        // Create new request
        teleportRequests.put(requesterUuid, new TeleportRequest(requesterUuid, targetUuid, tpaHere));
        
        // Send messages
        requester.sendMessage(localeManager.getMessage("teleport.tpa.sent", target.getName()));
        target.sendMessage(localeManager.getMessage("teleport.tpa.received", requester.getName()));
        target.sendMessage(localeManager.getMessage("teleport.request.instructions"));
    }

    // Add method to get last used home
    @Nullable
    public Location getLastUsedHome(@NotNull Player player) {
        String lastHomeName = lastUsedHomes.get(player.getUniqueId());
        if (lastHomeName != null) {
            return getHome(player, lastHomeName);
        }
        // If no last used home, try to get the "home" home
        Location defaultHome = getHome(player, "home");
        if (defaultHome != null) {
            return defaultHome;
        }
        // If no "home" home exists, get the first home in the list
        Map<String, Location> playerHomes = homes.get(player.getUniqueId());
        if (playerHomes != null && !playerHomes.isEmpty()) {
            String firstHome = playerHomes.keySet().iterator().next();
            return playerHomes.get(firstHome);
        }
        return null;
    }
} 