package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.TeleportManager;
import amg.plugins.aMGCore.managers.LocaleManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class TeleportCommands implements CommandExecutor, TabCompleter {
    private final TeleportManager teleportManager;
    private final LocaleManager localeManager;

    public TeleportCommands(AMGCore plugin) {
        this.teleportManager = plugin.getTeleportManager();
        this.localeManager = plugin.getLocaleManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "tpa" -> handleTpa(sender, args);
            case "tpahere" -> handleTpaHere(sender, args);
            case "tpaccept" -> handleTpaccept(sender);
            case "tpdeny" -> handleTpdeny(sender);
            case "sethome" -> handleSethome(sender, args);
            case "home" -> handleHome(sender, args);
            case "delhome" -> handleDelhome(sender, args);
            case "setwarp" -> handleSetwarp(sender, args);
            case "warp" -> handleWarp(sender, args);
            case "delwarp" -> handleDelwarp(sender, args);
            case "spawn" -> handleSpawn(sender);
            case "setspawn" -> handleSetspawn(sender);
            case "back" -> handleBack(sender);
            default -> {
                sender.sendMessage(localeManager.getComponent("command.unknown"));
                return false;
            }
        }
        return true;
    }

    private void handleTpaHere(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.tpahere")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        if (args.length != 1) {
            player.sendMessage(localeManager.getComponent("teleport.tpa.usage"));
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(localeManager.getComponent("command.player_not_found", args[0]));
            return;
        }

        if (target == player) {
            player.sendMessage(localeManager.getComponent("teleport.error.self_teleport"));
            return;
        }

        teleportManager.createTeleportRequest(player, target, true);
        player.sendMessage(localeManager.getComponent("teleport.request.sent", target.getName()));
    }

    private void handleTpa(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.tpa")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        if (args.length != 1) {
            player.sendMessage(localeManager.getComponent("teleport.tpa.usage"));
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(localeManager.getComponent("command.player_not_found", args[0]));
            return;
        }

        if (target == player) {
            player.sendMessage(localeManager.getComponent("teleport.error.self_teleport"));
            return;
        }

        teleportManager.sendTeleportRequest(player, target);
    }

    private void handleTpaccept(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.tpaccept")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        if (!teleportManager.acceptTeleportRequest(player)) {
            player.sendMessage(localeManager.getComponent("teleport.error.no_pending_requests"));
        }
    }

    private void handleTpdeny(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.tpdeny")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        if (!teleportManager.denyTeleportRequest(player)) {
            player.sendMessage(localeManager.getComponent("teleport.error.no_pending_requests"));
        }
    }

    private void handleSethome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.sethome")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        String name = args.length > 0 ? args[0].toLowerCase() : "home";
        if (teleportManager.setHome(player, name, player.getLocation())) {
            player.sendMessage(localeManager.getComponent("home.set", name));
        } else {
            player.sendMessage(localeManager.getComponent("home.limit", name));
        }
    }

    private void handleHome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.home")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        if (args.length == 0) {
            // Try to teleport to last used home
            Location home = teleportManager.getLastUsedHome(player);
            if (home != null) {
                teleportManager.recordLastLocation(player);
                player.teleport(home);
                player.sendMessage(localeManager.getComponent("home.teleported", "last used home"));
            } else {
                // No homes exist, show the list
                Set<String> homes = teleportManager.getHomeNames(player);
                if (homes.isEmpty()) {
                    player.sendMessage(localeManager.getComponent("home.list.empty"));
                } else {
                    String homeList = String.join(", ", homes);
                    player.sendMessage(localeManager.getComponent("home.list.header"));
                    player.sendMessage(localeManager.getComponent("home.list.entry", homeList));
                }
            }
            return;
        }

        String name = args[0].toLowerCase();
        Location home = teleportManager.getHome(player, name);
        if (home != null) {
            teleportManager.recordLastLocation(player);
            player.teleport(home);
            player.sendMessage(localeManager.getComponent("home.teleported", name));
        } else {
            player.sendMessage(localeManager.getComponent("home.not_found", name));
        }
    }

    private void handleDelhome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.delhome")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        if (args.length != 1) {
            sender.sendMessage(localeManager.getComponent("home.del_usage"));
            return;
        }

        String name = args[0].toLowerCase();
        if (teleportManager.deleteHome(player, name)) {
            player.sendMessage(localeManager.getComponent("home.deleted", name));
        } else {
            player.sendMessage(localeManager.getComponent("home.not_found", name));
        }
    }

    private void handleSetwarp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.setwarp")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        if (args.length != 1) {
            sender.sendMessage(localeManager.getComponent("warp.set_usage"));
            return;
        }

        String name = args[0].toLowerCase();
        if (teleportManager.setWarp(name, player.getLocation())) {
            player.sendMessage(localeManager.getComponent("warp.set", name));
        } else {
            player.sendMessage(localeManager.getComponent("warp.error.save_failed"));
        }
    }

    private void handleWarp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.warp")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        if (args.length == 0) {
            // List warps
            Set<String> warps = teleportManager.getWarpNames();
            if (warps.isEmpty()) {
                player.sendMessage(localeManager.getComponent("warp.list.empty"));
            } else {
                String warpList = String.join(", ", warps);
                player.sendMessage(localeManager.getComponent("warp.list.header"));
                player.sendMessage(localeManager.getComponent("warp.list.entry", warpList));
            }
            return;
        }

        String name = args[0].toLowerCase();
        Location warp = teleportManager.getWarp(name);
        if (warp != null) {
            teleportManager.recordLastLocation(player);
            player.teleport(warp);
            player.sendMessage(localeManager.getComponent("warp.teleported", name));
        } else {
            player.sendMessage(localeManager.getComponent("warp.not_found", name));
        }
    }

    private void handleDelwarp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.delwarp")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        if (args.length != 1) {
            sender.sendMessage(localeManager.getComponent("warp.del_usage"));
            return;
        }

        String name = args[0].toLowerCase();
        if (teleportManager.deleteWarp(name)) {
            sender.sendMessage(localeManager.getComponent("warp.deleted", name));
        } else {
            sender.sendMessage(localeManager.getComponent("warp.not_found", name));
        }
    }

    private void handleSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.spawn")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        teleportManager.recordLastLocation(player);
        player.teleport(teleportManager.getSpawnLocation());
        player.sendMessage(localeManager.getComponent("teleport.spawn.teleported"));
    }

    private void handleSetspawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.setspawn")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        teleportManager.setSpawnLocation(player.getLocation());
        player.sendMessage(localeManager.getComponent("teleport.spawn.set"));
    }

    private void handleBack(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return;
        }

        if (!player.hasPermission("amgcore.command.back")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return;
        }

        Location lastLocation = teleportManager.getLastLocation(player);
        if (lastLocation != null) {
            teleportManager.recordLastLocation(player); // Record current location before teleporting back
            player.teleport(lastLocation);
            player.sendMessage(localeManager.getComponent("teleport.back.teleported"));
        } else {
            player.sendMessage(localeManager.getComponent("teleport.back.no_previous"));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        switch (command.getName().toLowerCase()) {
            case "tpa" -> {
                if (args.length == 1) {
                    String partialName = args[0].toLowerCase();
                    Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partialName))
                        .forEach(completions::add);
                }
            }
            case "home", "delhome" -> {
                if (args.length == 1 && sender instanceof Player player) {
                    String partial = args[0].toLowerCase();
                    teleportManager.getHomeNames(player).stream()
                        .filter(name -> name.toLowerCase().startsWith(partial))
                        .forEach(completions::add);
                }
            }
            case "warp", "delwarp" -> {
                if (args.length == 1) {
                    String partial = args[0].toLowerCase();
                    teleportManager.getWarpNames().stream()
                        .filter(name -> name.toLowerCase().startsWith(partial))
                        .forEach(completions::add);
                }
            }
        }
        
        Collections.sort(completions);
        return completions;
    }
} 