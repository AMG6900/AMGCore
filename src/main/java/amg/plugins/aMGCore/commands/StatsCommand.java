package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.PlaytimeManager;
import amg.plugins.aMGCore.managers.StatsManager;
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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class StatsCommand implements CommandExecutor, TabCompleter {
    private final StatsManager statsManager;
    private final PlaytimeManager playtimeManager;
    private final LocaleManager localeManager;
    private final DateTimeFormatter dateFormatter;

    public StatsCommand(AMGCore plugin) {
        // Make sure the stats module is enabled
        if (!plugin.isModuleEnabled("stats")) {
            plugin.getLogger().info("StatsCommand: Enabling stats module");
            plugin.getModuleRegistry().enableModule("stats");
        }
        
        // Make sure the playtime module is enabled
        if (!plugin.isModuleEnabled("playtime")) {
            plugin.getLogger().info("StatsCommand: Enabling playtime module");
            plugin.getModuleRegistry().enableModule("playtime");
        }
        
        try {
            this.statsManager = (StatsManager) plugin.getManager("stats");
            if (this.statsManager == null) {
                plugin.getLogger().severe("StatsCommand: Failed to get StatsManager - it's null!");
            } else {
                plugin.getLogger().info("StatsCommand: Successfully got StatsManager");
            }
            
            this.playtimeManager = plugin.getPlaytimeManager();
            if (this.playtimeManager == null) {
                plugin.getLogger().severe("StatsCommand: Failed to get PlaytimeManager - it's null!");
            } else {
                plugin.getLogger().info("StatsCommand: Successfully got PlaytimeManager");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("StatsCommand: Error getting managers: " + e.getMessage());
            throw e;
        }
        
        this.localeManager = plugin.getLocaleManager();
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        try {
            // Check if statsManager is null
            if (statsManager == null) {
                sender.sendMessage(localeManager.getComponent("stats.error.manager_unavailable"));
                return true;
            }
            
            Player target;
            if (args.length > 0 && sender.hasPermission("amgcore.command.stats.others")) {
                target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage(localeManager.getComponent("stats.error.player_not_found"));
                    return true;
                }
            } else if (sender instanceof Player) {
                target = (Player) sender;
            } else {
                sender.sendMessage(localeManager.getComponent("stats.error.console_must_specify"));
                return true;
            }

            StatsManager.PlayerStats stats = statsManager.getPlayerStats(target.getUniqueId());
            if (stats == null) {
                sender.sendMessage(localeManager.getComponent("stats.error.no_stats_found", target.getName()));
                return true;
            }

            // Get playtime from PlaytimeManager if available, otherwise use stats
            String playtimeStr;
            if (playtimeManager != null) {
                playtimeStr = playtimeManager.getFormattedPlaytime(target.getUniqueId());
            } else {
                // Format playtime from stats
                Duration playtime = stats.getPlaytime();
                long days = playtime.toDays();
                long hours = playtime.toHoursPart();
                long minutes = playtime.toMinutesPart();
                long seconds = playtime.toSecondsPart();

                StringBuilder sb = new StringBuilder();
                if (days > 0) sb.append(days).append("d ");
                if (hours > 0) sb.append(hours).append("h ");
                if (minutes > 0) sb.append(minutes).append("m ");
                if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append("s");
                playtimeStr = sb.toString();
            }

            // Format last seen
            String lastSeen;
            if (target.isOnline()) {
                lastSeen = localeManager.getMessage("stats.time.online_now");
            } else {
                Duration offlineTime = Duration.between(stats.getLastSeen(), Instant.now());
                if (offlineTime.toDays() > 0) {
                    lastSeen = localeManager.getMessage("stats.time.days_ago", String.valueOf(offlineTime.toDays()));
                } else if (offlineTime.toHours() > 0) {
                    lastSeen = localeManager.getMessage("stats.time.hours_ago", String.valueOf(offlineTime.toHours()));
                } else if (offlineTime.toMinutes() > 0) {
                    lastSeen = localeManager.getMessage("stats.time.minutes_ago", String.valueOf(offlineTime.toMinutes()));
                } else {
                    lastSeen = localeManager.getMessage("stats.time.seconds_ago", String.valueOf(offlineTime.getSeconds()));
                }
            }

            // Send stats message
            sender.sendMessage(localeManager.getComponent("stats.display.header", target.getName()));
            sender.sendMessage(localeManager.getComponent("stats.display.first_join", dateFormatter.format(stats.getFirstJoin())));
            sender.sendMessage(localeManager.getComponent("stats.display.last_seen", lastSeen));
            sender.sendMessage(localeManager.getComponent("stats.display.playtime", playtimeStr));
            sender.sendMessage(localeManager.getComponent("stats.display.kills", 
                String.valueOf(stats.getKills()),
                String.valueOf(stats.getPvPKills()),
                String.valueOf(stats.getMobKills())
            ));
            sender.sendMessage(localeManager.getComponent("stats.display.deaths", String.valueOf(stats.getDeaths())));
            sender.sendMessage(localeManager.getComponent("stats.display.kd_ratio", String.format("%.2f", stats.getKDRatio())));

            return true;
        } catch (Exception e) {
            DebugLogger.severe("Error executing stats command", "StatsCommand", e);
            sender.sendMessage(localeManager.getComponent("stats.error.command_error"));
            return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1 && sender.hasPermission("amgcore.command.stats.others")) {
            completions.addAll(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList()));
        }

        return completions;
    }
} 