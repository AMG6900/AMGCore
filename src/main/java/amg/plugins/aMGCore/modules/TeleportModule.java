package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.TeleportManager;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for managing teleportation functionality.
 */
public class TeleportModule extends BaseModule {
    private TeleportManager teleportManager;

    /**
     * Creates a new TeleportModule.
     * 
     * @param plugin The plugin instance
     */
    public TeleportModule(AMGCore plugin) {
        super("teleport", plugin, new String[]{"database", "playerdata"}, 50);
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("TeleportModule", "Initializing teleport manager");
        teleportManager = new TeleportManager(plugin);
        plugin.registerManager("teleport", teleportManager);
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("TeleportModule", "Shutting down teleport manager");
        teleportManager = null;
    }
    
    /**
     * Gets the teleport manager instance.
     * 
     * @return The teleport manager
     */
    public TeleportManager getTeleportManager() {
        return teleportManager;
    }
} 