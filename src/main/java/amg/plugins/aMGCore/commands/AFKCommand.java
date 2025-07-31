package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.AFKManager;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.utils.DebugLogger;
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
import java.util.stream.Collectors;

public class AFKCommand implements CommandExecutor, TabCompleter {
    private final AMGCore plugin;
    private AFKManager afkManager;
    private final LocaleManager localeManager;

    public AFKCommand(AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();
    }

    private boolean ensureManagersLoaded() {
        if (afkManager == null) {
            if (!plugin.isModuleEnabled("afk")) {
                return false;
            }
            afkManager = (AFKManager) plugin.getManager("afk");
            return afkManager != null;
        }
        return true;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        try {
            if (!ensureManagersLoaded()) {
                sender.sendMessage(localeManager.getComponent("afk.module_unavailable"));
                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage(localeManager.getComponent("command.players_only"));
                return true;
            }

            if (args.length > 0 && sender.hasPermission("amgcore.command.afk.others")) {
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage(localeManager.getComponent("afk.player_not_found", args[0]));
                    return true;
                }

                boolean isAFK = afkManager.isAFK(target);
                String reason = args.length > 1 ? String.join(" ", args).substring(args[0].length() + 1) : null;
                afkManager.setAFK(target, !isAFK, reason);

                if (sender != target) {
                    sender.sendMessage(localeManager.getComponent("afk.toggled_other", 
                        target.getName(), 
                        localeManager.getMessage("afk.status." + (!isAFK ? "on" : "off"))
                    ));
                }
            } else {
                boolean isAFK = afkManager.isAFK(player);
                String reason = args.length > 0 ? String.join(" ", args) : null;
                afkManager.setAFK(player, !isAFK, reason);
            }

            return true;
        } catch (Exception e) {
            DebugLogger.severe("Error executing AFK command", "AFKCommand", e);
            sender.sendMessage(localeManager.getComponent("afk.error_occurred"));
            return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1 && sender.hasPermission("amgcore.command.afk.others")) {
            completions.addAll(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList()));
        }

        return completions;
    }
} 