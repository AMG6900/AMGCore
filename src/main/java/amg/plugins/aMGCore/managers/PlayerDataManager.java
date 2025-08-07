package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.models.PlayerData;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class PlayerDataManager {
    private final Map<String, PlayerData> playerDataCache;
    private final Map<String, ReentrantLock> playerLocks;
    private final Map<String, Long> lastSaveTime;
    private final Map<String, Long> lastAccessTime;
    private final Set<String> dirtyPlayers;
    private final DatabaseManager databaseManager;
    private static final long SAVE_COOLDOWN = 50L; // 50ms cooldown between saves
    private static final long CACHE_EXPIRY_TIME = 10 * 60 * 1000L; // 10 minutes
    private static final Object CACHE_LOCK = new Object();
    private final ScheduledExecutorService cacheCleanupExecutor;
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);
    
    // Cache size limits
    private static final int MAX_CACHE_SIZE = 1000;
    private static final int CACHE_TRIM_SIZE = 800;
    private static final int BATCH_SAVE_SIZE = 50;

    public PlayerDataManager(AMGCore plugin) {
        this.playerDataCache = new ConcurrentHashMap<>();
        this.playerLocks = new ConcurrentHashMap<>();
        this.lastSaveTime = new ConcurrentHashMap<>();
        this.lastAccessTime = new ConcurrentHashMap<>();
        this.dirtyPlayers = ConcurrentHashMap.newKeySet();
        this.databaseManager = new DatabaseManager(plugin);
        
        // Initialize cache cleanup executor
        this.cacheCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "AMGCore-CacheCleanup");
            thread.setDaemon(true);
            return thread;
        });
        
        // Schedule cache cleanup task - run more frequently
        this.cacheCleanupExecutor.scheduleAtFixedRate(
            this::cleanupCache,
            CACHE_EXPIRY_TIME / 4,  // Run more frequently
            CACHE_EXPIRY_TIME / 4,
            TimeUnit.MILLISECONDS
        );
        
        // Schedule periodic batch save - run more frequently for better responsiveness
        this.cacheCleanupExecutor.scheduleAtFixedRate(
            this::batchSaveDirtyPlayers,
            15,  // Run every 15 seconds
            15,
            TimeUnit.SECONDS
        );
        
        // Schedule memory optimization task
        this.cacheCleanupExecutor.scheduleAtFixedRate(
            this::optimizeMemory,
            60,  // Run every minute
            60,
            TimeUnit.SECONDS
        );
    }

    private ReentrantLock getPlayerLock(String uuid) {
        return playerLocks.computeIfAbsent(uuid, k -> new ReentrantLock(true));
    }

    @Nullable
    public PlayerData loadPlayer(Player player) {
        return loadPlayer(player, true);
    }

    @Nullable
    private PlayerData loadPlayer(Player player, boolean useCache) {
        String uuid = player.getUniqueId().toString();
        ReentrantLock lock = getPlayerLock(uuid);
        lock.lock();
        
        try {
            // Check cache first if using cache
            if (useCache) {
                PlayerData cachedData = playerDataCache.get(uuid);
                if (cachedData != null && uuid.equals(cachedData.getUuid())) {
                    // Update last access time
                    lastAccessTime.put(uuid, System.currentTimeMillis());
                    cacheHits.incrementAndGet();
                    return cachedData;
                }
                cacheMisses.incrementAndGet();
            }

            // Check cache size before loading new data
            checkCacheSize();
            
            // Load from database
            PlayerData data = databaseManager.loadPlayerData(player.getUniqueId(), player.getName());
            if (data != null) {
                setupDataChangeHandler(player, data);
                
                if (useCache) {
                    // Update cache and timestamps
                    playerDataCache.put(uuid, data);
                    lastAccessTime.put(uuid, System.currentTimeMillis());
                }
                
                // Update IP address
                data.updateIp(player.getAddress().getAddress().getHostAddress());
                
                return data;
            }
            
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if the cache size exceeds the limit and trim if necessary
     */
    private void checkCacheSize() {
        if (playerDataCache.size() > MAX_CACHE_SIZE) {
            DebugLogger.debug("PlayerDataManager", "Cache size exceeds limit, trimming cache");
            trimCache();
        }
    }
    
    /**
     * Optimize memory usage by trimming the cache and releasing unused resources
     */
    private void trimCache() {
        synchronized (CACHE_LOCK) {
            if (playerDataCache.size() <= CACHE_TRIM_SIZE) {
                return; // Cache is already below trim threshold
            }
            
            DebugLogger.debug("PlayerDataManager", "Optimizing memory usage, current cache size: " + playerDataCache.size());
            
            // Sort players by last access time
            List<Map.Entry<String, Long>> sortedEntries = new ArrayList<>(lastAccessTime.entrySet());
            sortedEntries.sort(Map.Entry.comparingByValue());
            
            // Calculate how many entries to remove
            int toRemove = playerDataCache.size() - CACHE_TRIM_SIZE;
            int removed = 0;
            
            // Remove oldest entries that aren't dirty
            for (Map.Entry<String, Long> entry : sortedEntries) {
                String uuid = entry.getKey();
                
                // Skip dirty players
                if (dirtyPlayers.contains(uuid)) {
                    continue;
                }
                
                // Get lock for player
                ReentrantLock lock = getPlayerLock(uuid);
                if (lock.tryLock()) {
                    try {
                        // Remove from cache
                        playerDataCache.remove(uuid);
                        lastAccessTime.remove(uuid);
                        lastSaveTime.remove(uuid);
                        
                        removed++;
                        if (removed >= toRemove) {
                            break;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
            
            // Clean up locks for players no longer in cache
            List<String> locksToRemove = new ArrayList<>();
            for (String uuid : playerLocks.keySet()) {
                if (!playerDataCache.containsKey(uuid)) {
                    locksToRemove.add(uuid);
                }
            }
            
            for (String uuid : locksToRemove) {
                playerLocks.remove(uuid);
            }
            
            // Save dirty players to reduce memory pressure
            batchSaveDirtyPlayers();
            
            // Suggest garbage collection
            System.gc();
            
            DebugLogger.debug("PlayerDataManager", "Memory optimization complete, removed " + 
                             removed + " entries, new cache size: " + playerDataCache.size() + 
                             ", " + getCacheStatistics());
        }
    }
    
    /**
     * Public method to optimize memory usage
     */
    public void optimizeMemory() {
        // Clean up cache
        cleanupCache();
        
        // Perform internal optimization
        trimCache();
    }

    private void setupDataChangeHandler(Player player, PlayerData data) {
        // Set up data change handler
        data.setOnDataChanged(() -> {
            String uuid = player.getUniqueId().toString();
            markDirty(uuid);
        });
    }

    private void markDirty(String uuid) {
        dirtyPlayers.add(uuid);
        lastAccessTime.put(uuid, System.currentTimeMillis());
    }

    /**
     * Save all dirty players in batches for better performance
     */
    private void batchSaveDirtyPlayers() {
        try {
            if (dirtyPlayers.isEmpty()) {
                return;
            }
            
            int totalSaved = 0;
            int batchCount = 0;
            long now = System.currentTimeMillis();
            List<String> toSave = new ArrayList<>(dirtyPlayers);
            List<String> saved = new ArrayList<>();
            
            DebugLogger.debug("PlayerDataManager", "Starting batch save for " + toSave.size() + " dirty players");
            
            // Process in batches
            for (int i = 0; i < toSave.size(); i++) {
                String uuid = toSave.get(i);
                
                // Skip if saved recently
                Long lastSave = lastSaveTime.get(uuid);
                if (lastSave != null && (now - lastSave) < SAVE_COOLDOWN) {
                    continue;
                }
                
                // Get player data
                PlayerData data = playerDataCache.get(uuid);
                if (data == null) {
                    saved.add(uuid);
                    continue;
                }
                
                // Save player data
                ReentrantLock lock = getPlayerLock(uuid);
                if (lock.tryLock()) {
                    try {
                        databaseManager.savePlayerData(data);
                        lastSaveTime.put(uuid, now);
                        saved.add(uuid);
                        totalSaved++;
                        
                        // Check if we need to commit the batch
                        if (totalSaved % BATCH_SAVE_SIZE == 0) {
                            batchCount++;
                        }
                    } catch (Exception e) {
                        DebugLogger.severe("PlayerDataManager", "Error saving player data for " + uuid, e);
                    } finally {
                        lock.unlock();
                    }
                }
            }
            
            // Remove saved players from dirty list
            dirtyPlayers.removeAll(saved);
            
            if (totalSaved > 0) {
                DebugLogger.debug("PlayerDataManager", "Batch save complete: saved " + totalSaved + 
                                 " players in " + (batchCount + 1) + " batches, " + 
                                 dirtyPlayers.size() + " players still dirty");
            }
        } catch (Exception e) {
            DebugLogger.severe("PlayerDataManager", "Error during batch save", e);
        }
    }

    public void savePlayer(@NotNull Player player) {
        String uuid = player.getUniqueId().toString();
        ReentrantLock lock = getPlayerLock(uuid);
        lock.lock();
        
        try {
            PlayerData data = playerDataCache.get(uuid);
            if (data == null) {
                // Load player data if not in cache
                data = loadPlayer(player, false);
                if (data == null) {
                    return;
                }
            }
            
            // Update IP address
            data.updateIp(player.getAddress().getAddress().getHostAddress());
            
            // Save to database
            databaseManager.savePlayerData(data);
            
            // Update timestamps
            long now = System.currentTimeMillis();
            lastSaveTime.put(uuid, now);
            lastAccessTime.put(uuid, now);
            
            // Remove from dirty players
            dirtyPlayers.remove(uuid);
        } finally {
            lock.unlock();
        }
    }

    public void reloadPlayerData(@NotNull Player player) {
        String uuid = player.getUniqueId().toString();
        ReentrantLock lock = getPlayerLock(uuid);
        lock.lock();
        
        try {
            // Remove from cache
            playerDataCache.remove(uuid);
            
            // Load fresh data
            PlayerData data = loadPlayer(player, false);
            if (data != null) {
                // Update cache
                playerDataCache.put(uuid, data);
                
                // Update timestamps
                long now = System.currentTimeMillis();
                lastAccessTime.put(uuid, now);
                
                // Remove from dirty players
                dirtyPlayers.remove(uuid);
            }
        } finally {
            lock.unlock();
        }
    }

    public void unloadPlayer(@NotNull Player player) {
        String uuid = player.getUniqueId().toString();
        ReentrantLock lock = getPlayerLock(uuid);
        lock.lock();
        
        try {
            // Save player data if dirty
            if (dirtyPlayers.contains(uuid)) {
                PlayerData data = playerDataCache.get(uuid);
                if (data != null) {
                    try {
                        databaseManager.savePlayerData(data);
                    } catch (Exception e) {
                        DebugLogger.severe("PlayerDataManager", "Error saving player data during unload for " + player.getName(), e);
                    }
                }
            }
            
            // Remove from all maps
            playerDataCache.remove(uuid);
            lastSaveTime.remove(uuid);
            lastAccessTime.remove(uuid);
            dirtyPlayers.remove(uuid);
            
            // Don't remove the lock yet, it might be in use by other threads
        } catch (Exception e) {
            DebugLogger.severe("PlayerDataManager", "Error unloading player data for " + player.getName(), e);
        } finally {
            lock.unlock();
        }
    }

    public boolean exists(@NotNull Player player) {
        return playerDataCache.containsKey(player.getUniqueId().toString());
    }

    @Nullable
    public PlayerData getPlayerData(@NotNull Player player) {
        String uuid = player.getUniqueId().toString();
        
        // Try to get from cache first
        PlayerData data = playerDataCache.get(uuid);
        if (data != null) {
            // Update last access time
            lastAccessTime.put(uuid, System.currentTimeMillis());
            cacheHits.incrementAndGet();
            return data;
        }
        
        // Load from database if not in cache
        cacheMisses.incrementAndGet();
        return loadPlayer(player);
    }

    /**
     * Save all players currently in the cache
     * 
     * @return The number of players saved
     */
    public int saveAllPlayers() {
        int saved = 0;
        List<String> playerIds = new ArrayList<>(playerDataCache.keySet());
        
        for (String uuid : playerIds) {
            ReentrantLock lock = getPlayerLock(uuid);
            if (lock.tryLock()) {
                try {
                    PlayerData data = playerDataCache.get(uuid);
                    if (data != null) {
                        try {
                            databaseManager.savePlayerData(data);
                            lastSaveTime.put(uuid, System.currentTimeMillis());
                            saved++;
                        } catch (Exception e) {
                            DebugLogger.severe("PlayerDataManager", "Error saving player data for " + uuid, e);
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
        
        // Clear dirty players
        dirtyPlayers.clear();
        
        return saved;
    }

    /**
     * Clean up expired cache entries
     */
    private void cleanupCache() {
        try {
            long now = System.currentTimeMillis();
            List<String> toRemove = new ArrayList<>();
            
            for (Map.Entry<String, Long> entry : lastAccessTime.entrySet()) {
                String uuid = entry.getKey();
                long lastAccess = entry.getValue();
                
                // Skip if dirty
                if (dirtyPlayers.contains(uuid)) {
                    continue;
                }
                
                // Check if expired
                if (now - lastAccess > CACHE_EXPIRY_TIME) {
                    toRemove.add(uuid);
                }
            }
            
            // Remove expired entries
            for (String uuid : toRemove) {
                ReentrantLock lock = getPlayerLock(uuid);
                if (lock.tryLock()) {
                    try {
                        playerDataCache.remove(uuid);
                        lastAccessTime.remove(uuid);
                        lastSaveTime.remove(uuid);
                    } finally {
                        lock.unlock();
                    }
                }
            }
            
            if (!toRemove.isEmpty()) {
                DebugLogger.debug("PlayerDataManager", "Cleaned up " + toRemove.size() + " expired cache entries");
            }
        } catch (Exception e) {
            DebugLogger.severe("PlayerDataManager", "Error during cache cleanup", e);
        }
    }

    /**
     * Get the number of players currently loaded in the cache
     * 
     * @return The number of loaded players
     */
    public int getLoadedPlayerCount() {
        return playerDataCache.size();
    }
    
    /**
     * Get the number of dirty players waiting to be saved
     * 
     * @return The number of dirty players
     */
    public int getDirtyPlayerCount() {
        return dirtyPlayers.size();
    }

    /**
     * Get the cache hit rate
     * 
     * @return The cache hit rate as a percentage
     */
    public double getCacheHitRate() {
        int hits = cacheHits.get();
        int misses = cacheMisses.get();
        int total = hits + misses;
        
        if (total == 0) {
            return 0.0;
        }
        
        return (double) hits / total * 100.0;
    }
    
    /**
     * Get detailed cache statistics
     * 
     * @return A string containing cache statistics
     */
    public String getCacheStatistics() {
        int hits = cacheHits.get();
        int misses = cacheMisses.get();
        int total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100.0 : 0.0;
        
        return String.format(
            "Cache: %d items, %.2f%% hit rate (%d hits, %d misses), %d dirty",
            playerDataCache.size(), hitRate, hits, misses, dirtyPlayers.size()
        );
    }
    
    /**
     * Check if a player's data is marked as dirty and needs to be saved
     * 
     * @param player The player to check
     * @return True if the player's data is dirty, false otherwise
     */
    public boolean isDirty(@NotNull Player player) {
        return dirtyPlayers.contains(player.getUniqueId().toString());
    }
    
    public void close() {
        try {
            // Shutdown executor
            cacheCleanupExecutor.shutdown();
            if (!cacheCleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cacheCleanupExecutor.shutdownNow();
            }
            
            // Save all players
            int saved = saveAllPlayers();
            DebugLogger.debug("PlayerDataManager", "Saved " + saved + " players during shutdown");
            
            // Clear caches
            playerDataCache.clear();
            lastSaveTime.clear();
            lastAccessTime.clear();
            dirtyPlayers.clear();
            
            // Close database manager
            databaseManager.close();
        } catch (Exception e) {
            DebugLogger.severe("PlayerDataManager", "Error closing player data manager", e);
        }
    }
} 