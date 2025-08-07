package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.PlaytimeManager;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for tracking player playtime.
 */
public class PlaytimeModule extends BaseModule {
    private PlaytimeManager playtimeManager;

    /**
     * Creates a new PlaytimeModule.
     * 
     * @param plugin The plugin instance
     */
    public PlaytimeModule(AMGCore plugin) {
        super("playtime", plugin, new String[]{"database"}, 40);
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("PlaytimeModule", "Initializing playtime manager");
        playtimeManager = new PlaytimeManager(plugin, plugin.getDatabaseManager());
        plugin.registerManager("playtime", playtimeManager);
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("PlaytimeModule", "Shutting down playtime manager");
        if (playtimeManager != null) {
            playtimeManager.shutdown();
            playtimeManager = null;
        }
    }
    
    /**
     * Gets the playtime manager instance.
     * 
     * @return The playtime manager
     */
    public PlaytimeManager getPlaytimeManager() {
        return playtimeManager;
    }
} 