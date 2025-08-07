package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.LogManager;
import amg.plugins.aMGCore.utils.DebugLogger;

/**
 * Module for managing logging operations.
 */
public class LoggingModule extends BaseModule {
    private LogManager logManager;

    /**
     * Creates a new LoggingModule.
     * 
     * @param plugin The plugin instance
     */
    public LoggingModule(AMGCore plugin) {
        super("logging", plugin, new String[0], 90); // High priority, but after database
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("LoggingModule", "Initializing log manager");
        logManager = new LogManager(plugin);
        plugin.registerManager("logging", logManager);
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("LoggingModule", "Shutting down log manager");
        // LogManager doesn't need explicit cleanup
        logManager = null;
    }
    
    /**
     * Gets the log manager instance.
     * 
     * @return The log manager
     */
    public LogManager getLogManager() {
        return logManager;
    }
} 