package amg.plugins.aMGCore.models;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents serializable data for an ItemStack.
 * This class provides a way to store and recreate ItemStacks without using deprecated methods.
 */
public final class ItemData {
    private static final Logger LOGGER = Logger.getLogger(ItemData.class.getName());
    private static final String CUSTOM_MODEL_DATA_KEY = "custom_model_data";
    private static final String MINECRAFT_NAMESPACE = "minecraft";
    
    private static boolean DEBUG_ENABLED = false;

    private final Material type;
    private final int amount;
    private final short durability;
    private final String displayName;
    private final List<String> lore;
    private final Map<String, Integer> enchantments;
    private final Set<String> flags;
    private final boolean unbreakable;
    private final int customModelData;
    private final Map<String, Object> persistentData;

    /**
     * Sets whether debug logging is enabled for this class.
     * 
     * @param enabled true to enable debug logging, false to disable it
     */
    public static void setDebugEnabled(boolean enabled) {
        DEBUG_ENABLED = enabled;
        debug("ItemData debug logging " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Logs a debug message if debug logging is enabled.
     * 
     * @param message the message to log
     */
    private static void debug(String message) {
        if (DEBUG_ENABLED) {
            LOGGER.info("[DEBUG] ItemData: " + message);
        }
    }
    
    /**
     * Logs a debug message with an exception if debug logging is enabled.
     * 
     * @param message the message to log
     * @param throwable the exception to log
     */
    private static void debug(String message, Throwable throwable) {
        if (DEBUG_ENABLED) {
            LOGGER.log(Level.INFO, "[DEBUG] ItemData: " + message, throwable);
        }
    }

    /**
     * Creates ItemData for the specified material type.
     *
     * @param type the material type
     */
    public ItemData(Material type) {
        this(type, 1);
    }

    /**
     * Creates ItemData for the specified material type and amount.
     *
     * @param type   the material type
     * @param amount the item amount
     */
    public ItemData(Material type, int amount) {
        this(type, amount, (short) 0, null, null, null, null, false, 0, null);
    }

    /**
     * Creates ItemData with the specified parameters.
     *
     * @param type          the material type
     * @param amount        the item amount
     * @param durability    the item durability
     * @param displayName   the item display name
     * @param lore          the item lore
     * @param enchantments  the item enchantments
     * @param flags         the item flags
     * @param unbreakable   whether the item is unbreakable
     * @param customModelData the custom model data
     * @param persistentData additional persistent data
     */
    public ItemData(@NotNull Material type, int amount, short durability, @Nullable String displayName, 
                    @Nullable List<String> lore, @Nullable Map<String, Integer> enchantments, 
                    @Nullable Set<String> flags, boolean unbreakable, int customModelData,
                    @Nullable Map<String, Object> persistentData) {
        if (type == null) {
            throw new IllegalArgumentException("Material type cannot be null");
        }
        this.type = type;
        this.amount = Math.max(1, Math.min(type.getMaxStackSize(), amount));
        this.durability = durability;
        this.displayName = displayName;
        this.lore = lore != null ? new ArrayList<>(lore) : null;
        this.enchantments = enchantments != null ? new HashMap<>(enchantments) : null;
        this.flags = flags != null ? new HashSet<>(flags) : null;
        this.unbreakable = unbreakable;
        this.customModelData = customModelData;
        this.persistentData = persistentData != null ? new HashMap<>(persistentData) : null;
        
        debug("Created ItemData for " + type + 
              (displayName != null ? " with name '" + displayName + "'" : "") +
              " and " + (enchantments != null ? enchantments.size() : 0) + " enchantment(s)");
    }

    /**
     * Creates a new builder for creating ItemData.
     *
     * @param type the material type
     * @return a new ItemData builder
     */
    @Contract("_ -> new")
    public static @NotNull Builder builder(@NotNull Material type) {
        debug("Creating new ItemData builder for " + type);
        return new Builder(type);
    }

    /**
     * Creates ItemData from an ItemStack.
     *
     * @param item the item stack
     * @return the item data
     * @throws NullPointerException if the item is null
     */
    public static @NotNull ItemData fromItemStack(@NotNull ItemStack item) {
        if (item == null) {
            throw new NullPointerException("ItemStack cannot be null");
        }
        
        debug("Converting ItemStack to ItemData: " + item.getType() + " x" + item.getAmount());
        
        Material type = item.getType();
        int amount = item.getAmount();

        int customModelData = 0;
        short durability = 0;
        String displayName = null;
        List<String> lore = null;
        Map<String, Integer> enchantments = null;
        Set<String> flags = null;
        boolean unbreakable = false;
        Map<String, Object> persistentData = null;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Custom model data - use PersistentDataContainer
            NamespacedKey modelDataKey = new NamespacedKey(MINECRAFT_NAMESPACE, CUSTOM_MODEL_DATA_KEY);
            PersistentDataContainer container = meta.getPersistentDataContainer();
            
            if (container.has(modelDataKey, PersistentDataType.INTEGER)) {
                customModelData = container.get(modelDataKey, PersistentDataType.INTEGER);
                durability = (short) (customModelData & 0xFFFF);
                debug("Found custom model data: " + customModelData);
            }

            // Display name
            Component displayNameComponent = meta.displayName();
            if (displayNameComponent != null) {
                displayName = displayNameComponent.toString();
                debug("Found display name: " + displayName);
            }

            // Lore
            List<Component> loreComponents = meta.lore();
            if (loreComponents != null && !loreComponents.isEmpty()) {
                lore = new ArrayList<>();
                for (Component loreLine : loreComponents) {
                    lore.add(loreLine.toString());
                }
                debug("Found lore with " + lore.size() + " lines");
            }

            // Enchantments
            if (!meta.getEnchants().isEmpty()) {
                enchantments = new HashMap<>();
                for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                    NamespacedKey key = entry.getKey().getKey();
                    enchantments.put(key.toString(), entry.getValue());
                    debug("Found enchantment: " + key + " (level " + entry.getValue() + ")");
                }
            }

            // Item flags
            if (!meta.getItemFlags().isEmpty()) {
                flags = new HashSet<>();
                for (ItemFlag flag : meta.getItemFlags()) {
                    flags.add(flag.name());
                    debug("Found item flag: " + flag.name());
                }
            }

            // Unbreakable
            unbreakable = meta.isUnbreakable();
            if (unbreakable) {
                debug("Item is unbreakable");
            }
            
            // Additional persistent data (excluding custom model data)
            persistentData = extractPersistentData(meta);
        }

        ItemData result = new ItemData(type, amount, durability, displayName, lore, enchantments, flags, unbreakable, customModelData, persistentData);
        debug("Successfully created ItemData from ItemStack");
        return result;
    }

