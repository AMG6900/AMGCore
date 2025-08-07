package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.ServerInfoManager;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for managing server information.
 */
public class ServerInfoModule extends BaseModule {
    private ServerInfoManager serverInfoManager;

    /**
     * Creates a new ServerInfoModule.
     * 
     * @param plugin The plugin instance
     */
    public ServerInfoModule(AMGCore plugin) {
        super("serverinfo", plugin, new String[0], 30);
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("ServerInfoModule", "Initializing server info manager");
        serverInfoManager = new ServerInfoManager(plugin);
        plugin.registerManager("serverinfo", serverInfoManager);
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("ServerInfoModule", "Shutting down server info manager");
        serverInfoManager = null;
    }
    
    /**
     * Gets the server info manager instance.
     * 
     * @return The server info manager
     */
    public ServerInfoManager getServerInfoManager() {
        return serverInfoManager;
    }
} 