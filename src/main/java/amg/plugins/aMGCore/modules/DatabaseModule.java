package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.DatabaseManager;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for managing database connections and operations.
 */
public class DatabaseModule extends BaseModule {
    private DatabaseManager databaseManager;

    /**
     * Creates a new DatabaseModule.
     * 
     * @param plugin The plugin instance
     */
    public DatabaseModule(AMGCore plugin) {
        super("database", plugin, new String[0], 100); // High priority as other modules depend on it
    }

    @Override
    protected void onEnable() throws Exception {
        try {
            plugin.getLogger().info("DatabaseModule: Initializing database manager");
            DebugLogger.debug("DatabaseModule", "Initializing database manager");
            databaseManager = new DatabaseManager(plugin);
            plugin.registerManager("database", databaseManager);
            plugin.getLogger().info("DatabaseModule: Database manager initialized successfully");
        } catch (Exception e) {
            plugin.getLogger().severe("DatabaseModule: Failed to initialize database manager: " + e.getMessage());
            DebugLogger.severe("DatabaseModule", "Failed to initialize database manager", e);
            throw e; // Re-throw to ensure module initialization fails
        }
    }

    @Override
    protected void onDisable() throws Exception {
        try {
            plugin.getLogger().info("DatabaseModule: Shutting down database manager");
            DebugLogger.debug("DatabaseModule", "Shutting down database manager");
            if (databaseManager != null) {
                databaseManager.close();
                databaseManager = null;
                plugin.getLogger().info("DatabaseModule: Database manager shut down successfully");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("DatabaseModule: Error shutting down database manager: " + e.getMessage());
            DebugLogger.severe("DatabaseModule", "Error shutting down database manager", e);
            throw e;
        }
    }
    
    /**
     * Gets the database manager instance.
     * 
     * @return The database manager
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
} 