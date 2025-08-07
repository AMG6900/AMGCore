package amg.plugins.aMGCore.api;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base implementation of the Module interface that provides common functionality.
 */
public abstract class BaseModule implements Module {
    protected final AMGCore plugin;
    protected final String name;
    protected final AtomicBoolean enabled = new AtomicBoolean(false);
    protected final String[] dependencies;
    protected final int priority;

    /**
     * Creates a new BaseModule with the given name and plugin instance.
     * 
     * @param name The module name
     * @param plugin The plugin instance
     */
    public BaseModule(@NotNull String name, @NotNull AMGCore plugin) {
        this(name, plugin, new String[0], 0);
    }

    /**
     * Creates a new BaseModule with the given name, plugin instance, dependencies, and priority.
     * 
     * @param name The module name
     * @param plugin The plugin instance
     * @param dependencies The module dependencies
     * @param priority The module priority
     */
    public BaseModule(@NotNull String name, @NotNull AMGCore plugin, String[] dependencies, int priority) {
        this.name = name;
        this.plugin = plugin;
        this.dependencies = dependencies != null ? dependencies : new String[0];
        this.priority = priority;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    @Override
    public boolean enable() {
        if (enabled.compareAndSet(false, true)) {
            try {
                onEnable();
                DebugLogger.debug("Module", "Enabled module: " + name);
                return true;
            } catch (Exception e) {
                enabled.set(false);
                DebugLogger.severe("Module", "Failed to enable module: " + name, e);
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean disable() {
        if (!canDisable()) {
            DebugLogger.debug("Module", "Cannot disable core module: " + name);
            return false;
        }
        
        if (enabled.compareAndSet(true, false)) {
            try {
                onDisable();
                DebugLogger.debug("Module", "Disabled module: " + name);
                return true;
            } catch (Exception e) {
                enabled.set(true); // Revert state on error
                DebugLogger.severe("Module", "Failed to disable module: " + name, e);
                return false;
            }
        }
        return false;
    }

    @Override
    public AMGCore getPlugin() {
        return plugin;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public String[] getDependencies() {
        return dependencies;
    }

    /**
     * Called when the module is enabled.
     * Subclasses should override this method to perform initialization.
     * 
     * @throws Exception if an error occurs during initialization
     */
    protected abstract void onEnable() throws Exception;

    /**
     * Called when the module is disabled.
     * Subclasses should override this method to perform cleanup.
     * 
     * @throws Exception if an error occurs during cleanup
     */
    protected abstract void onDisable() throws Exception;
} 