package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import io.papermc.paper.event.player.AsyncChatEvent;

public class JailManager implements Listener {
    private final AMGCore plugin;
    private final DatabaseManager databaseManager;
    private final LocaleManager localeManager;
    private final Map<UUID, JailData> jailedPlayers;
    private final Map<String, JailLocation> jails;
    private final Map<UUID, ItemStack[]> savedInventories;
    private final Map<UUID, Location> lastLocations;
    private BukkitTask jailTimerTask;
    private LuckPerms luckPerms;
    private static final String JAIL_PERMISSION = "amgcore.jail.jailed";
    
    public JailManager(AMGCore plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.localeManager = plugin.getLocaleManager();
        this.jailedPlayers = new ConcurrentHashMap<>();
        this.jails = new ConcurrentHashMap<>();
        this.savedInventories = new ConcurrentHashMap<>();
        this.lastLocations = new ConcurrentHashMap<>();
        
        // Initialize LuckPerms
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            this.luckPerms = provider.getProvider();
            plugin.getLogger().info("LuckPerms integration enabled for jail system");
        } else {
            plugin.getLogger().warning("LuckPerms not found, jail system will use fallback method");
        }
        
        // Register events
        Bukkit.getPluginManager().registerEvents(this, plugin);
        
        // Initialize database tables
        initializeDatabaseTables();
        
        // Load jails from database
        loadJails();
        
        // Load jailed players
        loadJailedPlayers();
        