    private static Map<String, Object> extractPersistentData(ItemMeta meta) {
        Map<String, Object> result = new HashMap<>();
        
        
        // Currently, there's no way to iterate through all keys in a PDC in Bukkit API
        // This would require NMS access or plugin-specific implementations
        // Here we could add code to extract common plugin data based on known keys
        
        if (!result.isEmpty()) {
            debug("Extracted " + result.size() + " persistent data entries");
        }
        
        return result.isEmpty() ? null : result;
    }

    /**
     * Converts this ItemData to an ItemStack.
     *
     * @return a new ItemStack
     */
    @NotNull
    public ItemStack toItemStack() {
        debug("Converting ItemData to ItemStack: " + type + " x" + amount);
        ItemStack item = new ItemStack(type, amount);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            // Custom model data
            if (customModelData > 0) {
                NamespacedKey modelDataKey = new NamespacedKey(MINECRAFT_NAMESPACE, CUSTOM_MODEL_DATA_KEY);
                meta.getPersistentDataContainer().set(modelDataKey, PersistentDataType.INTEGER, customModelData);
                debug("Applied custom model data: " + customModelData);
            }

            // Display name
            if (displayName != null) {
                meta.displayName(Component.text(displayName));
                debug("Applied display name: " + displayName);
            }

            // Lore
            if (lore != null && !lore.isEmpty()) {
                List<Component> loreComponents = new ArrayList<>();
                for (String loreLine : lore) {
                    loreComponents.add(Component.text(loreLine));
                }
                meta.lore(loreComponents);
                debug("Applied lore with " + lore.size() + " lines");
            }

            // Enchantments
            if (enchantments != null && !enchantments.isEmpty()) {
                debug("Applying " + enchantments.size() + " enchantments");
                for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                    try {
                        String key = entry.getKey();
                        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
                        if (namespacedKey != null) {
                            // Use getByKey as the current best practice
                            Enchantment enchantment = Enchantment.getByKey(namespacedKey);
                            if (enchantment != null) {
                                meta.addEnchant(enchantment, entry.getValue(), true);
                                debug("Applied enchantment: " + key + " (level " + entry.getValue() + ")");
                            } else {
                                LOGGER.log(Level.WARNING, "Unknown enchantment key: {0}", key);
                                debug("Failed to apply enchantment: Unknown key " + key);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to apply enchantment", e);
                        debug("Failed to apply enchantment", e);
                    }
                }
            }

            // Item flags
            if (flags != null && !flags.isEmpty()) {
                debug("Applying " + flags.size() + " item flags");
                for (String flagName : flags) {
                    try {
                        ItemFlag flag = ItemFlag.valueOf(flagName);
                        meta.addItemFlags(flag);
                        debug("Applied item flag: " + flagName);
                    } catch (IllegalArgumentException e) {
                        LOGGER.log(Level.WARNING, "Invalid item flag: {0}", flagName);
                        debug("Failed to apply item flag: Invalid flag " + flagName);
                    }
                }
            }

            // Unbreakable
            meta.setUnbreakable(unbreakable);
            if (unbreakable) {
                debug("Set item as unbreakable");
            }
            
            // Apply additional persistent data
            applyPersistentData(meta);

            item.setItemMeta(meta);
        }

        debug("Successfully created ItemStack from ItemData");
        return item;
    }
    
