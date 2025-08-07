package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.AFKManager;
import amg.plugins.aMGCore.managers.DatabaseManager;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.jetbrains.annotations.NotNull;

public class AFKModule extends BaseModule {
    private AFKManager afkManager;

    public AFKModule(@NotNull AMGCore plugin) {
        super("afk", plugin);
    }

    @Override
    public void onEnable() {
        try {
            // Create the AFK manager
            DatabaseManager databaseManager = plugin.getDatabaseManager();
            afkManager = new AFKManager(plugin, databaseManager);
            plugin.registerManager("afk", afkManager);
        } catch (Exception e) {
            DebugLogger.severe("AFKModule", "Failed to enable AFK module", e);
        }
    }

    @Override
    public void onDisable() {
        if (afkManager != null) {
            // Clear AFK states
            afkManager = null;
        }
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public String[] getDependencies() {
        return new String[0];
    }

    public AFKManager getAFKManager() {
        return afkManager;
    }
}
