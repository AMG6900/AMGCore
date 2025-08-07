package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.JailManager;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for managing player jails.
 */
public class JailModule extends BaseModule {
    private JailManager jailManager;

    /**
     * Creates a new JailModule.
     * 
     * @param plugin The plugin instance
     */
    public JailModule(AMGCore plugin) {
        super("jail", plugin, new String[]{"database", "playerdata"}, 60); // Depends on database and player data
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("JailModule", "Initializing jail manager");
        jailManager = new JailManager(plugin, plugin.getDatabaseManager());
        plugin.registerManager("jail", jailManager);
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("JailModule", "Shutting down jail manager");
        if (jailManager != null) {
            jailManager.shutdown();
            jailManager = null;
        }
    }
    
    /**
     * Gets the jail manager instance.
     * 
     * @return The jail manager
     */
    public JailManager getJailManager() {
        return jailManager;
    }
} 