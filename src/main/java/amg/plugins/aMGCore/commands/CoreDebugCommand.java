package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command handler for the debug commands.
 * Provides functionality to view debug information.
 */
public class CoreDebugCommand implements CommandExecutor, TabCompleter {
    private final AMGCore plugin;
    private final LocaleManager localeManager;
    private static final List<String> SUBCOMMANDS = Arrays.asList("on", "off", "list");

    /**
     * Creates a new CoreDebugCommand.
     *
     * @param plugin the plugin instance
     */
    public CoreDebugCommand(@NotNull AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("amgcore.debug")) {
            sender.sendMessage(localeManager.getComponent("debug.no_permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(localeManager.getComponent("debug.usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on" -> {
                plugin.getConfig().set("debug", true);
                plugin.saveConfig();
                plugin.reloadPluginConfig();
                sender.sendMessage(localeManager.getComponent("debug.enabled"));
            }
            case "off" -> {
                plugin.getConfig().set("debug", false);
                plugin.saveConfig();
                plugin.reloadPluginConfig();
                sender.sendMessage(localeManager.getComponent("debug.disabled"));
            }
            case "list" -> {
                boolean isEnabled = plugin.isDebugEnabled();
                sender.sendMessage(localeManager.getComponent("debug.status.header"));
                sender.sendMessage(localeManager.getComponent("debug.status.mode", isEnabled ? 1 : 0));
                if (isEnabled) {
                    sender.sendMessage(localeManager.getComponent("debug.status.log_dir", plugin.getDataFolder().getPath() + "/logs"));
                }
            }
            default -> {
                sender.sendMessage(localeManager.getComponent("debug.unknown_option"));
                return false;
            }
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!sender.hasPermission("amgcore.debug")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            String partialCommand = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                .filter(cmd -> cmd.startsWith(partialCommand))
                .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
} 