package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.managers.PlayerDataManager;
import amg.plugins.aMGCore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class AdvertisementCommand implements CommandExecutor, TabCompleter {
    private final LocaleManager localeManager;
    private final PlayerDataManager playerDataManager;
    private final Map<UUID, Long> cooldowns;
    private static final long COOLDOWN_TIME = TimeUnit.MINUTES.toMillis(5); // 5 minutes cooldown
    private static final double AD_FEE = 500.0; // 500 dollar fee

    public AdvertisementCommand(AMGCore plugin) {
        this.localeManager = plugin.getLocaleManager();
        this.playerDataManager = plugin.getPlayerDataManager();
        this.cooldowns = new ConcurrentHashMap<>();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("general.player_only"));
            return true;
        }

        if (!player.hasPermission("amgcore.command.ad")) {
            player.sendMessage(localeManager.getComponent("chat.ads.no_permission"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(localeManager.getComponent("chat.ads.usage"));
            return true;
        }

        // Check cooldown
        UUID playerUuid = player.getUniqueId();
        long lastUsed = cooldowns.getOrDefault(playerUuid, 0L);
        long timeLeft = (lastUsed + COOLDOWN_TIME) - System.currentTimeMillis();

        if (timeLeft > 0) {
            player.sendMessage(localeManager.getComponent("chat.ads.cooldown", timeLeft / 1000));
            return true;
        }

        // Get player data
        PlayerData playerData = playerDataManager.getPlayerData(player);
        if (playerData == null) {
            player.sendMessage(localeManager.getComponent("general.error.data_load"));
            return true;
        }

        // Check if player has enough money
        if (!playerData.hasMoney(AD_FEE)) {
            player.sendMessage(localeManager.getComponent("chat.ads.insufficient_funds", String.valueOf(AD_FEE)));
            return true;
        }

        // Join args into message
        String message = String.join(" ", args);

        // Take the fee
        playerData.removeMoney(AD_FEE);

        // Broadcast advertisement
        Bukkit.broadcast(localeManager.getComponent("chat.ads.format", player.getName(), message));
        player.sendMessage(localeManager.getComponent("chat.ads.success"));

        // Set cooldown
        cooldowns.put(playerUuid, System.currentTimeMillis());
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return new ArrayList<>();
    }
}