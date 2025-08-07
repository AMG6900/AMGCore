package amg.plugins.aMGCore.events;

import amg.plugins.aMGCore.AMGCore;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

public class LoggingEvents implements Listener {
    private final AMGCore plugin;
    private final PlainTextComponentSerializer textSerializer = PlainTextComponentSerializer.plainText();

    public LoggingEvents(@NotNull AMGCore plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location loc = block.getLocation();
        
        String locationStr = formatLocation(loc);

        plugin.getLogManager().logModAction(
            player.getName(),
            "BLOCK_PLACE",
            block.getType().name(),
            locationStr
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location loc = block.getLocation();
        
        String locationStr = formatLocation(loc);

        plugin.getLogManager().logModAction(
            player.getName(),
            "BLOCK_BREAK",
            block.getType().name(),
            locationStr
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Handle different click types
        switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME -> handleItemPlacement(event, player);
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> handleItemPickup(event, player);
            case MOVE_TO_OTHER_INVENTORY -> handleItemMove(event, player);
            case HOTBAR_SWAP -> handleHotbarSwap(event, player);
            case SWAP_WITH_CURSOR -> handleItemSwap(event, player);
            default -> {}
        }
    }

    private void handleItemPlacement(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (event.getCursor() == null || event.getCursor().getType().isAir()) {
            return;
        }

        logContainerInteraction(
            player,
            "PLACE",
            event.getCursor(),
            event.getClickedInventory(),
            event.getSlot()
        );
    }

    private void handleItemPickup(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }

        logContainerInteraction(
            player,
            "TAKE",
            event.getCurrentItem(),
            event.getClickedInventory(),
            event.getSlot()
        );
    }

    private void handleItemMove(@NotNull InventoryClickEvent event, @NotNull Player player) {
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) {
            return;
        }

        Inventory sourceInv = event.getClickedInventory();
        String action = sourceInv == event.getView().getTopInventory() ? "MOVE_TO_PLAYER" : "MOVE_TO_CONTAINER";
        logContainerInteraction(player, action, item, sourceInv, event.getSlot());
    }

    private void handleHotbarSwap(@NotNull InventoryClickEvent event, @NotNull Player player) {
        ItemStack currentItem = event.getCurrentItem();
        
        // Check if hotbar button is valid before trying to get the item
        int hotbarButton = event.getHotbarButton();
        ItemStack hotbarItem = null;
        if (hotbarButton >= 0 && hotbarButton < 9) {
            hotbarItem = event.getView().getBottomInventory().getItem(hotbarButton);
        }

        if (currentItem != null && !currentItem.getType().isAir()) {
            logContainerInteraction(
                player,
                "SWAP_OUT",
                currentItem,
                event.getClickedInventory(),
                event.getSlot()
            );
        }

        if (hotbarItem != null && !hotbarItem.getType().isAir()) {
            logContainerInteraction(
                player,
                "SWAP_IN",
                hotbarItem,
                event.getClickedInventory(),
                event.getSlot()
            );
        }
    }

    private void handleItemSwap(@NotNull InventoryClickEvent event, @NotNull Player player) {
        ItemStack currentItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();

        if (currentItem != null && !currentItem.getType().isAir()) {
            logContainerInteraction(
                player,
                "SWAP_OUT",
                currentItem,
                event.getClickedInventory(),
                event.getSlot()
            );
        }

        if (cursorItem != null && !cursorItem.getType().isAir()) {
            logContainerInteraction(
                player,
                "SWAP_IN",
                cursorItem,
                event.getClickedInventory(),
                event.getSlot()
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Process each slot that was affected by the drag
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int slot = entry.getKey();
            ItemStack item = entry.getValue();
            
            // Determine which inventory this slot belongs to
            Inventory affectedInv = slot < event.getView().getTopInventory().getSize() 
                ? event.getView().getTopInventory() 
                : event.getView().getBottomInventory();

            if (affectedInv == event.getView().getTopInventory()) {
                logContainerInteraction(player, "DRAG_PLACE", item, affectedInv, slot);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(@NotNull InventoryMoveItemEvent event) {
        if (event.getItem() == null || event.getItem().getType().isAir()) {
            return;
        }

        String sourceInfo = getContainerInfo(event.getSource());
        String destInfo = getContainerInfo(event.getDestination());

        plugin.getLogManager().logItemTransaction(
            "SYSTEM",
            "CONTAINER_TRANSFER",
            event.getItem().getType().name(),
            event.getItem().getAmount(),
            String.format("From %s to %s", sourceInfo, destInfo)
        );
    }

    private void logContainerInteraction(
            @NotNull Player player,
            @NotNull String action,
            @NotNull ItemStack item,
            @Nullable Inventory inventory,
            int slot) {
        if (inventory == null || !(inventory.getHolder() instanceof BlockInventoryHolder)) {
            return;
        }

        String containerInfo = getContainerInfo(inventory);
        String slotInfo = String.format("slot %d", slot);

        plugin.getLogManager().logItemTransaction(
            player.getName(),
            "CONTAINER_" + action,
            item.getType().name(),
            item.getAmount(),
            containerInfo + " at " + slotInfo
        );
    }

    @NotNull
    private String getContainerInfo(@NotNull Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockInventoryHolder blockHolder) {
            Location loc = blockHolder.getBlock().getLocation();
            return formatLocation(loc) + " (" + blockHolder.getBlock().getType().name() + ")";
        }
        return "Unknown";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = textSerializer.serialize(event.message());
        
        // Log the chat message
        plugin.getLogManager().logChat(
            player.getName(),
            message,
            event.viewers().size()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(@NotNull PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().trim();
        
        // Don't log sensitive commands
        String lowerCommand = command.toLowerCase();
        if (isSensitiveCommand(lowerCommand)) {
            plugin.getLogManager().logCommand(
                player.getName(),
                "/[SENSITIVE COMMAND]",
                !event.isCancelled()
            );
            return;
        }
        
        // Log the command
        plugin.getLogManager().logCommand(
            player.getName(),
            command,
            !event.isCancelled()
        );
    }

    private boolean isSensitiveCommand(String command) {
        return command.startsWith("/login") ||
               command.startsWith("/register") ||
               command.startsWith("/l ") ||
               command.startsWith("/reg ") ||
               command.startsWith("/changepassword") ||
               command.startsWith("/changepass") ||
               command.startsWith("/authme");
    }

    @NotNull
    private String formatLocation(@NotNull Location loc) {
        return String.format("%s,%d,%d,%d",
            Objects.requireNonNull(loc.getWorld()).getName(),
            loc.getBlockX(),
            loc.getBlockY(),
            loc.getBlockZ()
        );
    }
} 