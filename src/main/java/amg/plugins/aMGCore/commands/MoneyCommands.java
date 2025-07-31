package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.CoreAPI;
import amg.plugins.aMGCore.managers.LocaleManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class MoneyCommands implements CommandExecutor, TabCompleter {
    private final DecimalFormat moneyFormat;
    private final AMGCore plugin;
    private final LocaleManager localeManager;

    public MoneyCommands(AMGCore plugin, Object playerDataManager) {
        this.plugin = plugin;
        this.moneyFormat = new DecimalFormat("#,##0.00");
        this.localeManager = plugin.getLocaleManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        try {
            // Check if money module is available
            if (!plugin.isModuleEnabled("money")) {
                sender.sendMessage(localeManager.getComponent("money.module_unavailable"));
                return true;
            }

            switch (command.getName().toLowerCase()) {
                case "money", "balance" -> handleMoney(sender, args);
                case "givemoney" -> handleGiveMoney(sender, args);
                case "setmoney" -> handleSetMoney(sender, args);
                case "balancetop", "baltop" -> handleBalanceTop(sender);
                default -> {
                    sender.sendMessage(localeManager.getComponent("money.unknown"));
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            sender.sendMessage(localeManager.getComponent("money.error_occurred"));
            return true;
        }
    }

    private void handleMoney(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(localeManager.getComponent("money.usage"));
            return;
        }

        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(localeManager.getComponent("money.console_usage"));
                return;
            }
            target = (Player) sender;
        } else {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(localeManager.getComponent("money.player_not_found", args[0]));
                return;
            }

            if (!sender.hasPermission("amgcore.command.money.others")) {
                sender.sendMessage(localeManager.getComponent("money.no_permission_others"));
                return;
            }
        }

        double balance = CoreAPI.getMoney(target);
        if (target == sender) {
            sender.sendMessage(localeManager.getComponent("money.balance_self", moneyFormat.format(balance)));
        } else {
            sender.sendMessage(localeManager.getComponent("money.balance_other", target.getName(), moneyFormat.format(balance)));
        }
    }

    private void handleGiveMoney(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.givemoney")) {
            sender.sendMessage(localeManager.getComponent("money.givemoney.no_permission"));
            return;
        }

        if (args.length != 2) {
            sender.sendMessage(localeManager.getComponent("money.givemoney.usage"));
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(localeManager.getComponent("money.player_not_found", args[0]));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount <= 0) {
                sender.sendMessage(localeManager.getComponent("money.error.amount_positive"));
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(localeManager.getComponent("money.error.invalid_amount", args[1]));
            return;
        }

        CoreAPI.addMoney(target, amount);
        double newBalance = CoreAPI.getMoney(target);
        
        sender.sendMessage(localeManager.getComponent("money.givemoney.success", 
            moneyFormat.format(amount), target.getName(), moneyFormat.format(newBalance)));
        
        if (target != sender) {
            target.sendMessage(localeManager.getComponent("money.givemoney.received", 
                moneyFormat.format(amount), sender.getName(), moneyFormat.format(newBalance)));
        }
    }

    private void handleSetMoney(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.setmoney")) {
            sender.sendMessage(localeManager.getComponent("money.setmoney.no_permission"));
            return;
        }

        if (args.length != 2) {
            sender.sendMessage(localeManager.getComponent("money.setmoney.usage"));
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(localeManager.getComponent("money.player_not_found", args[0]));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount < 0) {
                sender.sendMessage(localeManager.getComponent("money.error.amount_negative"));
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(localeManager.getComponent("money.error.invalid_amount", args[1]));
            return;
        }

        CoreAPI.setMoney(target, amount);
        
        sender.sendMessage(localeManager.getComponent("money.setmoney.success", 
            target.getName(), moneyFormat.format(amount)));
        
        if (target != sender) {
            target.sendMessage(localeManager.getComponent("money.setmoney.changed", 
                moneyFormat.format(amount), sender.getName()));
        }
    }

    private void handleBalanceTop(CommandSender sender) {
        if (!sender.hasPermission("amgcore.command.balancetop")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        // Get all online players and their balances
        Map<String, Double> balances = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            balances.put(player.getName(), CoreAPI.getMoney(player));
        }

        // Sort by balance (descending)
        List<Map.Entry<String, Double>> sortedBalances = balances.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .collect(Collectors.toList());

        sender.sendMessage(localeManager.getComponent("money.baltop.header"));
        
        if (sortedBalances.isEmpty()) {
            sender.sendMessage(localeManager.getComponent("money.baltop.empty"));
            return;
        }

        // Show top 10
        for (int i = 0; i < Math.min(10, sortedBalances.size()); i++) {
            Map.Entry<String, Double> entry = sortedBalances.get(i);
            sender.sendMessage(localeManager.getComponent("money.baltop.entry", 
                i + 1, entry.getKey(), moneyFormat.format(entry.getValue())));
        }

        sender.sendMessage(localeManager.getComponent("money.baltop.footer"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        switch (command.getName().toLowerCase()) {
            case "money", "balance" -> {
                if (args.length == 1 && sender.hasPermission("amgcore.command.money.others")) {
                    String partialName = args[0].toLowerCase();
                    Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partialName))
                        .forEach(completions::add);
                }
            }
            case "givemoney", "setmoney" -> {
                if (args.length == 1) {
                    String partialName = args[0].toLowerCase();
                    Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partialName))
                        .forEach(completions::add);
                } else if (args.length == 2) {
                    String partial = args[1].toLowerCase();
                    List<String> amounts = List.of("100", "1000", "10000", "100000");
                    amounts.stream()
                        .filter(amount -> amount.startsWith(partial))
                        .forEach(completions::add);
                }
            }
        }
        
        Collections.sort(completions);
        return completions;
    }
} 