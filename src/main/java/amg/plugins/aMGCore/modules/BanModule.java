package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.BanManager;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for managing player bans.
 */
public class BanModule extends BaseModule {
    private BanManager banManager;

    /**
     * Creates a new BanModule.
     * 
     * @param plugin The plugin instance
     */
    public BanModule(AMGCore plugin) {
        super("ban", plugin, new String[]{"playerdata"}, 70); // Depends on player data
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("BanModule", "Initializing ban manager");
        banManager = new BanManager(plugin);
        plugin.registerManager("ban", banManager);
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("BanModule", "Shutting down ban manager");
        // BanManager doesn't need explicit cleanup
        banManager = null;
    }
    
    /**
     * Gets the ban manager instance.
     * 
     * @return The ban manager
     */
    public BanManager getBanManager() {
        return banManager;
    }
} 