    private void applyPersistentData(ItemMeta meta) {
        if (persistentData == null || persistentData.isEmpty()) {
            return;
        }

        debug("Applying " + persistentData.size() + " persistent data entries");
        // Implementation would depend on how persistentData is structured
        // This is a placeholder for future implementation
    }

    @NotNull
    public Material getType() {
        return type;
    }

    public int getAmount() {
        return amount;
    }

    public short getDurability() {
        return durability;
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public List<String> getLore() {
        return lore != null ? Collections.unmodifiableList(lore) : null;
    }

    @Nullable
    public Map<String, Integer> getEnchantments() {
        return enchantments != null ? Collections.unmodifiableMap(enchantments) : null;
    }

    @Nullable
    public Set<String> getFlags() {
        return flags != null ? Collections.unmodifiableSet(flags) : null;
    }

    public boolean isUnbreakable() {
        return unbreakable;
    }

    public int getCustomModelData() {
        return customModelData;
    }
    
    @Nullable
    public Map<String, Object> getPersistentData() {
        return persistentData != null ? Collections.unmodifiableMap(persistentData) : null;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        ItemData itemData = (ItemData) o;
        return amount == itemData.amount &&
               durability == itemData.durability &&
               unbreakable == itemData.unbreakable &&
               customModelData == itemData.customModelData &&
               type == itemData.type &&
               Objects.equals(displayName, itemData.displayName) &&
               Objects.equals(lore, itemData.lore) &&
               Objects.equals(enchantments, itemData.enchantments) &&
               Objects.equals(flags, itemData.flags) &&
               Objects.equals(persistentData, itemData.persistentData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, amount, durability, displayName, lore, 
                           enchantments, flags, unbreakable, customModelData, persistentData);
    }

    @Override
    public String toString() {
        return "ItemData{" +
               "type=" + type +
               ", amount=" + amount +
               ", durability=" + durability +
               ", displayName='" + displayName + '\'' +
               ", lore=" + lore +
               ", enchantments=" + enchantments +
               ", flags=" + flags +
               ", unbreakable=" + unbreakable +
               ", customModelData=" + customModelData +
               '}';
    }
    
    /**
     * Builder for creating ItemData instances.
     */
    public static final class Builder {
        private final Material type;
        private int amount = 1;
        private short durability = 0;
        private String displayName = null;
        private List<String> lore = null;
        private Map<String, Integer> enchantments = null;
        private Set<String> flags = null;
        private boolean unbreakable = false;
        private int customModelData = 0;
        private Map<String, Object> persistentData = null;

        private Builder(@NotNull Material type) {
            if (type == null) {
                throw new IllegalArgumentException("Material type cannot be null");
            }
            this.type = type;
        }

        public Builder amount(int amount) {
            this.amount = Math.max(1, Math.min(type.getMaxStackSize(), amount));
            debug("Builder: set amount to " + this.amount);
            return this;
        }

        public Builder durability(short durability) {
            this.durability = durability;
            debug("Builder: set durability to " + durability);
            return this;
        }

        public Builder displayName(@Nullable String displayName) {
            this.displayName = displayName;
            debug("Builder: set display name to " + (displayName != null ? "'" + displayName + "'" : "null"));
            return this;
        }

        public Builder lore(@Nullable List<String> lore) {
            this.lore = lore != null ? new ArrayList<>(lore) : null;
            debug("Builder: set lore " + (lore != null ? "with " + lore.size() + " lines" : "to null"));
            return this;
        }

        public Builder addLoreLine(@NotNull String line) {
            if (this.lore == null) {
                this.lore = new ArrayList<>();
            }
            this.lore.add(line);
            debug("Builder: added lore line: " + line);
            return this;
        }

        public Builder enchantment(@NotNull NamespacedKey enchantmentKey, int level) {
            if (this.enchantments == null) {
                this.enchantments = new HashMap<>();
            }
            this.enchantments.put(enchantmentKey.toString(), level);
            debug("Builder: added enchantment " + enchantmentKey + " (level " + level + ")");
            return this;
        }

        public Builder enchantment(@NotNull Enchantment enchantment, int level) {
            return enchantment(enchantment.getKey(), level);
        }

        public Builder flag(@NotNull ItemFlag flag) {
            if (this.flags == null) {
                this.flags = new HashSet<>();
            }
            this.flags.add(flag.name());
            debug("Builder: added item flag " + flag.name());
            return this;
        }

        public Builder unbreakable(boolean unbreakable) {
            this.unbreakable = unbreakable;
            debug("Builder: set unbreakable to " + unbreakable);
            return this;
        }

        public Builder customModelData(int customModelData) {
            this.customModelData = customModelData;
            debug("Builder: set custom model data to " + customModelData);
            return this;
        }
        
        public Builder persistentData(@Nullable Map<String, Object> persistentData) {
            this.persistentData = persistentData != null ? new HashMap<>(persistentData) : null;
            debug("Builder: set persistent data " + (persistentData != null ? "with " + persistentData.size() + " entries" : "to null"));
            return this;
        }

        @NotNull
        public ItemData build() {
            debug("Builder: building ItemData for " + type);
            return new ItemData(
                type, amount, durability, displayName, lore, 
                enchantments, flags, unbreakable, customModelData, persistentData
            );
        }

        @NotNull
        public ItemStack buildItemStack() {
            debug("Builder: building ItemStack for " + type);
            return build().toItemStack();
        }
    }
} 