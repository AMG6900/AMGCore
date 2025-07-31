package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.JailManager;
import amg.plugins.aMGCore.managers.LocaleManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class JailCommands implements CommandExecutor, TabCompleter {
    private final AMGCore plugin;
    private JailManager jailManager;
    private final LocaleManager localeManager;

    public JailCommands(AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();
    }

    private boolean ensureManagersLoaded() {
        if (jailManager == null) {
            if (!plugin.isModuleEnabled("jail")) {
                return false;
            }
            jailManager = (JailManager) plugin.getManager("jail");
            return jailManager != null;
        }
        return true;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!ensureManagersLoaded()) {
            sender.sendMessage(localeManager.getComponent("jail.module_unavailable"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("createjail")) {
            return handleCreateJail(sender, args);
        } else if (command.getName().equalsIgnoreCase("jail")) {
            return handleJail(sender, args);
        } else if (command.getName().equalsIgnoreCase("unjail")) {
            return handleUnjail(sender, args);
        } else if (command.getName().equalsIgnoreCase("deletejail")) {
            return handleDeleteJail(sender, args);
        } else if (command.getName().equalsIgnoreCase("jaillist")) {
            return handleJailList(sender, args);
        }
        return false;
    }

    private boolean handleCreateJail(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return true;
        }

        if (!player.hasPermission("amgcore.command.createjail")) {
            player.sendMessage(localeManager.getComponent("jail.no_permission"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(localeManager.getComponent("jail.create.usage"));
            return true;
        }

        String jailName = String.join(" ", args);
        
        // Create jail at player's location
        boolean success = jailManager.createJail(jailName, player.getLocation(), player.getUniqueId());
        
        if (success) {
            player.sendMessage(localeManager.getComponent("jail.create.success", jailName));
        } else {
            player.sendMessage(localeManager.getComponent("jail.create.exists", jailName));
        }
        
        return true;
    }

    private boolean handleJail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.jail")) {
            sender.sendMessage(localeManager.getComponent("jail.no_permission"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(localeManager.getComponent("jail.jail.usage"));
            sender.sendMessage(localeManager.getComponent("jail.time.format"));
            return true;
        }

        // Get target player
        Player targetPlayer = Bukkit.getPlayer(args[0]);
        if (targetPlayer == null) {
            sender.sendMessage(localeManager.getComponent("command.player_not_found", args[0]));
            return true;
        }

        // Get jail name
        String jailName = args[1];
        
        // Parse time
        String timeStr = args[2];
        long timeMillis;
        try {
            timeMillis = parseTime(timeStr);
            if (timeMillis <= 0) {
                sender.sendMessage(localeManager.getComponent("jail.time.invalid_format"));
                return true;
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(localeManager.getComponent("jail.time.invalid_format"));
            return true;
        }
        
        // Get reason (optional)
        String reason = null;
        if (args.length > 3) {
            reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        }
        
        // Get sender UUID
        java.util.UUID senderUuid;
        if (sender instanceof Player) {
            senderUuid = ((Player) sender).getUniqueId();
        } else {
            // Use a special UUID for console
            senderUuid = new java.util.UUID(0, 0);
        }
        
        // Jail the player
        boolean success = jailManager.jailPlayer(targetPlayer.getUniqueId(), jailName, senderUuid, reason, timeMillis);
        
        if (success) {
            sender.sendMessage(localeManager.getComponent("jail.jail.success", 
                targetPlayer.getName(), 
                jailName, 
                formatTime(timeMillis), 
                reason != null ? reason : "No reason provided"));
            targetPlayer.sendMessage(localeManager.getComponent("jail.jail.target", 
                jailName, 
                formatTime(timeMillis), 
                reason != null ? reason : "No reason provided"));
        } else {
            sender.sendMessage(localeManager.getComponent("jail.jail.already_jailed", targetPlayer.getName()));
        }
        
        return true;
    }

    private boolean handleUnjail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.unjail")) {
            sender.sendMessage(localeManager.getComponent("jail.no_permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(localeManager.getComponent("jail.unjail.usage"));
            return true;
        }

        // Get target player
        Player targetPlayer = Bukkit.getPlayer(args[0]);
        if (targetPlayer == null) {
            sender.sendMessage(localeManager.getComponent("command.player_not_found", args[0]));
            return true;
        }

        // Unjail the player
        boolean success = jailManager.unjailPlayer(targetPlayer.getUniqueId());
        
        if (success) {
            sender.sendMessage(localeManager.getComponent("jail.unjail.success", targetPlayer.getName()));
            targetPlayer.sendMessage(localeManager.getComponent("jail.unjail.target"));
        } else {
            sender.sendMessage(localeManager.getComponent("jail.unjail.not_jailed", targetPlayer.getName()));
        }
        
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("jail")) {
            if (args.length == 1) {
                // Tab complete player names
                return getOnlinePlayerNames(args[0]);
            } else if (args.length == 2) {
                // Tab complete jail names
                return getMatchingJailNames(args[1]);
            } else if (args.length == 3) {
                // Tab complete time suggestions
                return getTimeSuggestions(args[2]);
            }
        } else if (command.getName().equalsIgnoreCase("unjail")) {
            if (args.length == 1) {
                // Tab complete player names
                return getOnlinePlayerNames(args[0]);
            }
        }
        
        return Collections.emptyList();
    }

    private List<String> getOnlinePlayerNames(String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(name -> name.toLowerCase().startsWith(lowerPrefix))
            .collect(Collectors.toList());
    }

    private List<String> getMatchingJailNames(String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        return jailManager.getJailNames().stream()
            .filter(name -> name.toLowerCase().startsWith(lowerPrefix))
            .collect(Collectors.toList());
    }

    private List<String> getTimeSuggestions(String prefix) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("1h");
        suggestions.add("1d");
        suggestions.add("30m");
        suggestions.add("1d12h");
        suggestions.add("7d");
        
        if (prefix.isEmpty()) {
            return suggestions;
        }
        
        return suggestions.stream()
            .filter(s -> s.startsWith(prefix))
            .collect(Collectors.toList());
    }

    /**
     * Parses a time string in the format 1d2h3m4s to milliseconds.
     * 
     * @param timeStr The time string to parse
     * @return The time in milliseconds
     * @throws IllegalArgumentException if the time string is invalid
     */
    private long parseTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            throw new IllegalArgumentException("Time cannot be empty");
        }
        
        long totalMillis = 0;
        StringBuilder numBuilder = new StringBuilder();
        
        for (int i = 0; i < timeStr.length(); i++) {
            char c = timeStr.charAt(i);
            
            if (Character.isDigit(c)) {
                numBuilder.append(c);
            } else {
                if (numBuilder.length() == 0) {
                    throw new IllegalArgumentException("Invalid time format: " + timeStr);
                }
                
                int num;
                try {
                    num = Integer.parseInt(numBuilder.toString());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid number in time: " + numBuilder);
                }
                
                numBuilder.setLength(0);
                
                switch (c) {
                    case 'd':
                        totalMillis += TimeUnit.DAYS.toMillis(num);
                        break;
                    case 'h':
                        totalMillis += TimeUnit.HOURS.toMillis(num);
                        break;
                    case 'm':
                        totalMillis += TimeUnit.MINUTES.toMillis(num);
                        break;
                    case 's':
                        totalMillis += TimeUnit.SECONDS.toMillis(num);
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid time unit: " + c);
                }
            }
        }
        
        // Handle case where the string ends with a number
        if (numBuilder.length() > 0) {
            throw new IllegalArgumentException("Time string must end with a unit (d, h, m, s)");
        }
        
        return totalMillis;
    }

    /**
     * Formats time in milliseconds to a human-readable string.
     * 
     * @param millis Time in milliseconds
     * @return Formatted time string
     */
    private String formatTime(long millis) {
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        millis -= TimeUnit.DAYS.toMillis(days);
        
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        millis -= TimeUnit.HOURS.toMillis(hours);
        
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        millis -= TimeUnit.MINUTES.toMillis(minutes);
        
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" day").append(days > 1 ? "s" : "").append(" ");
        }
        if (hours > 0) {
            sb.append(hours).append(" hour").append(hours > 1 ? "s" : "").append(" ");
        }
        if (minutes > 0) {
            sb.append(minutes).append(" minute").append(minutes > 1 ? "s" : "").append(" ");
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append(" second").append(seconds != 1 ? "s" : "");
        } else {
            // Remove trailing space
            sb.setLength(sb.length() - 1);
        }
        
        return sb.toString();
    }

    private boolean handleDeleteJail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.deletejail")) {
            sender.sendMessage(localeManager.getComponent("jail.no_permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(localeManager.getComponent("jail.delete.usage"));
            return true;
        }

        String jailName = args[0];
        
        // Delete the jail
        boolean success = jailManager.deleteJail(jailName);
        
        if (success) {
            sender.sendMessage(localeManager.getComponent("jail.delete.success", jailName));
        } else {
            sender.sendMessage(localeManager.getComponent("jail.delete.not_found", jailName));
        }
        
        return true;
    }

    private boolean handleJailList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.jaillist")) {
            sender.sendMessage(localeManager.getComponent("jail.no_permission"));
            return true;
        }

        List<String> jails = jailManager.getJailNames();
        
        if (jails.isEmpty()) {
            sender.sendMessage(localeManager.getComponent("jail.list.empty"));
            return true;
        }
        
        sender.sendMessage(localeManager.getComponent("jail.list.header"));
        for (String jail : jails) {
            sender.sendMessage(localeManager.getComponent("jail.list.entry", jail));
        }
        
        return true;
    }
} 