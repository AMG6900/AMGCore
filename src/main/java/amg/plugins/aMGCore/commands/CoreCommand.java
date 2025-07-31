package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class CoreCommand implements CommandExecutor, TabCompleter {
    private final AMGCore plugin;
    private final LocaleManager localeManager;
    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "data");

    public CoreCommand(AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("amgcore.admin")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return true;
        }

        if (args.length == 0) {
            showPluginInfo(sender);
            return true;
        }

        try {
            switch (args[0].toLowerCase()) {
                case "reload" -> {
                    plugin.reloadPluginConfig();
                    sender.sendMessage(localeManager.getComponent("command.core.reload_success"));
                }
                case "data" -> {
                    if (args.length < 2) {
                        sender.sendMessage(localeManager.getComponent("command.core.data_usage"));
                        return true;
                    }
                    Player target = plugin.getServer().getPlayer(args[1]);
                    if (target != null) {
                        plugin.getPlayerDataManager().reloadPlayerData(target);
                        sender.sendMessage(localeManager.getComponent("command.core.data_reloaded", target.getName()));
                    } else {
                        sender.sendMessage(localeManager.getComponent("command.player_not_found", args[1]));
                    }
                }
                default -> {
                    sender.sendMessage(localeManager.getComponent("command.core.unknown_option"));
                    return false;
                }
            }
        } catch (Exception e) {
            DebugLogger.severe("Command", "Error executing core command", e);
            sender.sendMessage(localeManager.getComponent("general.error", "Error executing command: " + e.getMessage()));
        }

        return true;
    }

    private void showPluginInfo(@NotNull CommandSender sender) {
        String version = plugin.getName() + " v" + plugin.getPluginMeta().getVersion();
        String dataDir = plugin.getConfig().getString("storage.data_directory", "data");
        int saveInterval = plugin.getConfig().getInt("storage.auto_save_interval", 300);
        int onlinePlayers = plugin.getServer().getOnlinePlayers().size();
        int loadedData = plugin.getPlayerDataManager().getLoadedPlayerCount();
        
        sender.sendMessage(localeManager.getComponent("command.core.info.header"));
        sender.sendMessage(localeManager.getComponent("command.core.info.version", version));
        sender.sendMessage(localeManager.getComponent("command.core.info.status_header"));
        sender.sendMessage(localeManager.getComponent("command.core.info.debug_status", String.valueOf(plugin.isDebugEnabled())));
        sender.sendMessage(localeManager.getComponent("command.core.info.players_header"));
        sender.sendMessage(localeManager.getComponent("command.core.info.online_players", String.valueOf(onlinePlayers)));
        sender.sendMessage(localeManager.getComponent("command.core.info.loaded_data", String.valueOf(loadedData)));
        sender.sendMessage(localeManager.getComponent("command.core.info.storage_header"));
        sender.sendMessage(localeManager.getComponent("command.core.info.data_directory", dataDir));
        sender.sendMessage(localeManager.getComponent("command.core.info.save_interval", String.valueOf(saveInterval)));
        sender.sendMessage(localeManager.getComponent("command.core.info.commands_header"));
        sender.sendMessage(localeManager.getComponent("command.core.info.command_core"));
        sender.sendMessage(localeManager.getComponent("command.core.info.command_reload"));
        sender.sendMessage(localeManager.getComponent("command.core.info.command_data"));
        sender.sendMessage(localeManager.getComponent("command.core.info.command_debug"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!sender.hasPermission("amgcore.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            String partialCommand = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                .filter(cmd -> cmd.startsWith(partialCommand))
                .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("data")) {
            String partialName = args[1].toLowerCase();
            return plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partialName))
                .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
} 