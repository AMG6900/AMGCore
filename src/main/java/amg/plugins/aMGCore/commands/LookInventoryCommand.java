package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.utils.DebugLogger;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class LookInventoryCommand implements CommandExecutor, TabCompleter, Listener {
    private final AMGCore plugin;
    private final LocaleManager localeManager;
    private final Map<UUID, UUID> openInventories; // Key: Viewer UUID, Value: Target UUID
    private final Map<UUID, BukkitTask> updateTasks; // Key: Viewer UUID, Value: Update Task

    public LookInventoryCommand(AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();
        this.openInventories = new HashMap<>();
        this.updateTasks = new HashMap<>();
        
        // Register events
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(localeManager.getComponent("command.players_only"));
            return true;
        }

        if (!viewer.hasPermission("amgcore.command.lookinventory")) {
            sender.sendMessage(localeManager.getComponent("command.no_permission"));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(localeManager.getComponent("inventory.look.usage"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(localeManager.getComponent("command.player_not_found", args[0]));
            return true;
        }

        if (target == viewer && !viewer.hasPermission("amgcore.command.lookinventory.self")) {
            sender.sendMessage(localeManager.getComponent("inventory.look.error.no_permission_self"));
            return true;
        }

        // Create and open the inventory viewer
        openInventoryViewer(viewer, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            String partialName = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partialName))
                .sorted()
                .toList();
        }
        return Collections.emptyList();
    }

    private void openInventoryViewer(Player viewer, Player target) {
        // Create a copy of the target's inventory
        Inventory inv = Bukkit.createInventory(
            new InventoryViewerHolder(target.getUniqueId()),
            36, // Main inventory size
            localeManager.getComponent("inventory.look.title", target.getName())
        );
        
        // Copy the target's inventory contents
        updateInventoryContents(inv, target);
        
        // Open the inventory and track it
        viewer.openInventory(inv);
        openInventories.put(viewer.getUniqueId(), target.getUniqueId());
        
        // Start update task
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!viewer.isOnline() || !target.isOnline()) {
                closeInventoryViewer(viewer);
                return;
            }
            
            if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof InventoryViewerHolder) {
                updateInventoryContents(viewer.getOpenInventory().getTopInventory(), target);
            } else {
                closeInventoryViewer(viewer);
            }
        }, 1L, 1L); // Update every tick
        
        updateTasks.put(viewer.getUniqueId(), task);
        
        if (plugin.isDebugEnabled()) {
            DebugLogger.debug("LookInventory", viewer.getName() + " opened " + target.getName() + "'s inventory");
        }
    }

    private void updateInventoryContents(Inventory inv, Player target) {
        ItemStack[] contents = target.getInventory().getContents();
        for (int i = 0; i < Math.min(contents.length, 36); i++) {
            ItemStack currentItem = inv.getItem(i);
            ItemStack newItem = contents[i] != null ? contents[i].clone() : null;
            
            // Only update if the items are different
            if (!Objects.equals(currentItem, newItem)) {
                inv.setItem(i, newItem);
            }
        }
    }

    private void closeInventoryViewer(Player viewer) {
        // Cancel update task
        BukkitTask task = updateTasks.remove(viewer.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        
        // Remove from tracking
        openInventories.remove(viewer.getUniqueId());
        
        // Close inventory if still open
        if (viewer.isOnline() && viewer.getOpenInventory().getTopInventory().getHolder() instanceof InventoryViewerHolder) {
            viewer.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        if (!openInventories.containsKey(viewer.getUniqueId())) return;
        
        // Cancel the event to prevent inventory modification
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        if (!openInventories.containsKey(viewer.getUniqueId())) return;
        
        // Cancel the event to prevent inventory modification
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) return;
        
        // Close inventory viewer if it's one of ours
        if (openInventories.containsKey(viewer.getUniqueId())) {
            closeInventoryViewer(viewer);
        }
    }

    private static class InventoryViewerHolder implements InventoryHolder {
        private final UUID targetUuid;

        public InventoryViewerHolder(UUID targetUuid) {
            this.targetUuid = targetUuid;
        }

        @SuppressWarnings("unused")
        public UUID getTargetUuid() {
            return targetUuid;
        }

        @NotNull
        @Override
        public Inventory getInventory() {
            throw new UnsupportedOperationException("This is a virtual inventory holder");
        }
    }
} 