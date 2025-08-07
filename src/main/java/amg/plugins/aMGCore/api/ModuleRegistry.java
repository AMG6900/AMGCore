package amg.plugins.aMGCore.api;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for managing modules in the AMGCore plugin.
 */
public class ModuleRegistry {
    private final Map<String, Module> modules = new ConcurrentHashMap<>();
    private final Set<String> autoEnableModules = new HashSet<>();
    private final LocaleManager localeManager;

    /**
     * Creates a new ModuleRegistry for the given plugin.
     * 
     * @param plugin The plugin instance
     */
    public ModuleRegistry(@NotNull AMGCore plugin) {
        this.localeManager = plugin.getLocaleManager();
    }

    /**
     * Registers a module with the registry.
     * 
     * @param module The module to register
     * @return true if the module was registered, false if a module with the same name already exists
     */
    public boolean registerModule(@NotNull Module module) {
        if (modules.containsKey(module.getName())) {
            DebugLogger.warning("ModuleRegistry", "Module already registered: " + module.getName());
            return false;
        }

        modules.put(module.getName(), module);
        DebugLogger.debug("ModuleRegistry", "Registered module: " + module.getName());
        return true;
    }

    /**
     * Unregisters a module from the registry.
     * 
     * @param moduleName The name of the module to unregister
     * @return true if the module was unregistered, false if it wasn't registered
     */
    public boolean unregisterModule(@NotNull String moduleName) {
        Module module = modules.remove(moduleName);
        if (module != null) {
            if (module.isEnabled()) {
                module.disable();
            }
            DebugLogger.debug("ModuleRegistry", "Unregistered module: " + moduleName);
            return true;
        }
        return false;
    }

    /**
     * Gets a module by name.
     * 
     * @param moduleName The name of the module to get
     * @return The module, or null if no module with the given name exists
     */
    @Nullable
    public Module getModule(@NotNull String moduleName) {
        return modules.get(moduleName);
    }

    /**
     * Checks if a module is enabled
     * 
     * @param name The name of the module
     * @return true if the module exists and is enabled, false otherwise
     */
    public boolean isModuleEnabled(String name) {
        Module module = getModule(name);
        return module != null && module.isEnabled();
    }

    /**
     * Gets all registered modules.
     * 
     * @return An unmodifiable collection of all registered modules
     */
    public Collection<Module> getAllModules() {
        return Collections.unmodifiableCollection(modules.values());
    }

    /**
     * Gets all enabled modules.
     * 
     * @return A collection of all enabled modules
     */
    public Collection<Module> getEnabledModules() {
        return modules.values().stream()
                .filter(Module::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * Enables a module and all its dependencies.
     * 
     * @param moduleName The name of the module to enable
     * @return true if the module was enabled, false if it couldn't be enabled
     */
    public boolean enableModule(@NotNull String moduleName) {
        Module module = modules.get(moduleName);
        if (module == null) {
            if (localeManager != null) {
                DebugLogger.warning("ModuleRegistry", localeManager.getMessage("command.core.module.not_found", moduleName));
            } else {
                DebugLogger.warning("ModuleRegistry", "Module not found: " + moduleName);
            }
            return false;
        }

        if (module.isEnabled()) {
            if (localeManager != null) {
                DebugLogger.info("ModuleRegistry", localeManager.getMessage("command.core.module.already_enabled", moduleName));
            }
            return true;
        }

        // Enable dependencies first
        for (String dependency : module.getDependencies()) {
            if (!enableModule(dependency)) {
                if (localeManager != null) {
                    DebugLogger.warning("ModuleRegistry", localeManager.getMessage("command.core.module.dependency_failed", dependency, moduleName));
                } else {
                    DebugLogger.warning("ModuleRegistry", "Failed to enable dependency: " + dependency + " for module: " + moduleName);
                }
                return false;
            }
        }

        // Enable the module
        boolean success = module.enable();
        if (success && localeManager != null) {
            DebugLogger.info("ModuleRegistry", localeManager.getMessage("command.core.module.enabled", moduleName));
        }
        return success;
    }

    /**
     * Disables a module.
     * 
     * @param moduleName The name of the module to disable
     * @return true if the module was disabled, false if it couldn't be disabled
     */
    public boolean disableModule(@NotNull String moduleName) {
        Module module = modules.get(moduleName);
        if (module == null) {
            if (localeManager != null) {
                DebugLogger.warning("ModuleRegistry", localeManager.getMessage("command.core.module.not_found", moduleName));
            } else {
                DebugLogger.warning("ModuleRegistry", "Module not found: " + moduleName);
            }
            return false;
        }

        if (!module.isEnabled()) {
            if (localeManager != null) {
                DebugLogger.info("ModuleRegistry", localeManager.getMessage("command.core.module.already_disabled", moduleName));
            }
            return true;
        }

        // Check if any enabled modules depend on this one
        for (Module otherModule : getEnabledModules()) {
            if (Arrays.asList(otherModule.getDependencies()).contains(moduleName)) {
                String reason = "Module " + otherModule.getName() + " depends on it";
                if (localeManager != null) {
                    DebugLogger.warning("ModuleRegistry", localeManager.getMessage("command.core.module.disable_failed", moduleName, reason));
                } else {
                    DebugLogger.warning("ModuleRegistry", "Cannot disable module: " + moduleName + " because " + reason);
                }
                return false;
            }
        }

        // Disable the module
        boolean success = module.disable();
        if (success && localeManager != null) {
            DebugLogger.info("ModuleRegistry", localeManager.getMessage("command.core.module.disabled", moduleName));
        }
        return success;
    }

    /**
     * Marks a module for auto-enabling when the plugin starts.
     * 
     * @param moduleName The name of the module to auto-enable
     */
    public void addAutoEnableModule(@NotNull String moduleName) {
        autoEnableModules.add(moduleName);
    }

    /**
     * Removes a module from the auto-enable list.
     * 
     * @param moduleName The name of the module to remove from auto-enable
     */
    public void removeAutoEnableModule(@NotNull String moduleName) {
        autoEnableModules.remove(moduleName);
    }

    /**
     * Enables all modules marked for auto-enable.
     */
    public void enableAutoModules() {
        // First enable all core modules (modules that can't be disabled)
        for (Module module : modules.values()) {
            if (!module.canDisable()) {
                enableModule(module.getName());
            }
        }

        // Then enable auto-enable modules
        for (String moduleName : autoEnableModules) {
            enableModule(moduleName);
        }
    }

    /**
     * Disables all enabled modules except core modules.
     */
    public void disableAllModules() {
        // Sort modules by dependency (modules that others depend on are disabled last)
        List<Module> sortedModules = new ArrayList<>(getEnabledModules());
        sortedModules.sort((m1, m2) -> {
            boolean m1DependsOnM2 = Arrays.asList(m1.getDependencies()).contains(m2.getName());
            boolean m2DependsOnM1 = Arrays.asList(m2.getDependencies()).contains(m1.getName());
            
            if (m1DependsOnM2) return -1;
            if (m2DependsOnM1) return 1;
            return 0;
        });

        // Disable modules that can be disabled
        for (Module module : sortedModules) {
            if (module.canDisable()) {
                try {
                    module.disable();
                } catch (Exception e) {
                    DebugLogger.severe("ModuleRegistry", "Error disabling module: " + module.getName(), e);
                }
            } else {
                DebugLogger.debug("ModuleRegistry", "Skipping core module: " + module.getName());
            }
        }
    }
} 