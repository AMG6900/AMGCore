package amg.plugins.aMGCore.api;

import amg.plugins.aMGCore.AMGCore;

/**
 * Interface for all modules in the AMGCore plugin.
 * Modules are components that can be enabled and disabled on demand.
 */
public interface Module {
    /**
     * Gets the name of this module.
     * 
     * @return The module name
     */
    String getName();
    
    /**
     * Checks if this module is currently enabled.
     * 
     * @return true if the module is enabled, false otherwise
     */
    boolean isEnabled();
    
    /**
     * Enables this module if it's not already enabled.
     * 
     * @return true if the module was enabled, false if it was already enabled
     */
    boolean enable();
    
    /**
     * Disables this module if it's currently enabled.
     * 
     * @return true if the module was disabled, false if it was already disabled
     */
    boolean disable();
    
    /**
     * Gets the plugin instance.
     * 
     * @return The plugin instance
     */
    AMGCore getPlugin();
    
    /**
     * Gets the module's priority. Higher priority modules are loaded before lower priority ones.
     * 
     * @return The module priority
     */
    default int getPriority() {
        return 0;
    }
    
    /**
     * Gets an array of module names that this module depends on.
     * These modules will be enabled before this module.
     * 
     * @return An array of module names that this module depends on
     */
    default String[] getDependencies() {
        return new String[0];
    }

    /**
     * Checks if this module can be disabled.
     * Core modules should return false to prevent disabling.
     * 
     * @return true if the module can be disabled, false otherwise
     */
    default boolean canDisable() {
        return true;
    }
} 