package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.PlayerManager;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for managing player functionality.
 */
public class PlayerModule extends BaseModule {
    private PlayerManager playerManager;

    /**
     * Creates a new PlayerModule.
     * 
     * @param plugin The plugin instance
     */
    public PlayerModule(AMGCore plugin) {
        super("player", plugin, new String[]{"database", "playerdata"}, 50);
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("PlayerModule", "Initializing player manager");
        playerManager = new PlayerManager(plugin, plugin.getDatabaseManager());
        plugin.registerManager("player", playerManager);
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("PlayerModule", "Shutting down player manager");
        playerManager = null;
    }
    
    /**
     * Gets the player manager instance.
     * 
     * @return The player manager
     */
    public PlayerManager getPlayerManager() {
        return playerManager;
    }
} 