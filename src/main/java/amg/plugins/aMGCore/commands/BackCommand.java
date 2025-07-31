package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.managers.TeleportManager;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class BackCommand implements CommandExecutor, TabCompleter {
    private final TeleportManager teleportManager;
    private final LocaleManager localeManager;

    public BackCommand(AMGCore plugin) {

        this.teleportManager = plugin.getTeleportManager();
        this.localeManager = plugin.getLocaleManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("general.player_only"));
            return true;
        }

        if (!player.hasPermission("amgcore.command.back")) {
            player.sendMessage(localeManager.getComponent("general.no_permission"));
            return true;
        }

        Location lastLocation = teleportManager.getLastLocation(player);
        if (lastLocation == null) {
            player.sendMessage(localeManager.getComponent("teleport.back.no_previous"));
            return true;
        }

        // Record current location before teleporting back
        teleportManager.recordLastLocation(player);
        player.teleport(lastLocation);
        player.sendMessage(localeManager.getComponent("teleport.back.teleported"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return new ArrayList<>();
    }
}