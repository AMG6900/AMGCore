package amg.plugins.aMGCore.models;

import org.bukkit.Location;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Represents persistent player data that can be saved and loaded.
 * This class is serializable to support saving to disk.
 */
public final class PlayerData implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(PlayerData.class.getName());
    private static final String DEFAULT_JOB = "unemployed";
    private static final double DEFAULT_MONEY = 0.0;
    private static volatile boolean DEBUG_ENABLED = false;
    private static final Object LOCK_INIT = new Object();

    private final String uuid;
    private final String name;
    private double money;
    private String job;
    private LocationData lastLocation;
    private final Set<String> knownIps;
    
    // Transient fields that won't be serialized
    private transient volatile Runnable onDataChanged;
    private transient volatile ReentrantLock lock;
    private transient volatile boolean requiresImmediateSave;

    /**
     * Gets the lock for thread-safe operations.
     * This method ensures the lock is never null.
     * 
     * @return the lock
     */
    private ReentrantLock getLock() {
        ReentrantLock result = lock;
        if (result == null) {
            synchronized (LOCK_INIT) {
                result = lock;
                if (result == null) {
                    result = lock = new ReentrantLock(true); // Make it fair
                }
            }
        }
        return result;
    }

    /**
     * Special method called by the Java serialization system after deserialization.
     * This ensures that transient fields are properly initialized.
     * 
     * @return this object with initialized transient fields
     */
    private Object readResolve() {
        synchronized (LOCK_INIT) {
            if (lock == null) {
                lock = new ReentrantLock(true);
            }
        }
        requiresImmediateSave = false;
        return this;
    }
    
    /**
     * Custom deserialization method to ensure transient fields are initialized.
     * This is called automatically by Java's serialization mechanism.
     */
    private void readObject(ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        synchronized (LOCK_INIT) {
            lock = new ReentrantLock(true);
        }
        requiresImmediateSave = false;
    }

    /**
     * Sets whether debug logging is enabled for this class.
     * 
     * @param enabled true to enable debug logging, false to disable it
     */
    public static void setDebugEnabled(boolean enabled) {
        DEBUG_ENABLED = enabled;
        debug("PlayerData debug logging " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Logs a debug message if debug logging is enabled.
     * 
     * @param message the message to log
     */
    private static void debug(String message) {
        if (DEBUG_ENABLED) {
            LOGGER.info("[DEBUG] PlayerData: " + message);
        }
    }

    /**
     * Creates a new PlayerData instance for the given UUID and name.
     *
     * @param uuid the player's UUID
     * @param name the player's name
     * @throws NullPointerException if uuid or name is null
     */
    public PlayerData(@NotNull String uuid, @NotNull String name) {
        this.uuid = Objects.requireNonNull(uuid, "UUID cannot be null");
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.money = DEFAULT_MONEY;
        this.job = DEFAULT_JOB;
        this.knownIps = Collections.synchronizedSet(new HashSet<>());
        this.requiresImmediateSave = false;
        
        // Initialize the lock immediately in the constructor
        this.lock = new ReentrantLock();
        debug("Created new PlayerData for " + name + " (" + uuid + ")");
    }

    /**
     * Sets a callback to be called when data changes.
     * 
     * @param callback the callback to call when data changes
     */
    public void setOnDataChanged(@Nullable Runnable callback) {
        this.onDataChanged = callback;
    }

    /**
     * Notifies that data has changed.
     */
    protected void notifyDataChanged() {
        if (onDataChanged != null) {
            onDataChanged.run();
        }
    }

    /**
     * Checks if this data requires immediate saving.
     * This is used for critical data that should not be delayed.
     * 
     * @return true if immediate save is required, false otherwise
     */
    public boolean isRequiresImmediateSave() {
        return requiresImmediateSave;
    }
    
    /**
     * Sets whether this data requires immediate saving.
     * 
     * @param requiresImmediateSave true if immediate save is required, false otherwise
     */
    public void setRequiresImmediateSave(boolean requiresImmediateSave) {
        this.requiresImmediateSave = requiresImmediateSave;
    }

    /**
     * Gets the player's money.
     * 
     * @return the amount of money
     */
    public double getMoney() {
        getLock().lock();
        try {
            return money;
        } finally {
            getLock().unlock();
        }
    }

    /**
     * Sets the player's money.
     * 
     * @param money the new amount of money
     * @throws IllegalArgumentException if money is negative
     */
    public void setMoney(double money) {
        getLock().lock();
        try {
            if (money < 0) {
                throw new IllegalArgumentException("Money cannot be negative");
            }
            if (this.money != money) {
                this.money = money;
                // Money changes should be saved immediately to prevent duplication
                this.requiresImmediateSave = true;
                notifyDataChanged();
            }
        } finally {
            getLock().unlock();
        }
    }

    /**
     * Adds money to the player.
     * 
     * @param amount the amount to add
     * @throws IllegalArgumentException if amount is negative
     */
    public void addMoney(double amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Cannot add negative amount");
        }
        setMoney(this.money + amount);
    }

    /**
     * Removes money from the player.
     * 
     * @param amount the amount to remove
     * @return true if the money was removed, false if the player doesn't have enough
     * @throws IllegalArgumentException if amount is negative
     */
    public boolean removeMoney(double amount) {
        getLock().lock();
        try {
            if (amount < 0) {
                throw new IllegalArgumentException("Cannot remove negative amount");
            }
            
            if (this.money < amount) {
                return false;
            }
            
            this.money -= amount;
            // Money changes should be saved immediately to prevent duplication
            this.requiresImmediateSave = true;
            notifyDataChanged();
            return true;
        } finally {
            getLock().unlock();
        }
    }

    /**
     * Checks if the player has at least the given amount of money.
     * 
     * @param amount the amount to check
     * @return true if the player has at least the given amount, false otherwise
     */
    public boolean hasMoney(double amount) {
        getLock().lock();
        try {
            return this.money >= amount;
        } finally {
            getLock().unlock();
        }
    }

    /**
     * Updates the player's IP address.
     * 
     * @param ip the new IP address, or null to clear
     */
    public void updateIp(@Nullable String ip) {
        getLock().lock();
        try {
            if (ip != null && !ip.isEmpty()) {
                knownIps.add(ip);
            }
            
            notifyDataChanged();
        } finally {
            getLock().unlock();
        }
    }

    /**
     * Updates the player's location.
     * 
     * @param loc the new location
     * @throws NullPointerException if loc is null
     */
    public void updateLocation(@NotNull Location loc) {
        Objects.requireNonNull(loc, "Location cannot be null");
        
        getLock().lock();
        try {
            this.lastLocation = new LocationData(loc);
            // Location updates are common and don't need immediate saving
            this.requiresImmediateSave = false;
            notifyDataChanged();
        } finally {
            getLock().unlock();
        }
    }

    /**
     * Gets the player's last known location.
     * 
     * @return the last known location, or null if not set
     */
    @Nullable
    public Location getLastLocation() {
        return lastLocation != null ? lastLocation.toLocation() : null;
    }

    /**
     * Gets all known IP addresses for this player.
     * 
     * @return a set of known IP addresses
     */
    @NotNull
    public Set<String> getKnownIps() {
        return Collections.unmodifiableSet(knownIps);
    }

    /**
     * Gets the player's UUID.
     * 
     * @return the player's UUID
     */
    @NotNull
    public String getUuid() {
        return uuid;
    }

    /**
     * Gets the player's name.
     * 
     * @return the player's name
     */
    @NotNull
    public String getName() {
        return name;
    }

    /**
     * Gets the player's job.
     * 
     * @return the player's job
     */
    @NotNull
    public String getJob() {
        return job;
    }

    /**
     * Sets the player's job.
     * 
     * @param job the new job
     * @throws NullPointerException if job is null
     */
    public void setJob(@NotNull String job) {
        getLock().lock();
        try {
            this.job = Objects.requireNonNull(job, "Job cannot be null");
            // Job changes should be saved soon but not immediately
            this.requiresImmediateSave = false;
            notifyDataChanged();
        } finally {
            getLock().unlock();
        }
    }

    /**
     * Loads data from a player's inventory.
     * This method updates the PlayerData with relevant information from the inventory.
     *
     * @param inventory the player's inventory
     */
    public void loadFromInventory(@NotNull PlayerInventory inventory) {
        getLock().lock();
        try {
            // Currently, this method only triggers a data change notification
            // In the future, it could save specific inventory data if needed
            
            if (DEBUG_ENABLED) {
                debug("Loaded inventory data for player " + name);
            }
            
            notifyDataChanged();
        } finally {
            getLock().unlock();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlayerData that = (PlayerData) o;
        return uuid.equals(that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }

    @Override
    public String toString() {
        return "PlayerData{" +
                "uuid='" + uuid + '\'' +
                ", name='" + name + '\'' +
                ", money=" + money +
                ", job='" + job + '\'' +
                '}';
    }
} 