        // Start jail timer task
        startJailTimerTask();
    }

    private void initializeDatabaseTables() {
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Create jails table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS jails (
                    name VARCHAR(32) PRIMARY KEY,
                    world VARCHAR(64) NOT NULL,
                    x DOUBLE NOT NULL,
                    y DOUBLE NOT NULL,
                    z DOUBLE NOT NULL,
                    yaw FLOAT NOT NULL,
                    pitch FLOAT NOT NULL,
                    created_by VARCHAR(36) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            
            // Create jailed_players table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS jailed_players (
                    uuid VARCHAR(36) PRIMARY KEY,
                    jail_name VARCHAR(32) NOT NULL,
                    jailed_by VARCHAR(36) NOT NULL,
                    reason TEXT,
                    jail_time BIGINT NOT NULL,
                    remaining_time BIGINT NOT NULL,
                    jailed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (jail_name) REFERENCES jails(name) ON DELETE CASCADE
                )
            """);
            
            plugin.getLogger().info(localeManager.getMessage("jail.info.initialized"));
        } catch (SQLException e) {
            DebugLogger.severe("JailManager", localeManager.getMessage("jail.error.initialize_tables"), e);
        }
    }
    
    private void loadJails() {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM jails")) {
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString("name");
                String worldName = rs.getString("world");
                double x = rs.getDouble("x");
                double y = rs.getDouble("y");
                double z = rs.getDouble("z");
                float yaw = rs.getFloat("yaw");
                float pitch = rs.getFloat("pitch");
                String createdBy = rs.getString("created_by");
                
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    JailLocation jail = new JailLocation(
                        name,
                        new Location(world, x, y, z, yaw, pitch),
                        createdBy
                    );
                    jails.put(name.toLowerCase(), jail);
                } else {
                    plugin.getLogger().warning(localeManager.getMessage("jail.error.world_not_found", name, worldName));
                }
            }
            
            plugin.getLogger().info(localeManager.getMessage("jail.info.loaded_jails", String.valueOf(jails.size())));
        } catch (SQLException e) {
            DebugLogger.severe("JailManager", localeManager.getMessage("jail.error.load_jails"), e);
        }
    }
    
    private void loadJailedPlayers() {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM jailed_players")) {
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                UUID playerUuid = UUID.fromString(rs.getString("uuid"));
                String jailName = rs.getString("jail_name");
                UUID jailedBy = UUID.fromString(rs.getString("jailed_by"));
                String reason = rs.getString("reason");
                long jailTime = rs.getLong("jail_time");
                long remainingTime = rs.getLong("remaining_time");
                Timestamp jailedAt = rs.getTimestamp("jailed_at");
                
                JailData jailData = new JailData(
                    playerUuid,
                    jailName,
                    jailedBy,
                    reason,
                    jailTime,
                    remainingTime,
                    jailedAt.toInstant()
                );
                
                jailedPlayers.put(playerUuid, jailData);
            }
            
            plugin.getLogger().info(localeManager.getMessage("jail.info.loaded_players", String.valueOf(jailedPlayers.size())));
        } catch (SQLException e) {
            DebugLogger.severe("JailManager", localeManager.getMessage("jail.error.load_players"), e);
        }
    }
    
    private void startJailTimerTask() {
        // Cancel any existing task
        if (jailTimerTask != null) {
            jailTimerTask.cancel();
        }
        
        // Run every minute
        jailTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Process all online jailed players
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                JailData jailData = jailedPlayers.get(uuid);
                
                if (jailData != null) {
                    // Decrease remaining time by 1 minute
                    long newRemainingTime = jailData.remainingTime - TimeUnit.MINUTES.toMillis(1);
                    
                    if (newRemainingTime <= 0) {
                        // Player has served their time, release them
                        unjailPlayer(uuid);
                        player.sendMessage(localeManager.getComponent("jail.player.released_time_served"));
                    } else {
                        // Update remaining time
                        jailData.remainingTime = newRemainingTime;
                        updateJailDataInDatabase(jailData);
                        
                        // Every 5 minutes, show a message
                        if (newRemainingTime % TimeUnit.MINUTES.toMillis(5) == 0) {
                            long minutes = TimeUnit.MILLISECONDS.toMinutes(newRemainingTime);
                            player.sendMessage(localeManager.getComponent("jail.player.time_remaining", String.valueOf(minutes)));
                        }
                    }
                }
            }
        }, 1200L, 1200L); // 60 seconds (20 ticks per second)
    }
    
    /**
     * Creates a new jail at the specified location.
     * 
     * @param name The name of the jail
     * @param location The location of the jail
     * @param createdBy UUID of the player who created the jail
     * @return true if the jail was created, false if a jail with that name already exists
     */
    public boolean createJail(@NotNull String name, @NotNull Location location, @NotNull UUID createdBy) {
        String lowerName = name.toLowerCase();
        
        // Check if jail already exists
        if (jails.containsKey(lowerName)) {
            return false;
        }
        
        // Create jail object
        JailLocation jail = new JailLocation(name, location, createdBy.toString());
        
        // Save to database
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO jails (name, world, x, y, z, yaw, pitch, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
             """)) {
            
            stmt.setString(1, name);
            stmt.setString(2, location.getWorld().getName());
            stmt.setDouble(3, location.getX());
            stmt.setDouble(4, location.getY());
            stmt.setDouble(5, location.getZ());
            stmt.setFloat(6, location.getYaw());
            stmt.setFloat(7, location.getPitch());
            stmt.setString(8, createdBy.toString());
            
            stmt.executeUpdate();
            
            // Add to cache
            jails.put(lowerName, jail);
            
            return true;
        } catch (SQLException e) {
            DebugLogger.severe("JailManager", localeManager.getComponent("jail.error.create_jail", name).toString(), e);
            return false;
        }
    }
    
    /**
     * Deletes a jail.
     * 
     * @param name The name of the jail to delete
     * @return true if the jail was deleted, false if no jail with that name exists
     */
    public boolean deleteJail(@NotNull String name) {
        String lowerName = name.toLowerCase();
        
        // Check if jail exists
        if (!jails.containsKey(lowerName)) {
            return false;
        }
        
        // Delete from database
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM jails WHERE name = ?")) {
            
            stmt.setString(1, name);
            stmt.executeUpdate();
            
            // Remove from cache
            jails.remove(lowerName);
            
            return true;
        } catch (SQLException e) {
            DebugLogger.severe("JailManager", localeManager.getComponent("jail.error.delete_jail", name).toString(), e);
            return false;
        }
    }
    
    /**
     * Jails a player for a specified time.
     * 
     * @param playerUuid UUID of the player to jail
     * @param jailName Name of the jail to send the player to
     * @param jailedBy UUID of the player who jailed the player
     * @param reason Reason for jailing
     * @param time Time in milliseconds
     * @return true if the player was jailed, false if the jail doesn't exist or the player is already jailed
     */
    public boolean jailPlayer(@NotNull UUID playerUuid, @NotNull String jailName, @NotNull UUID jailedBy, 
                               @Nullable String reason, long time) {
        String lowerJailName = jailName.toLowerCase();
        
        // Check if jail exists
        JailLocation jail = jails.get(lowerJailName);
        if (jail == null) {
            return false;
        }
        
        // Check if player is already jailed
        if (jailedPlayers.containsKey(playerUuid)) {
            return false;
        }
        
        // Create jail data
        JailData jailData = new JailData(
            playerUuid,
            jailName,
            jailedBy,
            reason != null ? reason : "No reason provided",
            time,
            time,
            java.time.Instant.now()
        );
        
        // Save to database
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO jailed_players (uuid, jail_name, jailed_by, reason, jail_time, remaining_time)
                VALUES (?, ?, ?, ?, ?, ?)
             """)) {
            
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, jailName);
            stmt.setString(3, jailedBy.toString());
            stmt.setString(4, jailData.reason);
            stmt.setLong(5, time);
            stmt.setLong(6, time);
            
            stmt.executeUpdate();
            
            // Add to cache
            jailedPlayers.put(playerUuid, jailData);
            
            // Save inventory and teleport player if online
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                // Save inventory and location
                savedInventories.put(playerUuid, player.getInventory().getContents());
                lastLocations.put(playerUuid, player.getLocation());
                
                // Clear inventory and teleport
                player.getInventory().clear();
                player.teleport(jail.location);
                
                // Set jail permissions
                setJailPermissions(player);
                
                player.sendMessage(localeManager.getComponent("jail.player.jailed", formatTime(time)));
                if (reason != null) {
                    player.sendMessage(localeManager.getComponent("jail.player.jailed_reason", reason));
                }
            }
            
            return true;
        } catch (SQLException e) {
            DebugLogger.severe("JailManager", localeManager.getComponent("jail.error.jail_player", playerUuid.toString()).toString(), e);
            return false;
        }
    }
    
    /**
     * Unjails a player.
     * 
     * @param playerUuid UUID of the player to unjail
     * @return true if the player was unjailed, false if the player wasn't jailed
     */
    public boolean unjailPlayer(@NotNull UUID playerUuid) {
        // Check if player is jailed
        if (!jailedPlayers.containsKey(playerUuid)) {
            return false;
        }
        
        // Delete from database
        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM jailed_players WHERE uuid = ?")) {
                
                stmt.setString(1, playerUuid.toString());
                stmt.executeUpdate();
                
                // Remove from cache
                jailedPlayers.remove(playerUuid);
                
                // Restore player state if online
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null && player.isOnline()) {
                    // Restore inventory if saved
                    ItemStack[] savedInventory = savedInventories.remove(playerUuid);
                    if (savedInventory != null) {
                        player.getInventory().setContents(savedInventory);
                    }
                    
                    // Teleport to spawn
                    World world = player.getWorld();
                    Location spawnLocation = world.getSpawnLocation();
                    player.teleport(spawnLocation);
                    
                    // Update player permissions and game mode
                    player.setGameMode(Bukkit.getDefaultGameMode());
                    
                    // Reset player flags
                    player.setCanPickupItems(true);
                    player.setInvulnerable(false);
                    
                    // Remove jail permissions
                    removeJailPermissions(player);
                    
                    // Send message to player
                    player.sendMessage(localeManager.getComponent("jail.player.released"));
                }
                
                return true;
            }
        } catch (SQLException e) {
            DebugLogger.severe("JailManager", localeManager.getComponent("jail.error.unjail_player", playerUuid.toString()).toString(), e);
            return false;
        }
    }
    
    /**
     * Checks if a player is jailed.
     * 
     * @param playerUuid UUID of the player to check
     * @return true if the player is jailed, false otherwise
     */
    public boolean isPlayerJailed(@NotNull UUID playerUuid) {
        return jailedPlayers.containsKey(playerUuid);
    }
    
    /**
     * Gets jail data for a player.
     * 
     * @param playerUuid UUID of the player to get jail data for
     * @return JailData if the player is jailed, null otherwise
     */
    @Nullable
    public JailData getJailData(@NotNull UUID playerUuid) {
        return jailedPlayers.get(playerUuid);
    }
    
    /**
     * Gets a list of all jail names.
     * 
     * @return List of jail names
     */
    @NotNull
    public List<String> getJailNames() {
        return new ArrayList<>(jails.keySet());
    }
    
    /**
     * Gets a jail location by name.
     * 
     * @param name Name of the jail
     * @return JailLocation if the jail exists, null otherwise
     */
    @Nullable
    public JailLocation getJail(@NotNull String name) {
        return jails.get(name.toLowerCase());
    }
    
    private void updateJailDataInDatabase(JailData jailData) {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("""
                UPDATE jailed_players SET remaining_time = ? WHERE uuid = ?
             """)) {
            
            stmt.setLong(1, jailData.remainingTime);
            stmt.setString(2, jailData.playerUuid.toString());
            
            stmt.executeUpdate();
        } catch (SQLException e) {
            DebugLogger.severe("JailManager", localeManager.getComponent("jail.error.update_data", jailData.playerUuid.toString()).toString(), e);
        }
    }
    
    private void setJailPermissions(Player player) {
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                // Create a negative permission node for all permissions
                Node node = Node.builder("*")
                    .value(false) // negative permission
                    .expiry(1, TimeUnit.DAYS) // fallback expiry in case unjail fails
                    .build();
                
                // Add the jail permission node
                Node jailNode = Node.builder(JAIL_PERMISSION)
                    .value(true)
                    .build();
                
                // Add the nodes to the user
                user.data().add(node);
                user.data().add(jailNode);
                
                // Save the changes
                luckPerms.getUserManager().saveUser(user);
            }
        }
    }

    private void removeJailPermissions(Player player) {
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                // Remove all nodes added by the jail system
                user.data().clear(node -> node.getKey().equals("*") || node.getKey().equals(JAIL_PERMISSION));
                
                // Save the changes
                luckPerms.getUserManager().saveUser(user);
                
                // Force permission recalculation
                user.getCachedData().invalidate();
                luckPerms.getContextManager().signalContextUpdate(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Check if player is jailed
        JailData jailData = jailedPlayers.get(uuid);
        if (jailData != null) {
            // Get jail location
            JailLocation jail = jails.get(jailData.jailName.toLowerCase());
            if (jail != null) {
                // Set jail permissions
                setJailPermissions(player);
                
                // Teleport player to jail
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.teleport(jail.location);
                    player.sendMessage(localeManager.getComponent("jail.player.jailed", formatTime(jailData.remainingTime)));
                    if (jailData.reason != null) {
                        player.sendMessage(localeManager.getComponent("jail.player.jailed_reason", jailData.reason));
                    }
                }, 20L); // 1 second delay to ensure player is fully loaded
            }
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Check if player is jailed
        JailData jailData = jailedPlayers.get(uuid);
        if (jailData != null) {
            // Save remaining time to database
            updateJailDataInDatabase(jailData);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isPlayerJailed(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(localeManager.getComponent("jail.player.no_break"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (isPlayerJailed(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(localeManager.getComponent("jail.player.no_place"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isPlayerJailed(player.getUniqueId())) {
            String command = event.getMessage().toLowerCase();
            // Allow only basic commands while jailed
            if (!command.startsWith("/rules") && !command.startsWith("/help") &&
                !command.startsWith("/msg") && !command.startsWith("/tell") &&
                !command.startsWith("/r ") && !command.startsWith("/reply")) {
                event.setCancelled(true);
                player.sendMessage(localeManager.getComponent("jail.player.no_command"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (isPlayerJailed(player.getUniqueId())) {
            // Only allow teleports within the same jail area
            JailData jailData = jailedPlayers.get(player.getUniqueId());
            JailLocation jail = jails.get(jailData.getJailName().toLowerCase());
            
            if (jail != null && event.getTo() != null) {
                Location jailLoc = jail.getLocation();
                Location targetLoc = event.getTo();
                
                // Allow teleport only if within 10 blocks of jail location
                if (jailLoc.getWorld() == targetLoc.getWorld() && 
                    jailLoc.distance(targetLoc) <= 10) {
                    return;
                }
            }
            
            event.setCancelled(true);
            player.sendMessage(localeManager.getComponent("jail.player.no_teleport"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Check if the attacker is jailed
        if (event.getDamager() instanceof Player attacker) {
            if (isPlayerJailed(attacker.getUniqueId())) {
                event.setCancelled(true);
                attacker.sendMessage(localeManager.getComponent("jail.player.no_damage"));
                return;
            }
        }
        
        // Check if the victim is jailed
        if (event.getEntity() instanceof Player victim) {
            if (isPlayerJailed(victim.getUniqueId())) {
                event.setCancelled(true);
                if (event.getDamager() instanceof Player attacker) {
                    attacker.sendMessage(localeManager.getComponent("jail.player.no_damage_jailed"));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isPlayerJailed(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(localeManager.getComponent("jail.player.no_interact"));
        }
    }
    
    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (isPlayerJailed(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(localeManager.getComponent("jail.player.no_chat"));
        }
    }
    
    /**
     * Formats time in milliseconds to a human-readable string.
     * 
     * @param millis Time in milliseconds
     * @return Formatted time string
     */
    private String formatTime(long millis) {
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        millis -= TimeUnit.DAYS.toMillis(days);
        
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        millis -= TimeUnit.HOURS.toMillis(hours);
        
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        millis -= TimeUnit.MINUTES.toMillis(minutes);
        
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" day").append(days > 1 ? "s" : "").append(" ");
        }
        if (hours > 0) {
            sb.append(hours).append(" hour").append(hours > 1 ? "s" : "").append(" ");
        }
        if (minutes > 0) {
            sb.append(minutes).append(" minute").append(minutes > 1 ? "s" : "").append(" ");
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append(" second").append(seconds != 1 ? "s" : "");
        } else {
            // Remove trailing space
            sb.setLength(sb.length() - 1);
        }
        
        return sb.toString();
    }
    
    public void shutdown() {
        if (jailTimerTask != null) {
            jailTimerTask.cancel();
        }
        
        // Save all jailed players
        for (JailData jailData : jailedPlayers.values()) {
            updateJailDataInDatabase(jailData);
        }
    }
    
    /**
     * Represents a jail location.
     */
    public static class JailLocation {
        private final String name;
        private final Location location;
        private final String createdBy;
        
        public JailLocation(String name, Location location, String createdBy) {
            this.name = name;
            this.location = location;
            this.createdBy = createdBy;
        }
        
        public String getName() {
            return name;
        }
        
        public Location getLocation() {
            return location;
        }
        
        public String getCreatedBy() {
            return createdBy;
        }
    }
    
    /**
     * Represents jail data for a player.
     */
    public static class JailData {
        private final UUID playerUuid;
        private final String jailName;
        private final UUID jailedBy;
        private final String reason;
        private final long jailTime;
        private long remainingTime;
        private final java.time.Instant jailedAt;
        
        public JailData(UUID playerUuid, String jailName, UUID jailedBy, String reason, 
                        long jailTime, long remainingTime, java.time.Instant jailedAt) {
            this.playerUuid = playerUuid;
            this.jailName = jailName;
            this.jailedBy = jailedBy;
            this.reason = reason;
            this.jailTime = jailTime;
            this.remainingTime = remainingTime;
            this.jailedAt = jailedAt;
        }
        
        public UUID getPlayerUuid() {
            return playerUuid;
        }
        
        public String getJailName() {
            return jailName;
        }
        
        public UUID getJailedBy() {
            return jailedBy;
        }
        
        public String getReason() {
            return reason;
        }
        
        public long getJailTime() {
            return jailTime;
        }
        
        public long getRemainingTime() {
            return remainingTime;
        }
        
        public java.time.Instant getJailedAt() {
            return jailedAt;
        }
    }
} 