package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.models.BanData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import com.destroystokyo.paper.profile.PlayerProfile; // Updated import for Paper's PlayerProfile
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class BanManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, BanData> banCache = new HashMap<>();
    private final Map<String, ReentrantLock> banLocks = new HashMap<>();
    private final AMGCore plugin;
    private final File banDirectory;
    private final SimpleDateFormat dateFormat;
    private final LocaleManager localeManager;

    public BanManager(AMGCore plugin) {
        this.plugin = plugin;
        this.banDirectory = new File(plugin.getDataFolder(), "bans");
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        
        Object localeManagerObj = plugin.getManager("locale");
        this.localeManager = localeManagerObj instanceof LocaleManager ? (LocaleManager) localeManagerObj : null;
        
        if (!this.banDirectory.exists() && !this.banDirectory.mkdirs()) {
            plugin.getLogger().severe(localeManager.getComponent("ban.error.create_dir").toString());
        }
        
        // Generate bans.json on startup
        generateBansJson();
        
        // Sync existing bans with Paper's ban system
        syncExistingBans();
    }
    
    /**
     * Syncs existing bans with Paper's ban system
     */
    private void syncExistingBans() {
        if (banDirectory.exists() && banDirectory.isDirectory()) {
            File[] banFiles = banDirectory.listFiles((dir, name) -> name.toLowerCase().endsWith(".json") && !name.equals("bans.json"));
            
            if (banFiles != null) {
                for (File file : banFiles) {
                    try (Reader reader = new FileReader(file)) {
                        BanData banData = GSON.fromJson(reader, BanData.class);
                        if (banData != null && banData.getPlayerName() != null) {
                            // Skip expired bans
                            if (banData.getDuration() > 0 && banData.hasExpired()) {
                                continue;
                            }
                            
                            // Add to cache
                            banCache.put(banData.getPlayerName().toLowerCase(), banData);
                            
                            // Use parameterized BanList<PlayerProfile>
                            BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
                            
                            // Create a PlayerProfile for the player
                            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + banData.getPlayerName()).getBytes()), banData.getPlayerName());
                            
                            // Check if player is already banned
                            boolean alreadyBanned = banList.isBanned(profile);
                            
                            if (!alreadyBanned) {
                                Duration duration = banData.getDuration() > 0 
                                    ? Duration.ofMillis(banData.getDuration())
                                    : null;
                                
                                banList.addBan(
                                    profile,
                                    banData.getReason(),
                                    duration != null ? Date.from(Instant.now().plus(duration)) : null,
                                    banData.getStaffMember()
                                );
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning(localeManager.getComponent("ban.error.read_file", file.getName(), e.getMessage()).toString());
                    }
                }
            }
        }
    }
    
    /**
     * Generates a consolidated bans.json file containing all ban data
     */
    private void generateBansJson() {
        try {
            // Store bans.json in the bans directory instead of plugin root
            File bansFile = new File(banDirectory, "bans.json");
            Map<String, BanData> allBans = new HashMap<>();
            
            // Load all individual ban files
            if (banDirectory.exists() && banDirectory.isDirectory()) {
                File[] banFiles = banDirectory.listFiles((dir, name) -> name.toLowerCase().endsWith(".json") && !name.equals("bans.json"));
                
                if (banFiles != null) {
                    for (File file : banFiles) {
                        try (Reader reader = new FileReader(file)) {
                            BanData banData = GSON.fromJson(reader, BanData.class);
                            if (banData != null && banData.getPlayerName() != null) {
                                // Check if ban is expired
                                if (banData.getDuration() > 0) {
                                    long banEndTime = banData.getBanTime() + banData.getDuration();
                                    if (System.currentTimeMillis() >= banEndTime) {
                                        // Skip expired bans
                                        continue;
                                    }
                                }
                                allBans.put(banData.getPlayerName().toLowerCase(), banData);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to read ban file: " + file.getName() + " - " + e.getMessage());
                        }
                    }
                }
            }
            
            // Write consolidated bans.json
            try (Writer writer = new FileWriter(bansFile)) {
                GSON.toJson(allBans, writer);
                plugin.getLogger().info("Generated bans.json with " + allBans.size() + " active bans");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to generate bans.json: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private ReentrantLock getBanLock(String playerName) {
        return banLocks.computeIfAbsent(playerName.toLowerCase(), k -> new ReentrantLock());
    }

    public void banPlayer(@NotNull String playerName, @NotNull String staffMember, @NotNull String reason, long duration) {
        Objects.requireNonNull(playerName, "Player name cannot be null");
        Objects.requireNonNull(staffMember, "Staff member cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");

        String lowerName = playerName.toLowerCase();
        ReentrantLock lock = getBanLock(lowerName);
        lock.lock();
        
        try {
            // Create our ban data
            BanData banData = new BanData(playerName, staffMember, reason, duration);
            banCache.put(lowerName, banData);
            saveBanData(lowerName, banData);
            
            // Use parameterized BanList<PlayerProfile>
            BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
            
            // Create a PlayerProfile for the player
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes()), playerName);
            
            Date expires = duration > 0 
                ? Date.from(Instant.ofEpochMilli(System.currentTimeMillis() + duration))
                : null;
            
            banList.addBan(
                profile,
                reason,
                expires,
                staffMember
            );
            
            // Kick any online players with this name
            Player player = Bukkit.getPlayerExact(playerName);
            if (player != null) {
                player.kick(getBanComponent(playerName));
            }
            
            // Log the ban
            plugin.getLogger().info(String.format(
                "Player %s banned by %s for: %s (Duration: %s)",
                playerName,
                staffMember,
                reason,
                duration < 0 ? "PERMANENT" : formatDuration(duration)
            ));
            
            // Regenerate bans.json after adding a ban
            generateBansJson();
        } finally {
            lock.unlock();
        }
    }

    public boolean unbanPlayer(@NotNull String playerName) {
        Objects.requireNonNull(playerName, "Player name cannot be null");
        
        String lowerName = playerName.toLowerCase();
        ReentrantLock lock = getBanLock(lowerName);
        lock.lock();
        
        try {
            // Check if player is banned
            if (!isPlayerBanned(playerName)) {
                return false;
            }
            
            // Remove from cache
            banCache.remove(lowerName);
            
            // Delete ban file
            File banFile = getBanFile(lowerName);
            if (banFile.exists() && !banFile.delete()) {
                plugin.getLogger().warning(localeManager.getComponent("ban.error.delete_file", playerName).toString());
            }
            
            // Use parameterized BanList<PlayerProfile>
            BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes()), playerName);
            
            banList.pardon(profile);
            
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isPlayerBanned(@NotNull String playerName) {
        Objects.requireNonNull(playerName, "Player name cannot be null");
        
        String lowerName = playerName.toLowerCase();
        ReentrantLock lock = getBanLock(lowerName);
        lock.lock();
        
        try {
            // Use parameterized BanList<PlayerProfile>
            BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes()), playerName);
            
            if (banList.isBanned(profile)) {
                BanEntry<PlayerProfile> entry = banList.getBanEntry(profile);
                if (entry != null) {
                    // Check if ban is expired
                    Date expires = entry.getExpiration();
                    if (expires != null && expires.before(new Date())) {
                        // Ban is expired, unban the player
                        banList.pardon(profile);
                        return false;
                    }
                    return true;
                }
            }
            
            // Check our cache
            BanData banData = getBanData(lowerName);
            return banData != null && !banData.hasExpired();
        } finally {
            lock.unlock();
        }
    }

    public boolean isPlayerBanned(@NotNull Player player) {
        return isPlayerBanned(player.getName());
    }

    @NotNull
    public Component getBanComponent(@NotNull String playerName) {
        Objects.requireNonNull(playerName, "Player name cannot be null");
        
        String lowerName = playerName.toLowerCase();
        ReentrantLock lock = getBanLock(lowerName);
        lock.lock();
        
        try {
            // Use parameterized BanList<PlayerProfile>
            BanList<PlayerProfile> banList = Bukkit.getBanList(BanList.Type.PROFILE);
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes()), playerName);
            
            if (banList.isBanned(profile)) {
                BanEntry<PlayerProfile> entry = banList.getBanEntry(profile);
                if (entry != null) {
                    // Check if ban is expired
                    Date expires = entry.getExpiration();
                    if (expires != null && expires.before(new Date())) {
                        // Ban is expired, unban the player
                        banList.pardon(profile);
                        return localeManager.getComponent("ban.not_banned");
                    }
                    
                    // Build ban message using locale strings
                    Component message = Component.empty()
                        .append(localeManager.getComponent("ban.message"))
                        .append(Component.newline())
                        .append(localeManager.getComponent("ban.reason", entry.getReason()));
                    
                    if (expires != null) {
                        message = Component.empty()
                            .append(message)
                            .append(Component.newline())
                            .append(localeManager.getComponent("ban.expires", dateFormat.format(expires)));
                    } else {
                        message = Component.empty()
                            .append(message)
                            .append(Component.newline())
                            .append(localeManager.getComponent("ban.permanent_message"));
                    }
                    
                    return message;
                }
            }
            
            // Check our system
            BanData banData = getBanData(lowerName);
            if (banData == null) {
                return localeManager.getComponent("ban.not_banned");
            }

            Component header = localeManager.getComponent("ban.message");
            
            Component reason = Component.newline()
                .append(localeManager.getComponent("ban.reason", banData.getReason()));

            Component staff = Component.newline()
                .append(localeManager.getComponent("ban.staff", banData.getStaffMember()));

            Component date = Component.newline()
                .append(localeManager.getComponent("ban.date", dateFormat.format(new Date(banData.getBanTime()))));

            if (banData.getDuration() > 0) {
                long remainingTime = (banData.getBanTime() + banData.getDuration()) - System.currentTimeMillis();
                if (remainingTime <= 0) {
                    unbanPlayer(playerName);
                    return localeManager.getComponent("ban.not_banned");
                }

                Component duration = Component.newline()
                    .append(localeManager.getComponent("ban.duration", formatDuration(remainingTime)));

                return Component.empty()
                    .append(header)
                    .append(reason)
                    .append(staff)
                    .append(date)
                    .append(duration);
            } else {
                Component permanent = Component.newline()
                    .append(localeManager.getComponent("ban.permanent"));

                return Component.empty()
                    .append(header)
                    .append(reason)
                    .append(staff)
                    .append(date)
                    .append(permanent);
            }
        } finally {
            lock.unlock();
        }
    }

    @NotNull
    public String getBanMessage(@NotNull String playerName) {
        return getBanComponent(playerName).toString();
    }

    @NotNull
    public String getBanMessage(@NotNull Player player) {
        return getBanMessage(player.getName());
    }

    @Nullable
    private BanData getBanData(String lowerName) {
        // Check cache first
        BanData banData = banCache.get(lowerName);
        if (banData != null) {
            return banData;
        }

        // Load from file if not in cache
        File banFile = getBanFile(lowerName);
        if (banFile.exists()) {
            try (Reader reader = new FileReader(banFile)) {
                banData = GSON.fromJson(reader, BanData.class);
                if (banData != null) {
                    banCache.put(lowerName, banData);
                }
                return banData;
            } catch (IOException e) {
                plugin.getLogger().severe(localeManager.getComponent("ban.error.read_data", lowerName).toString());
                e.printStackTrace();
                return null;
            }
        }
        
        return null;
    }

    private void saveBanData(String lowerName, BanData banData) {
        File banFile = getBanFile(lowerName);
        try {
            if (!banFile.getParentFile().exists() && !banFile.getParentFile().mkdirs()) {
                throw new IOException("Failed to create parent directories");
            }
            try (Writer writer = new FileWriter(banFile)) {
                GSON.toJson(banData, writer);
            }
        } catch (IOException e) {
            plugin.getLogger().severe(localeManager.getComponent("ban.error.save_data", lowerName).toString());
            e.printStackTrace();
        }
    }

    private File getBanFile(String lowerName) {
        return new File(banDirectory, lowerName + ".json");
    }

    public void updatePlayerIp(@NotNull Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        String lowerName = player.getName().toLowerCase();
        ReentrantLock lock = getBanLock(lowerName);
        lock.lock();
        
        try {
            BanData banData = getBanData(lowerName);
            if (banData != null && player.getAddress() != null) {
                String ip = player.getAddress().getHostString();
                banData.addIpAddress(ip);
                saveBanData(lowerName, banData);
            }
        } finally {
            lock.unlock();
        }
    }

    @NotNull
    private String formatDuration(long milliseconds) {
        if (milliseconds < 0) {
            return "PERMANENT";
        }

        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        minutes %= 60;
        hours %= 24;

        StringBuilder duration = new StringBuilder();
        if (days > 0) {
            duration.append(days).append("d ");
        }
        if (hours > 0) {
            duration.append(hours).append("h ");
        }
        if (minutes > 0) {
            duration.append(minutes).append("m");
        }

        return duration.toString().trim();
    }
}