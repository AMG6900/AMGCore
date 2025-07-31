package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.managers.PlaytimeManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Command to display player playtime.
 */
public class PlaytimeCommand implements CommandExecutor, TabCompleter {
    private final AMGCore plugin;
    private final PlaytimeManager playtimeManager;
    private final LocaleManager localeManager;

    public PlaytimeCommand(AMGCore plugin) {
        this.plugin = plugin;
        this.playtimeManager = plugin.getPlaytimeManager();
        this.localeManager = plugin.getLocaleManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("amgcore.command.playtime")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return true;
        }

        if (args.length > 1) {
            sender.sendMessage(localeManager.getComponent("playtime.usage"));
            return true;
        }

        // If no args, show sender's playtime
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(localeManager.getComponent("command.players_only"));
                return true;
            }

            Player player = (Player) sender;
            String playtime = playtimeManager.getFormattedPlaytime(player.getUniqueId());
            sender.sendMessage(localeManager.getComponent("playtime.self", playtime));
            return true;
        }

        // If args, show specified player's playtime
        String targetName = args[0];
        Player targetPlayer = Bukkit.getPlayer(targetName);
        
        if (targetPlayer != null) {
            // Player is online
            String playtime = playtimeManager.getFormattedPlaytime(targetPlayer.getUniqueId());
            sender.sendMessage(localeManager.getComponent("playtime.other", targetPlayer.getName(), playtime));
            return true;
        } else {
            // Try to find offline player
            UUID offlineUuid = plugin.getServer().getOfflinePlayer(targetName).getUniqueId();
            
            if (offlineUuid != null) {
                String playtime = playtimeManager.getFormattedPlaytime(offlineUuid);
                sender.sendMessage(localeManager.getComponent("playtime.other", targetName, playtime));
                return true;
            } else {
                sender.sendMessage(localeManager.getComponent("playtime.not_found", targetName));
                return true;
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String partialName = args[0].toLowerCase();
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partialName) && 
                    (sender.hasPermission("amgcore.command.playtime.others") || sender.getName().equals(player.getName()))) {
                    completions.add(player.getName());
                }
            }
            
            return completions;
        }
        
        return new ArrayList<>();
    }
} 