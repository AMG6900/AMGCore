package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class EconomyCommands implements CommandExecutor, TabCompleter {
    private final AMGCore plugin;
    private final DecimalFormat currencyFormat;
    private final LocaleManager localeManager;
    
    public EconomyCommands(AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();
        this.currencyFormat = new DecimalFormat("#,##0.00");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("money") || command.getName().equalsIgnoreCase("balance")) {
            return handleMoney(sender, args);
        } else if (command.getName().equalsIgnoreCase("givemoney")) {
            return handleGiveMoney(sender, args);
        } else if (command.getName().equalsIgnoreCase("setmoney")) {
            return handleSetMoney(sender, args);
        } else if (command.getName().equalsIgnoreCase("balancetop") || command.getName().equalsIgnoreCase("baltop")) {
            return handleBalanceTop(sender, args);
        }
        
        return false;
    }

    private boolean handleMoney(CommandSender sender, String[] args) {
        if (args.length == 0) {
            // Check own balance
            if (!(sender instanceof Player player)) {
                sender.sendMessage(localeManager.getComponent("money.console_usage"));
                return true;
            }
            
            PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
            if (data == null) {
                player.sendMessage(localeManager.getComponent("money.error.data_load"));
                return true;
            }
            
            double balance = data.getMoney();
            player.sendMessage(localeManager.getComponent("money.balance_self", formatMoney(balance)));
            return true;
        } else {
            // Check another player's balance
            if (!sender.hasPermission("amgcore.command.money.others")) {
                sender.sendMessage(localeManager.getComponent("money.no_permission_others"));
                return true;
            }
            
            String targetName = args[0];
            Player targetPlayer = Bukkit.getPlayer(targetName);
            
            if (targetPlayer != null && targetPlayer.isOnline()) {
                // Target is online
                PlayerData data = plugin.getPlayerDataManager().getPlayerData(targetPlayer);
                if (data == null) {
                    sender.sendMessage(localeManager.getComponent("money.error.data_load_other", targetName));
                    return true;
                }
                
                double balance = data.getMoney();
                sender.sendMessage(localeManager.getComponent("money.balance_other", targetName, formatMoney(balance)));
                return true;
            } else {
                // Target is offline - try to load from database
                sender.sendMessage(localeManager.getComponent("money.checking_offline"));
                
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try (Connection conn = plugin.getDatabaseManager().getConnection();
                         PreparedStatement stmt = conn.prepareStatement("SELECT money FROM player_data WHERE name = ?")) {
                        
                        stmt.setString(1, targetName);
                        ResultSet rs = stmt.executeQuery();
                        
                        if (rs.next()) {
                            double balance = rs.getDouble("money");
                            Bukkit.getScheduler().runTask(plugin, () -> 
                                sender.sendMessage(localeManager.getComponent("money.balance_other", targetName, formatMoney(balance)))
                            );
                        } else {
                            Bukkit.getScheduler().runTask(plugin, () -> 
                                sender.sendMessage(localeManager.getComponent("money.error.player_not_found", targetName))
                            );
                        }
                    } catch (SQLException e) {
                        Bukkit.getScheduler().runTask(plugin, () -> 
                            sender.sendMessage(localeManager.getComponent("money.error.database_error", e.getMessage()))
                        );
                    }
                });
                
                return true;
            }
        }
    }

    private boolean handleGiveMoney(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.givemoney")) {
            sender.sendMessage(localeManager.getComponent("money.givemoney.no_permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(localeManager.getComponent("money.givemoney.usage"));
            return true;
        }

        String targetName = args[0];
        Player targetPlayer = Bukkit.getPlayer(targetName);
        
        if (targetPlayer == null) {
            sender.sendMessage(localeManager.getComponent("money.error.player_not_found", targetName));
            return true;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount <= 0) {
                sender.sendMessage(localeManager.getComponent("money.error.amount_positive"));
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(localeManager.getComponent("money.error.invalid_amount", args[1]));
            return true;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(targetPlayer);
        if (data == null) {
            sender.sendMessage(localeManager.getComponent("money.error.data_load_other", targetName));
            return true;
        }

        data.addMoney(amount);
        double newBalance = data.getMoney();
        
        // Save changes
        plugin.getPlayerDataManager().savePlayer(targetPlayer);
        
        // Notify sender and target
        sender.sendMessage(localeManager.getComponent("money.givemoney.success", formatMoney(amount), targetName, formatMoney(newBalance)));
        
        if (sender != targetPlayer) {
            targetPlayer.sendMessage(localeManager.getComponent("money.givemoney.received", formatMoney(amount), sender.getName(), formatMoney(newBalance)));
        }
        
        return true;
    }

    private boolean handleSetMoney(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.setmoney")) {
            sender.sendMessage(localeManager.getComponent("money.setmoney.no_permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(localeManager.getComponent("money.setmoney.usage"));
            return true;
        }

        String targetName = args[0];
        Player targetPlayer = Bukkit.getPlayer(targetName);
        
        if (targetPlayer == null) {
            sender.sendMessage(localeManager.getComponent("money.error.player_not_found", targetName));
            return true;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount < 0) {
                sender.sendMessage(localeManager.getComponent("money.error.amount_negative"));
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(localeManager.getComponent("money.error.invalid_amount", args[1]));
            return true;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(targetPlayer);
        if (data == null) {
            sender.sendMessage(localeManager.getComponent("money.error.data_load_other", targetName));
            return true;
        }

        data.setMoney(amount);
        
        // Save changes
        plugin.getPlayerDataManager().savePlayer(targetPlayer);
        
        // Notify sender and target
        sender.sendMessage(localeManager.getComponent("money.setmoney.success", targetName, formatMoney(amount)));
        
        if (sender != targetPlayer) {
            targetPlayer.sendMessage(localeManager.getComponent("money.setmoney.changed", formatMoney(amount), sender.getName()));
        }
        
        return true;
    }

    private boolean handleBalanceTop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.balancetop")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return true;
        }

        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) {
                    page = 1;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(localeManager.getComponent("money.baltop.error.invalid_page", args[0]));
                return true;
            }
        }

        final int pageSize = 10;
        final int offset = (page - 1) * pageSize;
        final int finalPage = page;

        sender.sendMessage(localeManager.getComponent("money.baltop.loading"));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                    SELECT name, money
                    FROM player_data
                    ORDER BY money DESC
                    LIMIT ? OFFSET ?
                 """)) {
                
                stmt.setInt(1, pageSize);
                stmt.setInt(2, offset);
                
                ResultSet rs = stmt.executeQuery();
                
                List<Map.Entry<String, Double>> topPlayers = new ArrayList<>();
                while (rs.next()) {
                    String name = rs.getString("name");
                    double money = rs.getDouble("money");
                    topPlayers.add(new AbstractMap.SimpleEntry<>(name, money));
                }
                
                // Get total count for pagination
                int totalPlayers;
                try (PreparedStatement countStmt = conn.prepareStatement("SELECT COUNT(*) FROM player_data")) {
                    ResultSet countRs = countStmt.executeQuery();
                    countRs.next();
                    totalPlayers = countRs.getInt(1);
                }
                
                int totalPages = (int) Math.ceil((double) totalPlayers / pageSize);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(localeManager.getComponent("money.baltop.header", finalPage, totalPages));
                    
                    if (topPlayers.isEmpty()) {
                        sender.sendMessage(localeManager.getComponent("money.baltop.empty"));
                    } else {
                        for (int i = 0; i < topPlayers.size(); i++) {
                            Map.Entry<String, Double> entry = topPlayers.get(i);
                            int rank = offset + i + 1;
                            sender.sendMessage(localeManager.getComponent("money.baltop.entry", rank, entry.getKey(), formatMoney(entry.getValue())));
                        }
                    }
                    
                    sender.sendMessage(localeManager.getComponent("money.baltop.footer"));
                });
                
            } catch (SQLException e) {
                Bukkit.getScheduler().runTask(plugin, () -> 
                    sender.sendMessage(localeManager.getComponent("money.error.database_error", e.getMessage()))
                );
            }
        });
        
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("money") || 
            command.getName().equalsIgnoreCase("givemoney") || 
            command.getName().equalsIgnoreCase("setmoney")) {
            
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
    
    private String formatMoney(double amount) {
        return currencyFormat.format(amount);
    }
} 