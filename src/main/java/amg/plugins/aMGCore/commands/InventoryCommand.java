package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class InventoryCommand implements CommandExecutor, TabCompleter, Listener {
    private final AMGCore plugin;
    private final LocaleManager localeManager;
    private final Map<UUID, UUID> openInventories; // Viewer UUID -> Target UUID

    public InventoryCommand(AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();
        this.openInventories = new HashMap<>();
        
        // Register events
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return true;
        }

        if (!player.hasPermission("amgcore.command.inventory")) {
            player.sendMessage(localeManager.getComponent("command.no_permission"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(localeManager.getComponent("inventory.usage"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(localeManager.getComponent("command.player_not_found", args[0]));
            return true;
        }

        // Don't allow viewing own inventory through this command
        if (target.equals(player)) {
            player.sendMessage(localeManager.getComponent("inventory.error.self_view"));
            return true;
        }

        // Open the inventory
        openTargetInventory(player, target);
        return true;
    }

    private void openTargetInventory(Player viewer, Player target) {
        // Create a custom inventory holder to identify our inventory
        InventoryHolder holder = new TargetInventoryHolder(target);
        
        // Create inventory with same size as player inventory (36 slots)
        Inventory inventory = Bukkit.createInventory(holder, 36, 
            localeManager.getComponent("inventory.title", target.getName()));
        
        // Fill with target's inventory contents
        inventory.setContents(target.getInventory().getStorageContents());
        
        // Open the inventory for the viewer
        viewer.openInventory(inventory);
        
        // Track this open inventory
        openInventories.put(viewer.getUniqueId(), target.getUniqueId());
        
        viewer.sendMessage(localeManager.getComponent("inventory.opened", target.getName()));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        if (!(event.getInventory().getHolder() instanceof TargetInventoryHolder holder)) return;
        
        // Get the target player
        Player target = Bukkit.getPlayer(holder.getTargetUUID());
        if (target == null || !target.isOnline()) {
            // Target logged off, close inventory
            viewer.closeInventory();
            viewer.sendMessage(localeManager.getComponent("inventory.error.player_offline"));
            return;
        }
        
        // Update the target's inventory with the changes
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Copy the contents from the viewed inventory to the target's inventory
            for (int i = 0; i < event.getInventory().getSize(); i++) {
                target.getInventory().setItem(i, event.getInventory().getItem(i));
            }
            
            // Update the viewer's view to match any changes that might have happened
            viewer.updateInventory();
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        if (!(event.getInventory().getHolder() instanceof TargetInventoryHolder holder)) return;
        
        // Get the target player
        Player target = Bukkit.getPlayer(holder.getTargetUUID());
        if (target == null || !target.isOnline()) {
            // Target logged off, close inventory
            viewer.closeInventory();
            viewer.sendMessage(localeManager.getComponent("inventory.error.player_offline"));
            return;
        }
        
        // Update the target's inventory with the changes
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Copy the contents from the viewed inventory to the target's inventory
            for (int i = 0; i < event.getInventory().getSize(); i++) {
                target.getInventory().setItem(i, event.getInventory().getItem(i));
            }
            
            // Update the viewer's view to match any changes that might have happened
            viewer.updateInventory();
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) return;
        
        // Remove from tracking map when inventory is closed
        openInventories.remove(viewer.getUniqueId());
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String partialName = args[0].toLowerCase();
            
            // Add online player names that match the partial input
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partialName)) {
                    completions.add(player.getName());
                }
            }
            
            return completions;
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Custom InventoryHolder to identify our inventory and store the target player
     */
    private static class TargetInventoryHolder implements InventoryHolder {
        private final UUID targetUUID;
        
        public TargetInventoryHolder(Player target) {
            this.targetUUID = target.getUniqueId();
        }
        
        public UUID getTargetUUID() {
            return targetUUID;
        }

        @Override
        public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException("This is a virtual holder");
        }
    }
} 