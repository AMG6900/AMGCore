package amg.plugins.aMGCore.tasks;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.models.PlayerData;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AutoSaveTask extends BukkitRunnable {
    private final AMGCore plugin;
    private final AtomicLong lastFullSaveTime = new AtomicLong(0);
    private final AtomicInteger saveCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Long> lastPlayerSaveTimes = new ConcurrentHashMap<>();
    
    // Performance metrics
    private final AtomicLong totalSaveTime = new AtomicLong(0);
    private final AtomicInteger totalSaveCount = new AtomicInteger(0);
    private final AtomicInteger totalPlayersSaved = new AtomicInteger(0);
    
    // Configuration
    private static final long FULL_SAVE_INTERVAL = 15 * 60 * 1000; // 15 minutes
    private static final int PLAYERS_PER_BATCH = 5; // Number of players to save per batch
    private static final long PLAYER_SAVE_INTERVAL = 5 * 60 * 1000; // 5 minutes

    public AutoSaveTask(@NotNull AMGCore plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
    }

    @Override
    public void run() {
        try {
            long startTime = System.currentTimeMillis();
            Collection<? extends Player> onlinePlayers = plugin.getServer().getOnlinePlayers();
            
            if (onlinePlayers.isEmpty()) {
                // No players online, nothing to save
                if (plugin.isDebugEnabled()) {
                    DebugLogger.debug("AutoSaveTask", "No players online, skipping auto-save");
                }
                return;
            }
            
            // Determine if we should do a full save
            boolean fullSave = false;
            long now = System.currentTimeMillis();
            long lastFullSave = lastFullSaveTime.get();
            
            if (now - lastFullSave >= FULL_SAVE_INTERVAL) {
                fullSave = true;
                lastFullSaveTime.set(now);
                
                if (plugin.isDebugEnabled()) {
                    DebugLogger.debug("AutoSaveTask", "Performing full save of all player data");
                }
            }
            
            if (fullSave) {
                // Update locations for all online players before saving
                for (Player player : onlinePlayers) {
                    try {
                        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
                        if (data != null) {
                            data.updateLocation(player.getLocation());
                        }
                    } catch (Exception e) {
                        DebugLogger.severe("AutoSaveTask", "Failed to update location for player: " + player.getName(), e);
                    }
                }
                
                // Save all player data
                int savedCount = plugin.getPlayerDataManager().saveAllPlayers();
                totalPlayersSaved.addAndGet(savedCount);
                
                if (plugin.getConfig().getBoolean("logging.auto_save", true)) {
                    plugin.getLogger().info("Full auto-save completed: Saved " + savedCount + " players");
                }
            } else {
                // Staggered save - prioritize players who haven't been saved recently
                List<Player> playersToSave = new ArrayList<>();
                
                // First, collect players who need saving
                for (Player player : onlinePlayers) {
                    String uuid = player.getUniqueId().toString();
                    Long lastSaveTime = lastPlayerSaveTimes.get(uuid);
                    
                    // Save if player hasn't been saved recently or is dirty
                    if (lastSaveTime == null || now - lastSaveTime >= PLAYER_SAVE_INTERVAL || 
                            plugin.getPlayerDataManager().isDirty(player)) {
                        playersToSave.add(player);
                    }
                }
                
                // Limit the number of players to save in this batch
                int batchSize = Math.min(PLAYERS_PER_BATCH, playersToSave.size());
                int savedCount = 0;
                
                // Save only a subset of players
                for (int i = 0; i < batchSize; i++) {
                    Player player = playersToSave.get(i);
                    try {
                        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
                        if (data != null) {
                            // Update location
                            data.updateLocation(player.getLocation());
                            
                            // Save player data
                            plugin.getPlayerDataManager().savePlayer(player);
                            lastPlayerSaveTimes.put(player.getUniqueId().toString(), now);
                            savedCount++;
                        }
                    } catch (Exception e) {
                        DebugLogger.severe("AutoSaveTask", "Failed to save player: " + player.getName(), e);
                    }
                }
                
                totalPlayersSaved.addAndGet(savedCount);
                
                if (plugin.isDebugEnabled() && savedCount > 0) {
                    DebugLogger.debug("AutoSaveTask", "Staggered save completed: Saved " + savedCount + 
                            " players (batch " + saveCounter.incrementAndGet() + ")");
                }
            }
            
            // Update performance metrics
            long saveTime = System.currentTimeMillis() - startTime;
            totalSaveTime.addAndGet(saveTime);
            totalSaveCount.incrementAndGet();
            
            if (plugin.isDebugEnabled()) {
                DebugLogger.debug("AutoSaveTask", "Auto-save completed in " + saveTime + "ms");
            }
        } catch (Exception e) {
            DebugLogger.severe("AutoSaveTask", "Error during auto-save", e);
            plugin.getLogger().severe("Error during auto-save: " + e.getMessage());
        }
    }
    
    /**
     * Get performance metrics for the auto-save task
     * 
     * @return A string containing performance metrics
     */
    public String getPerformanceMetrics() {
        int saveCount = totalSaveCount.get();
        long totalTime = totalSaveTime.get();
        int playersSaved = totalPlayersSaved.get();
        double avgTime = saveCount > 0 ? (double) totalTime / saveCount : 0;
        double avgPlayersPerSave = saveCount > 0 ? (double) playersSaved / saveCount : 0;
        
        return String.format(
            "AutoSave: %d runs, %.2fms avg, %.1f players/save, %d total players saved",
            saveCount, avgTime, avgPlayersPerSave, playersSaved
        );
    }
    
    /**
     * Reset performance metrics
     */
    public void resetMetrics() {
        totalSaveTime.set(0);
        totalSaveCount.set(0);
        totalPlayersSaved.set(0);
    }
    
    /**
     * Check if a player is dirty (needs saving)
     * 
     * @param player The player to check
     * @return True if the player is dirty, false otherwise
     */
    public boolean isPlayerDirty(Player player) {
        String uuid = player.getUniqueId().toString();
        Long lastSaveTime = lastPlayerSaveTimes.get(uuid);
        return lastSaveTime == null || System.currentTimeMillis() - lastSaveTime >= PLAYER_SAVE_INTERVAL;
    }
} 