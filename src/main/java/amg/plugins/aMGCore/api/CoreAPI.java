package amg.plugins.aMGCore.api;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.models.PlayerData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

/**
 * Core API for interacting with player data and plugin modules.
 * This API provides methods for managing player jobs, money, and plugin modules.
 */
public final class CoreAPI {
    private CoreAPI() {
        // Prevent instantiation
        throw new UnsupportedOperationException("Utility class");
    }

    private static AMGCore getPlugin() {
        return AMGCore.getInstance();
    }

    private static PlayerData getPlayerDataOrThrow(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        PlayerData data = getPlugin().getPlayerDataManager().getPlayerData(player);
        if (data == null) {
            throw new IllegalStateException("No data found for player: " + player.getName());
        }
        return data;
    }

    /**
     * Gets the player's current job.
     *
     * @param player The player
     * @return The job name, or "unemployed" if no job is set
     * @throws NullPointerException if player is null
     */
    public static String getJob(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        PlayerData data = getPlugin().getPlayerDataManager().getPlayerData(player);
        return data != null ? data.getJob() : "unemployed";
    }

    /**
     * Sets the player's job.
     *
     * @param player The player
     * @param job The job name
     * @throws NullPointerException if player or job is null
     */
    public static void setJob(Player player, String job) {
        Objects.requireNonNull(job, "Job cannot be null");
        PlayerData data = getPlayerDataOrThrow(player);
        data.setJob(job);
        getPlugin().getPlayerDataManager().savePlayer(player);
    }

    /**
     * Gets the player's money balance.
     *
     * @param player The player
     * @return The current balance
     * @throws NullPointerException if player is null
     */
    public static double getMoney(Player player) {
        return getPlayerDataOrThrow(player).getMoney();
    }

    /**
     * Sets the player's money balance.
     *
     * @param player The player
     * @param amount The new balance
     * @throws NullPointerException if player is null
     * @throws IllegalArgumentException if amount is negative
     */
    public static void setMoney(Player player, double amount) {
        getPlayerDataOrThrow(player).setMoney(amount);
    }

    /**
     * Adds money to the player's balance.
     *
     * @param player The player
     * @param amount The amount to add
     * @throws NullPointerException if player is null
     * @throws IllegalArgumentException if amount is negative
     */
    public static void addMoney(Player player, double amount) {
        getPlayerDataOrThrow(player).addMoney(amount);
    }

    /**
     * Removes money from the player's balance.
     *
     * @param player The player
     * @param amount The amount to remove
     * @return true if the money was removed, false if insufficient funds
     * @throws NullPointerException if player is null
     * @throws IllegalArgumentException if amount is negative
     */
    public static boolean removeMoney(Player player, double amount) {
        return getPlayerDataOrThrow(player).removeMoney(amount);
    }

    /**
     * Checks if the player has enough money.
     *
     * @param player The player
     * @param amount The amount to check
     * @return true if the player has enough money
     * @throws NullPointerException if player is null
     * @throws IllegalArgumentException if amount is negative
     */
    public static boolean hasMoney(Player player, double amount) {
        return getPlayerDataOrThrow(player).hasMoney(amount);
    }

    /**
     * Gets the player's data object.
     *
     * @param player The player
     * @return PlayerData object, or null if not found
     * @throws NullPointerException if player is null
     */
    public static PlayerData getData(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        return getPlugin().getPlayerDataManager().getPlayerData(player);
    }

    /**
     * Reloads a player's data from disk.
     * This will discard any unsaved changes.
     *
     * @param player The player
     * @throws NullPointerException if player is null
     */
    public static void reloadData(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        getPlugin().getPlayerDataManager().reloadPlayerData(player);
    }

    /**
     * Checks if data exists for a player.
     *
     * @param player The player
     * @return true if data exists, false otherwise
     * @throws NullPointerException if player is null
     */
    public static boolean hasData(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        return getPlugin().getPlayerDataManager().exists(player);
    }

    /**
     * Forces an immediate save of the player's data.
     * This bypasses the save cooldown.
     *
     * @param player The player
     * @throws NullPointerException if player is null
     */
    public static void forceSave(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        getPlugin().getPlayerDataManager().savePlayer(player);
    }
    
    // Module Management API
    
    /**
     * Gets a module by name.
     * 
     * @param moduleName The name of the module to get
     * @return The module, or null if no module with the given name exists
     */
    @Nullable
    public static Module getModule(@NotNull String moduleName) {
        return getPlugin().getModuleRegistry().getModule(moduleName);
    }
    
    /**
     * Gets all registered modules.
     * 
     * @return A collection of all registered modules
     */
    @NotNull
    public static Collection<Module> getAllModules() {
        return getPlugin().getModuleRegistry().getAllModules();
    }
    
    /**
     * Gets all enabled modules.
     * 
     * @return A collection of all enabled modules
     */
    @NotNull
    public static Collection<Module> getEnabledModules() {
        return getPlugin().getModuleRegistry().getEnabledModules();
    }
    
    /**
     * Enables a module and all its dependencies.
     * 
     * @param moduleName The name of the module to enable
     * @return true if the module was enabled, false if it couldn't be enabled
     */
    public static boolean enableModule(@NotNull String moduleName) {
        return getPlugin().getModuleRegistry().enableModule(moduleName);
    }
    
    /**
     * Disables a module.
     * 
     * @param moduleName The name of the module to disable
     * @return true if the module was disabled, false if it couldn't be disabled
     */
    public static boolean disableModule(@NotNull String moduleName) {
        return getPlugin().getModuleRegistry().disableModule(moduleName);
    }
    
    /**
     * Checks if a module is enabled.
     * 
     * @param moduleName The name of the module to check
     * @return true if the module is enabled, false if it's disabled or doesn't exist
     */
    public static boolean isModuleEnabled(@NotNull String moduleName) {
        Module module = getModule(moduleName);
        return module != null && module.isEnabled();
    }
    
    /**
     * Marks a module for auto-enabling when the plugin starts.
     * 
     * @param moduleName The name of the module to auto-enable
     */
    public static void addAutoEnableModule(@NotNull String moduleName) {
        getPlugin().getModuleRegistry().addAutoEnableModule(moduleName);
    }
    
    /**
     * Removes a module from the auto-enable list.
     * 
     * @param moduleName The name of the module to remove from auto-enable
     */
    public static void removeAutoEnableModule(@NotNull String moduleName) {
        getPlugin().getModuleRegistry().removeAutoEnableModule(moduleName);
    }
} 