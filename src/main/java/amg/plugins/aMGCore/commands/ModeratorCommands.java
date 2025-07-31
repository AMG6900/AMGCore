package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ModeratorCommands implements CommandExecutor, TabCompleter {
    private final AMGCore plugin;
    private final LocaleManager localeManager;

    public ModeratorCommands(@NotNull AMGCore plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.localeManager = plugin.getLocaleManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Log command usage
        plugin.getLogger().info(sender.getName() + " used command: /" + label + " " + String.join(" ", args));
        
        try {
            switch (command.getName().toLowerCase()) {
                case "amgban":
                    return handleBanCommand(sender, args);
                case "amgtempban":
                    return handleTempBanCommand(sender, args);
                case "amgkick":
                    return handleKickCommand(sender, args);
                case "amgunban":
                    return handleUnbanCommand(sender, args);
                default:
                    return false;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error executing command " + label + ": " + e.getMessage());
            e.printStackTrace();
            sender.sendMessage(localeManager.getComponent("moderator.error.command_error"));
            return true;
        }
    }

    private boolean handleBanCommand(@NotNull CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.ban")) {
            sender.sendMessage(localeManager.getComponent("moderator.error.no_permission"));
            plugin.getLogger().info("Ban command failed: " + sender.getName() + " lacks permission");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(localeManager.getComponent("moderator.ban.usage"));
            plugin.getLogger().info("Ban command failed: Invalid arguments from " + sender.getName());
            return true;
        }

        String targetName = args[0];
        String reason = String.join(" ", args).substring(args[0].length()).trim();
        
        // Check if player exists
        if (Bukkit.getOfflinePlayer(targetName).hasPlayedBefore() || Bukkit.getPlayer(targetName) != null) {
            // Check if already banned
            if (plugin.getBanManager().isPlayerBanned(targetName)) {
                sender.sendMessage(localeManager.getComponent("moderator.ban.already_banned", targetName));
                plugin.getLogger().info("Ban command failed: " + targetName + " is already banned");
                return true;
            }

            // Check if trying to ban themselves
            if (sender instanceof Player && targetName.equalsIgnoreCase(sender.getName())) {
                sender.sendMessage(localeManager.getComponent("moderator.ban.cannot_self"));
                plugin.getLogger().info("Ban command failed: " + sender.getName() + " tried to ban themselves");
                return true;
            }

            plugin.getBanManager().banPlayer(targetName, sender.getName(), reason, -1); // -1 for permanent
            plugin.getLogManager().logBanKick(sender.getName(), "BAN", targetName, "PERMANENT", reason);
            
            // Kick the player if they're online
            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                target.kick(plugin.getBanManager().getBanComponent(targetName));
                plugin.getLogger().info("Kicked banned player: " + targetName);
            }

            // Broadcast the ban
            Bukkit.broadcast(
                localeManager.getComponent("moderator.ban.broadcast", targetName, sender.getName(), reason),
                "amgcore.ban.notify"
            );
            plugin.getLogger().info(localeManager.getMessage("moderator.ban.broadcast", targetName, sender.getName(), reason));

            // Confirm to sender
            sender.sendMessage(localeManager.getComponent("moderator.ban.success", targetName));
        } else {
            sender.sendMessage(localeManager.getComponent("moderator.error.never_played", targetName));
            plugin.getLogger().info("Ban command failed: Player " + targetName + " has never played on this server");
        }

        return true;
    }

    private boolean handleTempBanCommand(@NotNull CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.tempban")) {
            sender.sendMessage(localeManager.getComponent("moderator.error.no_permission"));
            plugin.getLogger().info("Tempban command failed: " + sender.getName() + " lacks permission");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(localeManager.getComponent("moderator.tempban.usage"));
            sender.sendMessage(localeManager.getComponent("moderator.tempban.duration_format"));
            plugin.getLogger().info("Tempban command failed: Invalid arguments from " + sender.getName());
            return true;
        }

        String targetName = args[0];
        String durationStr = args[1];
        String reason = String.join(" ", args).substring(args[0].length() + args[1].length()).trim();
        
        // Check if player exists
        if (Bukkit.getOfflinePlayer(targetName).hasPlayedBefore() || Bukkit.getPlayer(targetName) != null) {
            // Check if already banned
            if (plugin.getBanManager().isPlayerBanned(targetName)) {
                sender.sendMessage(localeManager.getComponent("moderator.ban.already_banned", targetName));
                plugin.getLogger().info("Tempban command failed: " + targetName + " is already banned");
                return true;
            }

            // Check if trying to ban themselves
            if (sender instanceof Player && targetName.equalsIgnoreCase(sender.getName())) {
                sender.sendMessage(localeManager.getComponent("moderator.ban.cannot_self"));
                plugin.getLogger().info("Tempban command failed: " + sender.getName() + " tried to ban themselves");
                return true;
            }

            // Parse duration
            long duration = parseDuration(durationStr);
            if (duration <= 0) {
                sender.sendMessage(localeManager.getComponent("moderator.error.invalid_duration"));
                plugin.getLogger().info("Tempban command failed: Invalid duration format from " + sender.getName());
                return true;
            }

            plugin.getBanManager().banPlayer(targetName, sender.getName(), reason, duration);
            plugin.getLogManager().logBanKick(sender.getName(), "TEMPBAN", targetName, durationStr, reason);

            // Kick the player if they're online
            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                target.kick(plugin.getBanManager().getBanComponent(targetName));
                plugin.getLogger().info("Kicked temp-banned player: " + targetName);
            }

            // Broadcast the temp ban
            Bukkit.broadcast(
                localeManager.getComponent("moderator.tempban.broadcast", targetName, sender.getName(), durationStr, reason),
                "amgcore.ban.notify"
            );
            plugin.getLogger().info(localeManager.getMessage("moderator.tempban.broadcast", targetName, sender.getName(), durationStr, reason));

            // Confirm to sender
            sender.sendMessage(localeManager.getComponent("moderator.tempban.success", targetName, durationStr, reason));
        } else {
            sender.sendMessage(localeManager.getComponent("moderator.error.never_played", targetName));
            plugin.getLogger().info("Tempban command failed: Player " + targetName + " has never played on this server");
        }

        return true;
    }

    private boolean handleKickCommand(@NotNull CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.kick")) {
            sender.sendMessage(localeManager.getComponent("moderator.error.no_permission"));
            plugin.getLogger().info("Kick command failed: " + sender.getName() + " lacks permission");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(localeManager.getComponent("moderator.kick.usage"));
            plugin.getLogger().info("Kick command failed: Invalid arguments from " + sender.getName());
            return true;
        }

        String targetName = args[0];
        String reason = String.join(" ", args).substring(args[0].length()).trim();
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            sender.sendMessage(localeManager.getComponent("moderator.error.player_not_found"));
            plugin.getLogger().info("Kick command failed: Target player " + targetName + " not found");
            return true;
        }

        // Check if sender is trying to kick themselves
        if (sender instanceof Player && target.getUniqueId().equals(((Player) sender).getUniqueId())) {
            sender.sendMessage(localeManager.getComponent("moderator.kick.cannot_self"));
            plugin.getLogger().info("Kick command failed: " + sender.getName() + " tried to kick themselves");
            return true;
        }

        plugin.getLogManager().logBanKick(sender.getName(), "KICK", targetName, "N/A", reason);
        target.kick(localeManager.getComponent("moderator.kick.message", sender.getName(), reason));

        // Broadcast the kick
        Bukkit.broadcast(
            localeManager.getComponent("moderator.kick.broadcast", targetName, sender.getName(), reason),
            "amgcore.kick.notify"
        );
        plugin.getLogger().info(localeManager.getMessage("moderator.kick.broadcast", targetName, sender.getName(), reason));

        // Confirm to sender
        sender.sendMessage(localeManager.getComponent("moderator.kick.success", targetName, reason));

        return true;
    }

    private boolean handleUnbanCommand(@NotNull CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.unban")) {
            sender.sendMessage(localeManager.getComponent("moderator.error.no_permission"));
            plugin.getLogger().info("Unban command failed: " + sender.getName() + " lacks permission");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(localeManager.getComponent("moderator.unban.usage"));
            plugin.getLogger().info("Unban command failed: Invalid arguments from " + sender.getName());
            return true;
        }

        String targetName = args[0];
        
        // Check if player exists
        if (Bukkit.getOfflinePlayer(targetName).hasPlayedBefore() || Bukkit.getPlayer(targetName) != null) {
            if (plugin.getBanManager().unbanPlayer(targetName)) {
                plugin.getLogManager().logModAction(sender.getName(), "UNBAN", targetName, "N/A");
                
                // Confirm to sender
                sender.sendMessage(localeManager.getComponent("moderator.unban.success", targetName));
                
                // Broadcast the unban
                Bukkit.broadcast(
                    localeManager.getComponent("moderator.unban.broadcast", targetName, sender.getName()),
                    "amgcore.ban.notify"
                );
                plugin.getLogger().info(localeManager.getMessage("moderator.unban.broadcast", targetName, sender.getName()));
            } else {
                sender.sendMessage(localeManager.getComponent("moderator.unban.not_banned", targetName));
                plugin.getLogger().info("Unban command failed: " + targetName + " is not banned");
            }
        } else {
            sender.sendMessage(localeManager.getComponent("moderator.error.never_played", targetName));
            plugin.getLogger().info("Unban command failed: Player " + targetName + " has never played on this server");
        }

        return true;
    }

    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return -1;
        }

        long totalMillis = 0;
        StringBuilder current = new StringBuilder();
        
        for (char c : duration.toLowerCase().toCharArray()) {
            if (Character.isDigit(c)) {
                current.append(c);
            } else {
                if (current.length() > 0) {
                    try {
                        int value = Integer.parseInt(current.toString());
                        switch (c) {
                            case 'd' -> totalMillis += value * 24L * 60L * 60L * 1000L;
                            case 'h' -> totalMillis += value * 60L * 60L * 1000L;
                            case 'm' -> totalMillis += value * 60L * 1000L;
                            default -> {
                                plugin.getLogger().warning("Invalid duration unit: " + c);
                                return -1; // Invalid format
                            }
                        }
                        current = new StringBuilder();
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Invalid duration number: " + current);
                        return -1;
                    }
                }
            }
        }
        
        // Check if there are any remaining digits without a unit
        if (current.length() > 0) {
            plugin.getLogger().warning("Duration has digits without unit: " + current);
            return -1;
        }
        
        // Ensure at least one valid duration was parsed
        if (totalMillis == 0) {
            plugin.getLogger().warning("Duration parsed to zero: " + duration);
            return -1;
        }
        
        return totalMillis;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            String partialName = args[0].toLowerCase();
            completions = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partialName))
                .collect(Collectors.toList());
        } else if (args.length == 2 && command.getName().equalsIgnoreCase("tempban")) {
            completions.addAll(List.of("1d", "7d", "14d", "30d", "1h", "12h", "24h", "1m", "30m", "60m"));
        }
        
        return completions;
    }
} 