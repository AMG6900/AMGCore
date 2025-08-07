package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.Module;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for managing plugin modules.
 */
public class ModuleCommand implements CommandExecutor, TabCompleter {
    private final AMGCore plugin;
    private final LocaleManager localeManager;

    /**
     * Creates a new ModuleCommand.
     * 
     * @param plugin The plugin instance
     */
    public ModuleCommand(@NotNull AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("amgcore.command.module")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return true;
        }

        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> listModules(sender);
            case "enable" -> enableModule(sender, args);
            case "disable" -> disableModule(sender, args);
            case "info" -> showModuleInfo(sender, args);
            case "reload" -> reloadModules(sender);
            default -> {
                sender.sendMessage(localeManager.getComponent("module.command.unknown_subcommand"));
                showHelp(sender);
            }
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("amgcore.command.module")) {
            return List.of();
        }

        if (args.length == 1) {
            return List.of("list", "enable", "disable", "info", "reload")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "enable":
                    // Only show disabled modules
                    return plugin.getModuleRegistry().getAllModules().stream()
                            .filter(m -> !m.isEnabled())
                            .map(Module::getName)
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                case "disable":
                    // Only show enabled modules
                    return plugin.getModuleRegistry().getAllModules().stream()
                            .filter(Module::isEnabled)
                            .map(Module::getName)
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                case "info":
                    // Show all modules
                    return plugin.getModuleRegistry().getAllModules().stream()
                            .map(Module::getName)
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(localeManager.getComponent("module.help.header"));
        sender.sendMessage(localeManager.getComponent("module.help.list"));
        sender.sendMessage(localeManager.getComponent("module.help.enable"));
        sender.sendMessage(localeManager.getComponent("module.help.disable"));
        sender.sendMessage(localeManager.getComponent("module.help.info"));
        sender.sendMessage(localeManager.getComponent("module.help.reload"));
    }

    private void listModules(CommandSender sender) {
        Collection<Module> allModules = plugin.getModuleRegistry().getAllModules();
        Collection<Module> enabledModules = plugin.getModuleRegistry().getEnabledModules();
        
        if (allModules.isEmpty()) {
            sender.sendMessage(localeManager.getComponent("module.list.empty"));
            return;
        }
        
        sender.sendMessage(localeManager.getComponent("module.list.header", 
            String.valueOf(enabledModules.size()), 
            String.valueOf(allModules.size())));
        
        List<Module> sortedModules = new ArrayList<>(allModules);
        sortedModules.sort((m1, m2) -> {
            // Sort by status (enabled first), then by priority (higher first), then by name
            if (m1.isEnabled() != m2.isEnabled()) {
                return m1.isEnabled() ? -1 : 1;
            }
            if (m1.getPriority() != m2.getPriority()) {
                return m2.getPriority() - m1.getPriority();
            }
            return m1.getName().compareTo(m2.getName());
        });
        
        for (Module module : sortedModules) {
            boolean isEnabled = module.isEnabled();
            sender.sendMessage(localeManager.getComponent("module.list.entry", 
                module.getName(), 
                isEnabled ? "enabled" : "disabled"
            ));
        }
    }

    private void enableModule(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(localeManager.getComponent("module.command.usage.enable"));
            return;
        }

        String moduleName = args[1];
        Module module = plugin.getModuleRegistry().getModule(moduleName);
        
        if (module == null) {
            sender.sendMessage(localeManager.getComponent("module.enable.not_found", moduleName));
            return;
        }
        
        if (module.isEnabled()) {
            sender.sendMessage(localeManager.getComponent("module.enable.already_enabled", moduleName));
            return;
        }
        
        try {
            boolean success = plugin.getModuleRegistry().enableModule(moduleName);
            
            if (success) {
                sender.sendMessage(localeManager.getComponent("module.enable.success", moduleName));
                DebugLogger.debug("ModuleCommand", "Enabled module: " + moduleName);
            } else {
                sender.sendMessage(localeManager.getComponent("module.enable.failed", moduleName));
            }
        } catch (Exception e) {
            sender.sendMessage(localeManager.getComponent("module.enable.error", e.getMessage()));
            DebugLogger.severe("ModuleCommand", "Error enabling module: " + moduleName, e);
        }
    }

    private void disableModule(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(localeManager.getComponent("module.command.usage.disable"));
            return;
        }

        String moduleName = args[1];
        Module module = plugin.getModuleRegistry().getModule(moduleName);
        
        if (module == null) {
            sender.sendMessage(localeManager.getComponent("module.disable.not_found", moduleName));
            return;
        }
        
        if (!module.isEnabled()) {
            sender.sendMessage(localeManager.getComponent("module.disable.already_disabled", moduleName));
            return;
        }
        
        // Prevent disabling critical modules
        if (Arrays.asList("database", "logging").contains(moduleName)) {
            sender.sendMessage(localeManager.getComponent("module.disable.critical", moduleName));
            return;
        }
        
        try {
            boolean success = plugin.getModuleRegistry().disableModule(moduleName);
            
            if (success) {
                sender.sendMessage(localeManager.getComponent("module.disable.success", moduleName));
                DebugLogger.debug("ModuleCommand", "Disabled module: " + moduleName);
            } else {
                sender.sendMessage(localeManager.getComponent("module.disable.failed", moduleName));
            }
        } catch (Exception e) {
            sender.sendMessage(localeManager.getComponent("module.disable.error", e.getMessage()));
            DebugLogger.severe("ModuleCommand", "Error disabling module: " + moduleName, e);
        }
    }

    private void showModuleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(localeManager.getComponent("module.command.usage.info"));
            return;
        }

        String moduleName = args[1];
        Module module = plugin.getModuleRegistry().getModule(moduleName);
        
        if (module == null) {
            sender.sendMessage(localeManager.getComponent("module.enable.not_found", moduleName));
            return;
        }
        
        String status = module.isEnabled() ? "enabled" : "disabled";
        
        sender.sendMessage(localeManager.getComponent("module.info.title", module.getName()));
        sender.sendMessage(localeManager.getComponent("module.info.status", status));
        sender.sendMessage(localeManager.getComponent("module.info.priority", String.valueOf(module.getPriority())));
        
        sender.sendMessage(localeManager.getComponent("module.info.dependencies.header"));
        if (module.getDependencies().length > 0) {
            sender.sendMessage(localeManager.getComponent("module.info.dependencies.list", String.join(", ", module.getDependencies())));
        } else {
            sender.sendMessage(localeManager.getComponent("module.info.dependencies.none"));
        }
    }

    private void reloadModules(CommandSender sender) {
        try {
            plugin.reloadPluginConfig();
            sender.sendMessage(localeManager.getComponent("module.reload.success"));
        } catch (Exception e) {
            sender.sendMessage(localeManager.getComponent("module.reload.error", e.getMessage()));
            DebugLogger.severe("ModuleCommand", "Error reloading module configuration", e);
        }
    }
} 