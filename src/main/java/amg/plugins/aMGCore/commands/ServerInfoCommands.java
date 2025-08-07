package amg.plugins.aMGCore.commands;
import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.ServerInfoManager;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ServerInfoCommands implements CommandExecutor, TabCompleter {
    private final AMGCore plugin;
    private ServerInfoManager serverInfoManager;
    private final LocaleManager localeManager;

    public ServerInfoCommands(AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();

        if (!plugin.isModuleEnabled("serverinfo")) {
            plugin.getModuleRegistry().enableModule("serverinfo");
        }

        // Get server info manager
        this.serverInfoManager = (ServerInfoManager) plugin.getManager("serverinfo");
    }

    private boolean ensureManagersLoaded() {
        if (serverInfoManager == null) {
            if (!plugin.isModuleEnabled("serverinfo")) {
                return false;
            }
            serverInfoManager = (ServerInfoManager) plugin.getManager("serverinfo");
            return serverInfoManager != null;
        }
        return true;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        try {
            if (!ensureManagersLoaded()) {
                sender.sendMessage(localeManager.getComponent("serverinfo.module_unavailable"));
                return true;
            }

            return switch (command.getName().toLowerCase()) {
                case "rules" -> handleRules(sender, args);
                case "serverstats" -> handleServerStats(sender, args);
                default -> false;
            };
        } catch (Exception e) {
            DebugLogger.severe("Error executing server info command", "ServerInfoCommands", e);
            sender.sendMessage(localeManager.getComponent("serverinfo.error_occurred"));
            return true;
        }
    }

    private boolean handleRules(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("amgcore.command.rules.reload")) {
            serverInfoManager.loadRules();
            sender.sendMessage(localeManager.getComponent("serverinfo.rules.reloaded"));
            return true;
        }

        List<String> rules = serverInfoManager.getRules();

        if (rules.isEmpty()) {
            sender.sendMessage(localeManager.getComponent("serverinfo.rules.empty"));
            return true;
        }

        sender.sendMessage(localeManager.getComponent("serverinfo.rules.title"));

        for (int i = 0; i < rules.size(); i++) {
            sender.sendMessage(localeManager.getComponent("serverinfo.rules.entry", String.valueOf(i + 1), rules.get(i)));
        }

        return true;
    }

    private boolean handleServerStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.serverstats")) {
            sender.sendMessage(localeManager.getComponent("serverinfo.no_permission"));
            return true;
        }

        sender.sendMessage(serverInfoManager.getServerStats());
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("rules") && args.length == 1) {
            if (sender.hasPermission("amgcore.command.rules.reload")) {
                if ("reload".startsWith(args[0].toLowerCase())) {
                    completions.add("reload");
                }
            }
        }

        return completions;
    }
} 