package amg.plugins.aMGCore.events;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.models.PlayerData;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryEvents implements Listener {
    private final AMGCore plugin;
    private final Map<String, Long> lastSaveTime;
    private static final long SAVE_COOLDOWN = 50L; // 50ms cooldown between saves
    private final Map<Player, String> containerTypeCache;

    public InventoryEvents(AMGCore plugin) {
        this.plugin = plugin;
        this.lastSaveTime = new ConcurrentHashMap<>();
        this.containerTypeCache = new WeakHashMap<>();
    }

    private boolean isInventorySaveEnabled(String configKey) {
        FileConfiguration config = plugin.getConfig();
        return config.getBoolean("inventory." + configKey, true);
    }

    private void savePlayerInventory(Player player) {
        if (!player.isOnline()) return;
        
        // Check save cooldown
        String uuid = player.getUniqueId().toString();
        long currentTime = System.currentTimeMillis();
        Long lastSave = lastSaveTime.get(uuid);
        if (lastSave != null && currentTime - lastSave < SAVE_COOLDOWN) {
            return;
        }
        
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data != null) {
            data.loadFromInventory(player.getInventory());
            lastSaveTime.put(uuid, currentTime);
                }
    }
    
    private void logItemMovement(InventoryClickEvent event, Player player) {
        try {
            // Get the current item being clicked
            ItemStack currentItem = event.getCurrentItem();
            ItemStack cursorItem = event.getCursor();
            
            // Skip if both items are null or air
            if ((currentItem == null || currentItem.getType().isAir()) && 
                (cursorItem == null || cursorItem.getType().isAir())) {
                return;
            }
            
            Inventory clickedInventory = event.getClickedInventory();
            // Skip if clicked inventory is null or is player inventory
            if (clickedInventory == null || clickedInventory.equals(player.getInventory())) return;
            
            // Only log when items move FROM container TO player
            switch (event.getAction()) {
                case PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE -> {
                    if (currentItem != null && !currentItem.getType().isAir()) {
                        logContainerAction(player, "TAKEN_FROM", currentItem, getContainerType(clickedInventory));
                    }
                }
                case MOVE_TO_OTHER_INVENTORY -> {
                    if (!clickedInventory.equals(player.getInventory()) && currentItem != null && !currentItem.getType().isAir()) {
                        logContainerAction(player, "MOVED_TO_PLAYER", currentItem, getContainerType(clickedInventory));
                    }
                }
                case HOTBAR_SWAP -> {
                    if (!clickedInventory.equals(player.getInventory()) && currentItem != null && !currentItem.getType().isAir()) {
                        logContainerAction(player, "TAKEN_FROM", currentItem, getContainerType(clickedInventory));
                    }
                }
                case SWAP_WITH_CURSOR -> {
                    if (!clickedInventory.equals(player.getInventory()) && currentItem != null && !currentItem.getType().isAir()) {
                        logContainerAction(player, "TAKEN_FROM", currentItem, getContainerType(clickedInventory));
                            }
                        }
                default -> {} // Ignore other actions
            }
            
            // Debug logging
            if (plugin.isDebugEnabled()) {
                DebugLogger.debug("Inventory", String.format(
                    "Click: %s, Action: %s, Slot: %d, Raw: %d, Current: %s, Cursor: %s",
                    event.getClick(),
                    event.getAction(),
                    event.getSlot(),
                    event.getRawSlot(),
                    currentItem != null ? currentItem.getType() : "null",
                    cursorItem != null ? cursorItem.getType() : "null"
                ));
            }
        } catch (Exception e) {
            DebugLogger.warning("Inventory", "Error logging item movement: " + e.getMessage());
        }
    }

    private String getContainerType(Inventory inventory) {
        if (inventory == null) return "UNKNOWN";
        
        InventoryHolder holder = inventory.getHolder();
        if (holder == null) return "UNKNOWN";
        
        if (holder instanceof Player player) {
            return containerTypeCache.computeIfAbsent(player, p -> "PLAYER_" + p.getName());
        }
        
        // Get the simple class name without package
        String className = holder.getClass().getSimpleName();
        return className.isEmpty() ? holder.getClass().getName() : className;
    }
    
    private void logContainerAction(Player player, String action, ItemStack item, String containerType) {
        if (item == null || item.getType().isAir()) return;
        
        Location loc = player.getLocation();
        String locationStr = String.format("%s,%d,%d,%d", 
            loc.getWorld().getName(), 
            loc.getBlockX(), 
            loc.getBlockY(), 
            loc.getBlockZ()
        );
        
        plugin.getLogManager().logItemTransaction(
            player.getName(),
            action + "_" + containerType,
            item.getType().name(),
            item.getAmount(),
            locationStr
        );
        
        // Debug logging
        if (plugin.isDebugEnabled()) {
            DebugLogger.debug("Inventory", String.format(
                "%s %s %dx %s in %s at %s",
                player.getName(),
                action,
                item.getAmount(),
                item.getType().name(),
                containerType,
                locationStr
            ));
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isInventorySaveEnabled("save_on_item_move")) return;
        
        // Run on next tick to ensure all inventory updates are complete
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            savePlayerInventory(player);
            logItemMovement(event, player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isInventorySaveEnabled("save_on_item_move")) return;

        // Run on next tick to ensure all inventory updates are complete
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            savePlayerInventory(player);
            
            // Log drag events to containers
            logDragToContainer(event, player);
        });
    }
    
    private void logDragToContainer(InventoryDragEvent event, Player player) {
        try {
            Inventory playerInventory = player.getInventory();
            Inventory topInventory = event.getView().getTopInventory();
            
            // Skip if top inventory is player inventory or null
            if (topInventory == null || topInventory.equals(playerInventory)) return;
            
            // Check if any slots in the drag are in the top inventory
            boolean draggedToContainer = false;
            for (int slot : event.getRawSlots()) {
                if (slot < topInventory.getSize()) {
                    draggedToContainer = true;
                    break;
                }
            }
            
            if (draggedToContainer) {
                ItemStack item = event.getOldCursor();
                if (item != null && !item.getType().isAir()) {
                    Location loc = player.getLocation();
                    String locationStr = String.format("%s,%d,%d,%d", 
                        loc.getWorld().getName(), 
                        loc.getBlockX(), 
                        loc.getBlockY(), 
                        loc.getBlockZ()
                    );
                    
                    String containerType = getContainerType(topInventory);
                    
                    plugin.getLogManager().logItemTransaction(
                        player.getName(),
                        "DRAGGED_TO_" + containerType,
                        item.getType().name(),
                        item.getAmount(),
                        locationStr
                    );
                }
            }
        } catch (Exception e) {
            DebugLogger.warning("Inventory", "Error logging drag event: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            savePlayerInventory(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemBreak(PlayerItemBreakEvent event) {
        savePlayerInventory(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> savePlayerInventory(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof Player sourcePlayer) {
            plugin.getServer().getScheduler().runTask(plugin, () -> savePlayerInventory(sourcePlayer));
        }
        if (event.getDestination().getHolder() instanceof Player destPlayer) {
            plugin.getServer().getScheduler().runTask(plugin, () -> savePlayerInventory(destPlayer));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        if (event.getInventory().getHolder() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> savePlayerInventory(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> savePlayerInventory(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isInventorySaveEnabled("save_on_pickup")) {
            plugin.getServer().getScheduler().runTask(plugin, () -> savePlayerInventory(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (isInventorySaveEnabled("save_on_drop")) {
            savePlayerInventory(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isInventorySaveEnabled("save_on_block_place")) {
            savePlayerInventory(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isInventorySaveEnabled("save_on_block_break")) {
            plugin.getServer().getScheduler().runTask(plugin, () -> savePlayerInventory(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        if (isInventorySaveEnabled("save_on_held_change")) {
            savePlayerInventory(event.getPlayer());
        }
    }
} 