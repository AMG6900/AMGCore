package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.StatsManager;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for managing player statistics.
 */
public class StatsModule extends BaseModule {
    private StatsManager statsManager;

    /**
     * Creates a new StatsModule.
     * 
     * @param plugin The plugin instance
     */
    public StatsModule(AMGCore plugin) {
        super("stats", plugin, new String[]{"database", "playerdata"}, 40);
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("StatsModule", "Initializing stats manager");
        statsManager = new StatsManager(plugin, plugin.getDatabaseManager());
        plugin.registerManager("stats", statsManager);
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("StatsModule", "Shutting down stats manager");
        statsManager = null;
    }
    
    /**
     * Gets the stats manager instance.
     * 
     * @return The stats manager
     */
    public StatsManager getStatsManager() {
        return statsManager;
    }
} 