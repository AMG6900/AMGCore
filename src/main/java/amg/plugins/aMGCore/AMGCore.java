package amg.plugins.aMGCore;

import org.bstats.bukkit.Metrics;
import amg.plugins.aMGCore.api.CoreAPI;
import amg.plugins.aMGCore.api.ModuleRegistry;
import amg.plugins.aMGCore.commands.CoreCommand;
import amg.plugins.aMGCore.commands.CoreDebugCommand;
import amg.plugins.aMGCore.commands.EconomyCommands;
import amg.plugins.aMGCore.commands.InventoryCommand;
import amg.plugins.aMGCore.commands.JailCommands;
import amg.plugins.aMGCore.commands.ModeratorCommands;
import amg.plugins.aMGCore.commands.PlaytimeCommand;
import amg.plugins.aMGCore.commands.TeleportCommands;
import amg.plugins.aMGCore.commands.PlayerCommands;
import amg.plugins.aMGCore.commands.AFKCommand;
import amg.plugins.aMGCore.commands.StatsCommand;
import amg.plugins.aMGCore.commands.ServerInfoCommands;
import amg.plugins.aMGCore.commands.ModuleCommand;
import amg.plugins.aMGCore.commands.ChatCommands;
import amg.plugins.aMGCore.commands.BackCommand;
import amg.plugins.aMGCore.commands.AdvertisementCommand;
import amg.plugins.aMGCore.events.InventoryEvents;
import amg.plugins.aMGCore.events.LoggingEvents;
import amg.plugins.aMGCore.events.PlayerDataEvents;
import amg.plugins.aMGCore.managers.BanManager;
import amg.plugins.aMGCore.managers.DatabaseManager;
import amg.plugins.aMGCore.managers.JailManager;
import amg.plugins.aMGCore.managers.LogManager;
import amg.plugins.aMGCore.managers.PlayerDataManager;
import amg.plugins.aMGCore.managers.PlaytimeManager;
import amg.plugins.aMGCore.managers.TeleportManager;
import amg.plugins.aMGCore.managers.ServerInfoManager;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.modules.BanModule;
import amg.plugins.aMGCore.modules.ChatModule;
import amg.plugins.aMGCore.modules.DatabaseModule;
import amg.plugins.aMGCore.modules.JailModule;
import amg.plugins.aMGCore.modules.LoggingModule;
import amg.plugins.aMGCore.modules.PlayerDataModule;
import amg.plugins.aMGCore.modules.PlayerModule;
import amg.plugins.aMGCore.modules.PlaytimeModule;
import amg.plugins.aMGCore.modules.ServerInfoModule;
import amg.plugins.aMGCore.modules.StatsModule;
import amg.plugins.aMGCore.modules.TeleportModule;
import amg.plugins.aMGCore.tasks.AutoSaveTask;
import amg.plugins.aMGCore.tasks.MemoryMonitorTask;
import amg.plugins.aMGCore.utils.DebugLogger;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class AMGCore extends JavaPlugin {
    private static AMGCore instance;
    private PlayerDataEvents playerDataEvents;
    private boolean debugEnabled;
    private final Map<String, Object> managers = new HashMap<>();
    private AutoSaveTask autoSaveTask;
    private BukkitTask logRotationTask;
    private LocaleManager localeManager;
    private MemoryMonitorTask memoryMonitorTask;
    
    // Module system
    private ModuleRegistry moduleRegistry;
    
    // Memory management
    private MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private AtomicLong lastGcTime = new AtomicLong(0);
    private static final long GC_COOLDOWN = 60000; // 1 minute between forced GCs
    private static final double MEMORY_THRESHOLD = 0.85; // 85% memory usage triggers optimization

    @Override
    public void onEnable() {
        int pluginId = 26718;
        Metrics metrics = new Metrics(this, pluginId);
        try {
            instance = this;
            
            try {
                // Save default config
                saveDefaultConfig();
                
                // Initialize debug logger
                debugEnabled = getConfig().getBoolean("debug", false);
                DebugLogger.initialize(this, debugEnabled);
                
                getLogger().info("Enabling AMGCore v" + getPluginMeta().getVersion());
                
                // Create data directory
                String dataDirPath = getConfig().getString("storage.data_directory", "data");
                File dataDir = new File(getDataFolder(), dataDirPath);
                if (!dataDir.exists() && !dataDir.mkdirs()) {
                    throw new RuntimeException("Failed to create data directory");
                }

                // Initialize locale manager first
                localeManager = new LocaleManager(this);
                registerManager("locale", localeManager);
                
                // Initialize module registry
                moduleRegistry = new ModuleRegistry(this);
                
                // Register and enable modules
                registerModules();
                moduleRegistry.enableModule("database");
                moduleRegistry.enableModule("logging");
                
                // Register event listeners and commands
                registerEventListeners();
                registerCommands();
                
                // Register API service and start tasks
                getServer().getServicesManager().register(
                    AMGCore.class,
                    this,
                    this,
                    ServicePriority.Normal
                );
                startTasks();
                
                // Load data for online players and enable auto-enable modules
                loadOnlinePlayers();
                moduleRegistry.enableAutoModules();
                
                // Start memory monitoring
                startMemoryMonitoring();
                
                getLogger().info("AMGCore has been enabled successfully!");
            } catch (Exception e) {
                getLogger().severe("Error during plugin initialization: " + e.getMessage());
                e.printStackTrace();
                throw e; // Re-throw to ensure the plugin is disabled
            }
        } catch (Exception e) {
            getLogger().severe("Critical error during plugin initialization. Disabling plugin: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerModules() {
        DebugLogger.debug("Main", "Registering modules");
        
        // Register core modules in order of dependency
        moduleRegistry.registerModule(new DatabaseModule(this));
        moduleRegistry.registerModule(new LoggingModule(this));
        moduleRegistry.registerModule(new PlayerDataModule(this));
        
        // Register feature modules
        moduleRegistry.registerModule(new BanModule(this));
        moduleRegistry.registerModule(new ChatModule(this));
        moduleRegistry.registerModule(new JailModule(this));
        moduleRegistry.registerModule(new PlayerModule(this));
        moduleRegistry.registerModule(new PlaytimeModule(this));
        moduleRegistry.registerModule(new ServerInfoModule(this));
        moduleRegistry.registerModule(new StatsModule(this));
        moduleRegistry.registerModule(new TeleportModule(this));
        
        // Set auto-enable modules from config
        if (getConfig().contains("modules.auto_enable")) {
            for (String moduleName : getConfig().getStringList("modules.auto_enable")) {
                moduleRegistry.addAutoEnableModule(moduleName);
            }
        } else {
            // Default auto-enable modules
            moduleRegistry.addAutoEnableModule("playerdata");
        }
    }

    private void registerEventListeners() {
        // Register player data events
        playerDataEvents = new PlayerDataEvents(this);
        getServer().getPluginManager().registerEvents(playerDataEvents, this);
        
        // Register inventory events
        getServer().getPluginManager().registerEvents(new InventoryEvents(this), this);
        
        // Register logging events
        getServer().getPluginManager().registerEvents(new LoggingEvents(this), this);
    }

    /**
     * Safely registers a command with error handling
     * 
     * @param commandName The name of the command to register
     * @param executor The command executor
     * @param tabCompleter The tab completer
     */
    private void registerCommand(String commandName, CommandExecutor executor, TabCompleter tabCompleter) {
        try {
            org.bukkit.command.PluginCommand command = getCommand(commandName);
            if (command == null) {
                getLogger().warning("Command not found in plugin.yml: " + commandName);
                return;
            }
            
            command.setExecutor(executor);
            if (tabCompleter != null) {
                command.setTabCompleter(tabCompleter);
            }
        } catch (Exception e) {
            getLogger().severe("Failed to register command: " + commandName + " - " + e.getMessage());
        }
    }

    private void registerCommands() {
        // Core commands
        CoreCommand coreCommand = new CoreCommand(this);
        registerCommand("core", coreCommand, coreCommand);
        
        // Debug commands
        CoreDebugCommand debugCommand = new CoreDebugCommand(this);
        registerCommand("coredebug", debugCommand, debugCommand);
        
        // Module command
        ModuleCommand moduleCommand = new ModuleCommand(this);
        registerCommand("module", moduleCommand, moduleCommand);
        
        // Moderator commands
        ModeratorCommands moderatorCommands = new ModeratorCommands(this);
        registerCommand("amgban", moderatorCommands, moderatorCommands);
        registerCommand("amgtempban", moderatorCommands, moderatorCommands);
        registerCommand("amgunban", moderatorCommands, moderatorCommands);
        registerCommand("amgkick", moderatorCommands, moderatorCommands);
        
        // Chat commands
        ChatCommands chatCommands = new ChatCommands(this);
        registerCommand("msg", chatCommands, chatCommands);
        registerCommand("r", chatCommands, chatCommands);
        registerCommand("reply", chatCommands, chatCommands);
        registerCommand("mute", chatCommands, chatCommands);
        registerCommand("unmute", chatCommands, chatCommands);
        registerCommand("broadcast", chatCommands, chatCommands);
        registerCommand("bc", chatCommands, chatCommands);
        registerCommand("ad", new AdvertisementCommand(this), null);
        
        // Inventory commands
        InventoryCommand inventoryCommand = new InventoryCommand(this);
        registerCommand("lookinventory", inventoryCommand, inventoryCommand);
        
        // Jail commands
        JailCommands jailCommands = new JailCommands(this);
        registerCommand("createjail", jailCommands, jailCommands);
        registerCommand("deletejail", jailCommands, jailCommands);
        registerCommand("jail", jailCommands, jailCommands);
        registerCommand("unjail", jailCommands, jailCommands);
        registerCommand("jaillist", jailCommands, jailCommands);
        
        // Economy commands
        EconomyCommands economyCommands = new EconomyCommands(this);
        registerCommand("money", economyCommands, economyCommands);
        registerCommand("givemoney", economyCommands, economyCommands);
        registerCommand("setmoney", economyCommands, economyCommands);
        registerCommand("pay", economyCommands, economyCommands);
        registerCommand("balancetop", economyCommands, economyCommands);
        
        // Teleport commands
        TeleportCommands teleportCommands = new TeleportCommands(this);
        registerCommand("tpa", teleportCommands, teleportCommands);
        registerCommand("tpaccept", teleportCommands, teleportCommands);
        registerCommand("tpdeny", teleportCommands, teleportCommands);
        registerCommand("sethome", teleportCommands, teleportCommands);
        registerCommand("home", teleportCommands, teleportCommands);
        registerCommand("delhome", teleportCommands, teleportCommands);
        registerCommand("setwarp", teleportCommands, teleportCommands);
        registerCommand("warp", teleportCommands, teleportCommands);
        registerCommand("delwarp", teleportCommands, teleportCommands);
        registerCommand("warplist", teleportCommands, teleportCommands);
        registerCommand("spawn", teleportCommands, teleportCommands);
        registerCommand("setspawn", teleportCommands, teleportCommands);
        registerCommand("back", new BackCommand(this), null);
        
        // Player commands
        PlayerCommands playerCommands = new PlayerCommands(this);
        registerCommand("vanish", playerCommands, playerCommands);
        registerCommand("v", playerCommands, playerCommands);
        registerCommand("god", playerCommands, playerCommands);
        registerCommand("speed", playerCommands, playerCommands);
        registerCommand("fly", playerCommands, playerCommands);
        registerCommand("heal", playerCommands, playerCommands);
        registerCommand("feed", playerCommands, playerCommands);
        
        // AFK command
        AFKCommand afkCommand = new AFKCommand(this);
        registerCommand("afk", afkCommand, afkCommand);
        
        // Stats command
        StatsCommand statsCommand = new StatsCommand(this);
        registerCommand("stats", statsCommand, statsCommand);
        
        // Playtime command
        PlaytimeCommand playtimeCommand = new PlaytimeCommand(this);
        registerCommand("playtime", playtimeCommand, playtimeCommand);
        registerCommand("pt", playtimeCommand, playtimeCommand);
        
        // Server info commands
        ServerInfoCommands serverInfoCommands = new ServerInfoCommands(this);
        registerCommand("rules", serverInfoCommands, serverInfoCommands);
        registerCommand("serverstats", serverInfoCommands, serverInfoCommands);
    }

    private void startTasks() {
        // Start auto-save task
        int autoSaveInterval = getConfig().getInt("storage.auto_save_interval", 300) * 20; // Convert to ticks
        autoSaveTask = new AutoSaveTask(this);
        autoSaveTask.runTaskTimer(this, autoSaveInterval, autoSaveInterval);
        
        // Set up log rotation
        setupLogRotation();
    }

    private void loadOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            try {
                getPlayerDataManager().loadPlayer(player);
            } catch (Exception e) {
                DebugLogger.severe("Main", "Failed to load data for player " + player.getName(), e);
            }
        }
    }

    @Override
    public void onDisable() {
        try {
            getLogger().info("Disabling AMGCore v" + getPluginMeta().getVersion());
            
            // Stop memory monitoring
            if (memoryMonitorTask != null) {
                memoryMonitorTask.cancel();
                memoryMonitorTask = null;
            }
            
            // Cancel tasks
            if (autoSaveTask != null) {
                autoSaveTask.cancel();
                autoSaveTask = null;
            }
            
            if (logRotationTask != null) {
                logRotationTask.cancel();
                logRotationTask = null;
            }
            
            // Save and unload all player data
            getLogger().info("Saving and unloading all player data...");
            saveAndUnloadPlayers();
            
            // Disable all modules
            if (moduleRegistry != null) {
                moduleRegistry.disableAllModules();
            }
            
            // Shutdown debug logger
            DebugLogger.shutdown();
            
            getLogger().info("AMGCore has been disabled successfully!");
        } catch (Exception e) {
            getLogger().severe("Error during plugin shutdown: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void saveAndUnloadPlayers() {
        if (moduleRegistry != null && moduleRegistry.getModule("playerdata") != null && 
                moduleRegistry.getModule("playerdata").isEnabled()) {
            try {
                int savedCount = getPlayerDataManager().saveAllPlayers();
                getLogger().info("Saved data for " + savedCount + " players");
            } catch (Exception e) {
                getLogger().severe("Failed to save player data: " + e.getMessage());
            }
        }
    }

    private void setupLogRotation() {
        // Set up daily log rotation
        long dayInTicks = 24 * 60 * 60 * 20; // 24 hours in ticks
        
        // Calculate initial delay to next midnight
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        String today = sdf.format(new Date());
        long tomorrowMidnight = 0;
        try {
            tomorrowMidnight = sdf.parse(today).getTime() + 24 * 60 * 60 * 1000;
        } catch (Exception e) {
            tomorrowMidnight = System.currentTimeMillis() + 24 * 60 * 60 * 1000;
        }
        
        long initialDelay = tomorrowMidnight - System.currentTimeMillis();
        
        // Schedule log rotation task
        logRotationTask = getServer().getScheduler().runTaskTimerAsynchronously(
            this,
            () -> {
                try {
                    getLogManager().rotateLogs();
                } catch (Exception e) {
                    DebugLogger.severe("Main", "Error during scheduled log rotation", e);
                }
            },
            initialDelay / 50, // Convert milliseconds to ticks
            dayInTicks // Run every 24 hours
        );
    }
    
    /**
     * Start monitoring memory usage and optimize when needed
     */
    private void startMemoryMonitoring() {
        // Check if memory monitoring is enabled in config
        if (!getConfig().getBoolean("memory.monitoring_enabled", true)) {
            return;
        }
        
        int monitoringInterval = getConfig().getInt("memory.monitoring_interval", 300) * 20; // Convert to ticks
        
        memoryMonitorTask = new MemoryMonitorTask(this);
        memoryMonitorTask.runTaskTimerAsynchronously(this, 1200, monitoringInterval); // Start after 1 minute
    }
    
    /**
     * Check memory usage and optimize if needed
     * 
     * @return true if optimization was performed, false otherwise
     */
    public boolean checkAndOptimizeMemory() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        
        // Calculate memory usage percentage
        double usagePercent = (double) used / max;
        
        if (usagePercent > MEMORY_THRESHOLD) {
            // Memory usage is above threshold, perform optimization
            DebugLogger.info("Memory", String.format(
                "High memory usage detected: %.1f%% (%.1f MB / %.1f MB)",
                usagePercent * 100,
                used / 1024.0 / 1024.0,
                max / 1024.0 / 1024.0
            ));
            
            return optimizeMemory();
        }
        
        return false;
    }
    
    /**
     * Optimize memory usage by clearing caches and suggesting garbage collection
     * 
     * @return true if optimization was performed, false otherwise
     */
    public boolean optimizeMemory() {
        long now = System.currentTimeMillis();
        long lastGc = lastGcTime.get();
        
        // Don't optimize too frequently
        if (now - lastGc < GC_COOLDOWN) {
            return false;
        }
        
        DebugLogger.info("Memory", "Performing memory optimization");
        
        // Clear caches in managers
        if (moduleRegistry.getModule("playerdata") != null && 
                moduleRegistry.getModule("playerdata").isEnabled()) {
            getPlayerDataManager().optimizeMemory();
        }
        
        // Suggest garbage collection
        System.gc();
        lastGcTime.set(now);
        
        // Log memory usage after optimization
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        double usagePercent = (double) used / max;
        
        DebugLogger.info("Memory", String.format(
            "Memory usage after optimization: %.1f%% (%.1f MB / %.1f MB)",
            usagePercent * 100,
            used / 1024.0 / 1024.0,
            max / 1024.0 / 1024.0
        ));
        
        return true;
    }
    
    /**
     * Get memory usage statistics
     * 
     * @return A string containing memory usage statistics
     */
    public String getMemoryStatistics() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        long committed = heapUsage.getCommitted();
        double usagePercent = (double) used / max;
        
        return String.format(
            "Memory: %.1f%% used (%.1f MB / %.1f MB), %.1f MB committed",
            usagePercent * 100,
            used / 1024.0 / 1024.0,
            max / 1024.0 / 1024.0,
            committed / 1024.0 / 1024.0
        );
    }

    public void reloadPluginConfig() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Reload config from disk
        reloadConfig();
        
        // Update debug mode
        boolean newDebugEnabled = getConfig().getBoolean("debug", false);
        if (newDebugEnabled != debugEnabled) {
            setDebugEnabled(newDebugEnabled);
        }
        
        // Reload modules
        if (moduleRegistry != null) {
            // Update auto-enable modules
            if (getConfig().contains("modules.auto_enable")) {
                for (String moduleName : getConfig().getStringList("modules.auto_enable")) {
                    moduleRegistry.addAutoEnableModule(moduleName);
                }
            }
        }
        
        // No need to reload individual managers as they will be reloaded when accessed
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        DebugLogger.setEnabled(enabled);
        
        // Update config
        getConfig().set("debug", enabled);
        saveConfig();
        
        // Update other components
        if (isModuleEnabled("playerdata")) {
            // PlayerData.setDebugEnabled(enabled); // This line was removed as per the new_code
        }
    }
    
    /**
     * Gets the module registry.
     * 
     * @return The module registry
     */
    @NotNull
    public ModuleRegistry getModuleRegistry() {
        return moduleRegistry;
    }
    
    /**
     * Checks if a module is enabled.
     * 
     * @param moduleName The name of the module to check
     * @return true if the module is enabled, false if it's disabled or doesn't exist
     */
    public boolean isModuleEnabled(String moduleName) {
        return moduleRegistry != null && moduleRegistry.isModuleEnabled(moduleName);
    }

    @NotNull
    public static AMGCore getInstance() {
        return Objects.requireNonNull(instance, "Plugin instance is not available");
    }

    @NotNull
    public PlayerDataManager getPlayerDataManager() {
        // Enable the module if it's not already enabled
        if (!isModuleEnabled("playerdata")) {
            moduleRegistry.enableModule("playerdata");
        }
        return (PlayerDataManager) managers.get("playerdata");
    }

    @NotNull
    public BanManager getBanManager() {
        // Enable the module if it's not already enabled
        if (!isModuleEnabled("ban")) {
            moduleRegistry.enableModule("ban");
        }
        return (BanManager) managers.get("ban");
    }

    @NotNull
    public LogManager getLogManager() {
        // Enable the module if it's not already enabled
        if (!isModuleEnabled("logging")) {
            moduleRegistry.enableModule("logging");
        }
        return (LogManager) managers.get("logging");
    }

    @NotNull
    public DatabaseManager getDatabaseManager() {
        // Enable the module if it's not already enabled
        if (!isModuleEnabled("database")) {
            moduleRegistry.enableModule("database");
        }
        return (DatabaseManager) managers.get("database");
    }

    @NotNull
    public JailManager getJailManager() {
        // Enable the module if it's not already enabled
        if (!isModuleEnabled("jail")) {
            moduleRegistry.enableModule("jail");
        }
        return (JailManager) managers.get("jail");
    }

    @NotNull
    public TeleportManager getTeleportManager() {
        // Enable the module if it's not already enabled
        if (!isModuleEnabled("teleport")) {
            moduleRegistry.enableModule("teleport");
        }
        return (TeleportManager) managers.get("teleport");
    }

    @NotNull
    public ServerInfoManager getServerInfoManager() {
        // Enable the module if it's not already enabled
        if (!isModuleEnabled("serverinfo")) {
            moduleRegistry.enableModule("serverinfo");
        }
        return (ServerInfoManager) managers.get("serverinfo");
    }
    
    @NotNull
    public PlaytimeManager getPlaytimeManager() {
        // Enable the module if it's not already enabled
        if (!isModuleEnabled("playtime")) {
            moduleRegistry.enableModule("playtime");
        }
        return (PlaytimeManager) managers.get("playtime");
    }

    @NotNull
    public LocaleManager getLocaleManager() {
        if (localeManager == null) {
            localeManager = new LocaleManager(this);
            registerManager("locale", localeManager);
        }
        return localeManager;
    }

    @Nullable
    public Object getManager(String name) {
        if ("locale".equals(name)) {
            return getLocaleManager();
        }
        return managers.get(name);
    }

    public void registerManager(String name, Object manager) {
        managers.put(name, manager);
    }

    public double getMoney(@NotNull Player player) {
        return CoreAPI.getMoney(player);
    }

    public void setMoney(@NotNull Player player, double amount) {
        CoreAPI.setMoney(player, amount);
    }

    public void addMoney(@NotNull Player player, double amount) {
        CoreAPI.addMoney(player, amount);
    }

    public boolean removeMoney(@NotNull Player player, double amount) {
        return CoreAPI.removeMoney(player, amount);
    }

    public boolean hasMoney(@NotNull Player player, double amount) {
        return CoreAPI.hasMoney(player, amount);
    }

    public String getJob(@NotNull Player player) {
        return CoreAPI.getJob(player);
    }

    public void setJob(@NotNull Player player, @NotNull String job) {
        CoreAPI.setJob(player, job);
    }
}
