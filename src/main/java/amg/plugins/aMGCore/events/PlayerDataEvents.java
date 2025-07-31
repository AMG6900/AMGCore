package amg.plugins.aMGCore.events;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.models.PlayerData;
import amg.plugins.aMGCore.utils.DebugLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PlayerDataEvents implements Listener {
    private final AMGCore plugin;
    private final Map<String, BukkitTask> locationUpdateTasks;
    private final Map<String, Location> lastKnownLocations;
    private final Queue<LocationUpdate> pendingLocationUpdates;
    private BukkitTask batchUpdateTask;
    private static final long LOCATION_UPDATE_INTERVAL = 20L * 30; // 30 seconds
    private static final long BATCH_UPDATE_INTERVAL = 20L * 5; // 5 seconds
    private static final double SIGNIFICANT_DISTANCE_CHANGE = 5.0; // 5 blocks

    public PlayerDataEvents(@NotNull AMGCore plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.locationUpdateTasks = new ConcurrentHashMap<>();
        this.lastKnownLocations = new ConcurrentHashMap<>();
        this.pendingLocationUpdates = new ConcurrentLinkedQueue<>();
        
        // Start batch update task
        this.batchUpdateTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::processPendingLocationUpdates,
            BATCH_UPDATE_INTERVAL,
            BATCH_UPDATE_INTERVAL
        );
    }
    
    /**
     * Process all pending location updates in a batch
     */
    private void processPendingLocationUpdates() {
        if (pendingLocationUpdates.isEmpty()) {
            return;
        }
        
        int processed = 0;
        int maxBatchSize = 50; // Process up to 50 updates at once
        
        while (!pendingLocationUpdates.isEmpty() && processed < maxBatchSize) {
            LocationUpdate update = pendingLocationUpdates.poll();
            if (update != null) {
                try {
                    Player player = plugin.getServer().getPlayer(update.playerUuid);
                    if (player != null && player.isOnline()) {
                        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
                        if (data != null) {
                            data.updateLocation(update.location);
                            lastKnownLocations.put(update.playerUuid, update.location);
                            
                            // Only save if this is a significant update
                            if (update.significant) {
                                plugin.getPlayerDataManager().savePlayer(player);
                            }
                        }
                    }
                    processed++;
                } catch (Exception e) {
                    DebugLogger.severe("PlayerDataEvents", "Error processing location update", e);
                }
            }
        }
        
        if (processed > 0 && plugin.isDebugEnabled()) {
            DebugLogger.debug("PlayerDataEvents", "Processed " + processed + " location updates");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getConfig();

        try {
            // Check if player is banned
            if (plugin.getBanManager().isPlayerBanned(player)) {
                String banMessage = plugin.getBanManager().getBanMessage(player);
                player.kick(Component.text(banMessage));
                if (config.getBoolean("logging.player_join", true)) {
                    plugin.getLogger().info("Banned player " + player.getName() + " attempted to join");
                }
                return;
            }
    
            // Load and apply player data
            PlayerData data = plugin.getPlayerDataManager().loadPlayer(player);
            if (data == null) {
                player.kick(Component.text("Failed to load player data!").color(NamedTextColor.RED));
                return;
            }
            
            // Update IP address
            String ip = player.getAddress() != null ? player.getAddress().getHostString() : null;
            if (ip != null) {
                data.updateIp(ip);
                plugin.getBanManager().updatePlayerIp(player);
            }
    
            // Handle spawn location
            Location spawnLoc = null;
            if (config.getBoolean("player.use_last_location", true)) {
                spawnLoc = data.getLastLocation();
            }
            
            if (spawnLoc == null || !isValidLocation(spawnLoc)) {
                // Use default spawn if last location is invalid or disabled
                spawnLoc = new Location(
                    player.getWorld(),
                    config.getDouble("player.spawn.x", 0.0),
                    config.getDouble("player.spawn.y", 64.0),
                    config.getDouble("player.spawn.z", 0.0),
                    (float) config.getDouble("player.spawn.yaw", 0.0),
                    (float) config.getDouble("player.spawn.pitch", 0.0)
                );
            }
    
            // Teleport on next tick to ensure chunk is loaded
            final Location finalSpawnLoc = spawnLoc;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    player.teleport(finalSpawnLoc);
                    
                    // Start location update task
                    startLocationUpdateTask(player);
                    
                    // Store initial location
                    lastKnownLocations.put(player.getUniqueId().toString(), player.getLocation());
                } catch (Exception e) {
                    DebugLogger.severe("PlayerDataEvents", "Error teleporting player on join: " + player.getName(), e);
                }
            });
    
            if (config.getBoolean("logging.player_join", true)) {
                plugin.getLogger().info("Player " + player.getName() + " joined (IP: " + ip + ")");
            }
        } catch (Exception e) {
            DebugLogger.severe("PlayerDataEvents", "Error handling player join: " + player.getName(), e);
            player.kick(Component.text("An error occurred while loading your data. Please try again.").color(NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        
        try {
            // Cancel location update task
            stopLocationUpdateTask(player);
            
            // Save final location with high priority
            PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
            if (data != null) {
                data.updateLocation(player.getLocation());
                data.setRequiresImmediateSave(true); // Mark as requiring immediate save
                plugin.getPlayerDataManager().savePlayer(player);
            }
            
            // Clean up cached data
            lastKnownLocations.remove(uuid);
            
            // Remove any pending updates for this player
            pendingLocationUpdates.removeIf(update -> update.playerUuid.equals(uuid));
        } catch (Exception e) {
            DebugLogger.severe("PlayerDataEvents", "Error handling player quit: " + player.getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        // Teleports are significant location changes
        queueLocationUpdate(event.getPlayer(), event.getTo(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // Respawns are significant location changes
        queueLocationUpdate(event.getPlayer(), event.getRespawnLocation(), true);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        // World changes are significant location changes
        queueLocationUpdate(event.getPlayer(), event.getPlayer().getLocation(), true);
    }

    private boolean isValidLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        
        // Check if chunk is loaded or can be loaded
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        return loc.getWorld().isChunkLoaded(chunkX, chunkZ) || 
               loc.getWorld().loadChunk(chunkX, chunkZ, false);
    }

    private void queueLocationUpdate(Player player, Location location, boolean significant) {
        if (player == null || location == null || !player.isOnline()) {
            return;
        }
        
        String uuid = player.getUniqueId().toString();
        
        // Check if this is a significant change from the last known location
        if (!significant && lastKnownLocations.containsKey(uuid)) {
            Location lastLoc = lastKnownLocations.get(uuid);
            if (lastLoc.getWorld().equals(location.getWorld())) {
                double distance = lastLoc.distance(location);
                if (distance < SIGNIFICANT_DISTANCE_CHANGE) {
                    // Not a significant change, don't queue
                    return;
                }
            }
        }
        
        // Queue the update
        pendingLocationUpdates.offer(new LocationUpdate(uuid, location.clone(), significant));
        
        // Update the last known location
        lastKnownLocations.put(uuid, location.clone());
    }

    private void startLocationUpdateTask(Player player) {
        String uuid = player.getUniqueId().toString();
        stopLocationUpdateTask(player); // Cancel any existing task
        
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> {
                if (player.isOnline()) {
                    // Queue a regular location update
                    queueLocationUpdate(player, player.getLocation(), false);
                } else {
                    stopLocationUpdateTask(player);
                }
            },
            LOCATION_UPDATE_INTERVAL,
            LOCATION_UPDATE_INTERVAL
        );
        
        locationUpdateTasks.put(uuid, task);
    }

    private void stopLocationUpdateTask(Player player) {
        String uuid = player.getUniqueId().toString();
        BukkitTask task = locationUpdateTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        try {
            Player player = event.getPlayer();
            Location loc = player.getLocation();
            String locationStr = String.format("%s,%d,%d,%d", 
                loc.getWorld().getName(), 
                loc.getBlockX(), 
                loc.getBlockY(), 
                loc.getBlockZ()
            );
            
            plugin.getLogManager().logItemTransaction(
                player.getName(),
                "DROPPED",
                event.getItemDrop().getItemStack().getType().name(),
                event.getItemDrop().getItemStack().getAmount(),
                locationStr
            );
        } catch (Exception e) {
            DebugLogger.severe("PlayerDataEvents", "Error logging item drop", e);
        }
    }
    
    /**
     * Cleans up resources when the plugin is disabled
     */
    public void shutdown() {
        // Process any remaining location updates
        processPendingLocationUpdates();
        
        // Cancel all tasks
        for (BukkitTask task : locationUpdateTasks.values()) {
            task.cancel();
        }
        locationUpdateTasks.clear();
        
        if (batchUpdateTask != null) {
            batchUpdateTask.cancel();
            batchUpdateTask = null;
        }
        
        // Clear caches
        lastKnownLocations.clear();
        pendingLocationUpdates.clear();
    }
    
    /**
     * Class representing a location update for a player
     */
    private static class LocationUpdate {
        final String playerUuid;
        final Location location;
        final boolean significant;
        
        LocationUpdate(String playerUuid, Location location, boolean significant) {
            this.playerUuid = playerUuid;
            this.location = location;
            this.significant = significant;
        }
    }
} 