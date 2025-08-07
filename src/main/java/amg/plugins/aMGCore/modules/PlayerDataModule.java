package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.PlayerDataManager;
import amg.plugins.aMGCore.models.PlayerData;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for managing player data.
 */
public class PlayerDataModule extends BaseModule {
    private PlayerDataManager playerDataManager;

    /**
     * Creates a new PlayerDataModule.
     * 
     * @param plugin The plugin instance
     */
    public PlayerDataModule(AMGCore plugin) {
        super("playerdata", plugin, new String[]{"database"}, 80); // Depends on database
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("PlayerDataModule", "Initializing player data manager");
        playerDataManager = new PlayerDataManager(plugin);
        plugin.registerManager("playerdata", playerDataManager);
        
        // Set debug mode
        PlayerData.setDebugEnabled(plugin.isDebugEnabled());
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("PlayerDataModule", "Shutting down player data manager");
        if (playerDataManager != null) {
            playerDataManager.close();
            playerDataManager = null;
        }
    }
    
    /**
     * Gets the player data manager instance.
     * 
     * @return The player data manager
     */
    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }
} 