package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChatManager implements Listener {
    private final AMGCore plugin;
    private final DatabaseManager databaseManager;
    private final LocaleManager localeManager;
    private final Map<UUID, String> playerChannels;
    private final Map<UUID, MuteData> mutedPlayers;
    private final Map<UUID, UUID> lastMessageFrom;
    private final Map<UUID, String> lastMessage;
    private final Map<String, ChatChannel> channels;
    private static final String DEFAULT_CHANNEL = "global";
    private static final long MUTE_CHECK_INTERVAL = 60L; // Check mutes every minute
    private LuckPerms luckPerms;
    private boolean placeholderAPIEnabled;

    public ChatManager(AMGCore plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.localeManager = plugin.getLocaleManager();
        this.playerChannels = new ConcurrentHashMap<>();
        this.mutedPlayers = new ConcurrentHashMap<>();
        this.lastMessageFrom = new ConcurrentHashMap<>();
        this.lastMessage = new ConcurrentHashMap<>();
        this.channels = new ConcurrentHashMap<>();
        
        // Initialize LuckPerms
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            this.luckPerms = provider.getProvider();
            plugin.getLogger().info("LuckPerms integration enabled");
        } else {
            plugin.getLogger().warning("LuckPerms not found, prefixes will not be available");
        }

        // Check for PlaceholderAPI
        this.placeholderAPIEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (placeholderAPIEnabled) {
            plugin.getLogger().info("PlaceholderAPI integration enabled");
        } else {
            plugin.getLogger().warning("PlaceholderAPI not found, placeholders will not be available");
        }
        
        // Initialize database tables
        initializeDatabase();
        
        // Load channels
        loadChannels();
        
        // Load muted players
        loadMutedPlayers();
        
        // Register events
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        // Start mute cleanup task
        startMuteCleanupTask();
    }

    private void initializeDatabase() {
        try (Connection conn = databaseManager.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                // Chat channels table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_channels (
                        name VARCHAR(32) PRIMARY KEY,
                        format VARCHAR(255) NOT NULL,
                        permission VARCHAR(64),
                        global BOOLEAN DEFAULT FALSE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                
                // Muted players table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS muted_players (
                        uuid VARCHAR(36) PRIMARY KEY,
                        muted_by VARCHAR(36) NOT NULL,
                        reason TEXT,
                        expires_at TIMESTAMP,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                
                                 // Insert default channel if not exists
                String globalFormat = localeManager != null 
                    ? localeManager.getMessage("chat.format.global") 
                    : "<gray>[<color:#00ff00>Global</color>]</gray> {message}";
                
                try (PreparedStatement pstmt = conn.prepareStatement(
                    "MERGE INTO chat_channels (name, format, global) VALUES (?, ?, ?)"
                )) {
                    pstmt.setString(1, "global");
                    pstmt.setString(2, globalFormat);
                    pstmt.setBoolean(3, true);
                    pstmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            DebugLogger.severe("ChatManager", "Failed to initialize database tables", e);
            throw new RuntimeException("Failed to initialize chat database tables", e);
        }
    }

    private void loadChannels() {
        try (Connection conn = databaseManager.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT * FROM chat_channels");
                while (rs.next()) {
                    String name = rs.getString("name");
                    String format = rs.getString("format");
                    String permission = rs.getString("permission");
                    boolean global = rs.getBoolean("global");
                    
                    // Remove player name from format if present
                    if (format.contains("{player}")) {
                        format = format.replace("{player}: ", "").replace("{player}:", "");
                        // Update in database
                        try (PreparedStatement updateStmt = conn.prepareStatement(
                            "UPDATE chat_channels SET format = ? WHERE name = ?"
                        )) {
                            updateStmt.setString(1, format);
                            updateStmt.setString(2, name);
                            updateStmt.executeUpdate();
                            DebugLogger.debug("ChatManager", "Updated format for channel: " + name);
                        }
                    }
                    
                    channels.put(name, new ChatChannel(name, format, permission, global));
                }
            }
        } catch (SQLException e) {
            DebugLogger.severe("ChatManager", "Failed to load chat channels", e);
        }
    }

    private void loadMutedPlayers() {
        try (Connection conn = databaseManager.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT * FROM muted_players");
                while (rs.next()) {
                    UUID playerUuid = UUID.fromString(rs.getString("uuid"));
                    UUID mutedBy = UUID.fromString(rs.getString("muted_by"));
                    String reason = rs.getString("reason");
                    Timestamp expiresAt = rs.getTimestamp("expires_at");
                    
                    Instant muteTime = Instant.now(); // Default to current time if not stored
                    
                    if (expiresAt != null) {
                        // Skip expired mutes
                        if (expiresAt.toInstant().isBefore(Instant.now())) {
                            continue;
                        }
                        mutedPlayers.put(playerUuid, new MuteData(
                            muteTime,
                            expiresAt.toInstant(),
                            reason,
                            mutedBy
                        ));
                    } else {
                        // Permanent mute
                        mutedPlayers.put(playerUuid, new MuteData(
                            muteTime,
                            null,
                            reason,
                            mutedBy
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            DebugLogger.severe("ChatManager", "Failed to load muted players", e);
        }
    }

    private void startMuteCleanupTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Instant now = Instant.now();
            mutedPlayers.entrySet().removeIf(entry -> {
                MuteData muteData = entry.getValue();
                if (muteData.getExpiry() != null && now.isAfter(muteData.getExpiry())) {
                    // Remove from database
                    try (Connection conn = databaseManager.getConnection()) {
                        try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM muted_players WHERE uuid = ?"
                        )) {
                            stmt.setString(1, entry.getKey().toString());
                            stmt.executeUpdate();
                        }
                    } catch (SQLException e) {
                        DebugLogger.severe("ChatManager", "Failed to remove expired mute", e);
                    }
                    
                                         // Notify player if online
                     Player player = Bukkit.getPlayer(entry.getKey());
                     if (player != null) {
                         if (localeManager != null) {
                             player.sendMessage(localeManager.getComponent("chat.mute.expired"));
                         } else {
                             player.sendMessage(Component.text("Your mute has expired."));
                         }
                     }
                    
                    return true;
                }
                return false;
            });
        }, MUTE_CHECK_INTERVAL * 20L, MUTE_CHECK_INTERVAL * 20L);
    }
    
    private String getPlayerPrefix(Player player) {
        String prefix = "";
        
        // Get prefix from LuckPerms if available
        if (luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String lpPrefix = user.getCachedData().getMetaData().getPrefix();
                if (lpPrefix != null) {
                    // Convert legacy color codes to MiniMessage format
                    prefix = convertLegacyColors(lpPrefix);
                }
            }
        }
        
        // Process PlaceholderAPI placeholders if available
        if (placeholderAPIEnabled) {
            prefix = PlaceholderAPI.setPlaceholders(player, prefix);
            // If no LuckPerms prefix, try getting it from PlaceholderAPI
            if (prefix.isEmpty()) {
                prefix = convertLegacyColors(PlaceholderAPI.setPlaceholders(player, "%luckperms_prefix%"));
            }
        }
        
        return prefix.isEmpty() ? "" : prefix + " ";
    }

    private String convertLegacyColors(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        // Replace legacy color codes with MiniMessage format
        return text.replace("&0", "<black>")
                  .replace("&1", "<dark_blue>")
                  .replace("&2", "<dark_green>")
                  .replace("&3", "<dark_aqua>")
                  .replace("&4", "<dark_red>")
                  .replace("&5", "<dark_purple>")
                  .replace("&6", "<gold>")
                  .replace("&7", "<gray>")
                  .replace("&8", "<dark_gray>")
                  .replace("&9", "<blue>")
                  .replace("&a", "<green>")
                  .replace("&b", "<aqua>")
                  .replace("&c", "<red>")
                  .replace("&d", "<light_purple>")
                  .replace("&e", "<yellow>")
                  .replace("&f", "<white>")
                  .replace("&l", "<bold>")
                  .replace("&m", "<strikethrough>")
                  .replace("&n", "<underline>")
                  .replace("&o", "<italic>")
                  .replace("&r", "<reset>")
                  // Also handle § color codes
                  .replace("§0", "<black>")
                  .replace("§1", "<dark_blue>")
                  .replace("§2", "<dark_green>")
                  .replace("§3", "<dark_aqua>")
                  .replace("§4", "<dark_red>")
                  .replace("§5", "<dark_purple>")
                  .replace("§6", "<gold>")
                  .replace("§7", "<gray>")
                  .replace("§8", "<dark_gray>")
                  .replace("§9", "<blue>")
                  .replace("§a", "<green>")
                  .replace("§b", "<aqua>")
                  .replace("§c", "<red>")
                  .replace("§d", "<light_purple>")
                  .replace("§e", "<yellow>")
                  .replace("§f", "<white>")
                  .replace("§l", "<bold>")
                  .replace("§m", "<strikethrough>")
                  .replace("§n", "<underline>")
                  .replace("§o", "<italic>")
                  .replace("§r", "<reset>");
    }

    public boolean createChannel(@NotNull String name, @NotNull String format, @Nullable String permission) {
        if (channels.containsKey(name)) {
            return false;
        }
        
        // Remove player name from format if present
        if (format.contains("{player}")) {
            format = format.replace("{player}: ", "").replace("{player}:", "");
        }

        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO chat_channels (name, format, permission, global)
                VALUES (?, ?, ?, ?)
            """)) {
                stmt.setString(1, name);
                stmt.setString(2, format);
                stmt.setString(3, permission);
                stmt.setBoolean(4, true); // Default to global channel
                stmt.executeUpdate();
                
                channels.put(name, new ChatChannel(name, format, permission, true));
                return true;
            }
        } catch (SQLException e) {
            DebugLogger.severe("ChatManager", "Failed to create chat channel: " + name, e);
            return false;
        }
    }

    public boolean deleteChannel(@NotNull String name) {
        if (name.equals(DEFAULT_CHANNEL)) {
            return false;
        }

        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM chat_channels WHERE name = ?")) {
                stmt.setString(1, name);
                int affected = stmt.executeUpdate();
                
                if (affected > 0) {
                    channels.remove(name);
                    
                    // Move players in this channel to default channel
                    playerChannels.entrySet().removeIf(entry -> {
                        if (entry.getValue().equals(name)) {
                            Player player = Bukkit.getPlayer(entry.getKey());
                                                         if (player != null) {
                                if (localeManager != null) {
                                    player.sendMessage(localeManager.getComponent("chat.format.global", name, DEFAULT_CHANNEL));
                                } else {
                                    player.sendMessage(Component.text("Channel " + name + " has been deleted. You have been moved to " + DEFAULT_CHANNEL));
                                }
                            }
                            return true;
                        }
                        return false;
                    });
                    
                    return true;
                }
                return false;
            }
        } catch (SQLException e) {
            DebugLogger.severe("ChatManager", "Failed to delete chat channel: " + name, e);
            return false;
        }
    }

    public boolean setPlayerChannel(@NotNull Player player, @NotNull String channelName) {
        if (!channels.containsKey(channelName)) {
            return false;
        }

        ChatChannel chatChannel = channels.get(channelName);
        if (chatChannel.permission != null && !player.hasPermission(chatChannel.permission)) {
            if (localeManager != null) {
                player.sendMessage(localeManager.getComponent("chat.no_permission"));
            } else {
                player.sendMessage(Component.text("You don't have permission to join this channel"));
            }
            return false;
        }

        String oldChannel = playerChannels.put(player.getUniqueId(), channelName);
        
        if (localeManager != null) {
            player.sendMessage(localeManager.getComponent("chat.format.global", channelName));
        } else {
            player.sendMessage(Component.text("Switched to channel " + channelName));
        }
        
        if (oldChannel != null && !oldChannel.equals(channelName)) {
            Component leftMessage = localeManager != null ? 
                localeManager.getComponent("chat.format.global", player.getName()) : 
                Component.text(player.getName() + " has left the channel");
            broadcastToChannel(oldChannel, leftMessage);
        }
        
        Component joinMessage = localeManager != null ? 
            localeManager.getComponent("chat.format.global", player.getName()) : 
            Component.text(player.getName() + " has joined the channel");
        broadcastToChannel(channelName, joinMessage);
        return true;
    }

    public boolean mutePlayer(@NotNull UUID playerUuid, @NotNull UUID mutedBy, @Nullable String reason, @Nullable Duration duration) {
        if (isPlayerMuted(playerUuid)) {
            return false;
        }
        Instant expiresAt = duration != null ? Instant.now().plus(duration) : null;
        
        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                MERGE INTO muted_players (uuid, muted_by, reason, expires_at)
                VALUES (?, ?, ?, ?)
            """)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, mutedBy.toString());
                stmt.setString(3, reason);
                stmt.setTimestamp(4, expiresAt != null ? Timestamp.from(expiresAt) : null);
                stmt.executeUpdate();
                
                mutedPlayers.put(playerUuid, new MuteData(
                    Instant.now(),
                    expiresAt,
                    reason,
                    mutedBy
                ));
                
                // Notify player if online
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null) {
                    if (localeManager != null) {
                        String timeText = duration != null ? formatDuration(duration) : localeManager.getMessage("chat.mute.permanent");
                        player.sendMessage(localeManager.getComponent("chat.mute.muted", reason != null ? reason : "", timeText));
                    } else {
                        String reasonText = reason != null ? " for: " + reason : "";
                        String timeText = duration != null ? formatDuration(duration) : "PERMANENT";
                        player.sendMessage(Component.text("You have been muted" + reasonText + ". Time remaining: " + timeText));
                    }
                }
                return true;
            }
        } catch (SQLException e) {
            DebugLogger.severe("ChatManager", "Failed to mute player: " + playerUuid, e);
            return false;
        }
    }

    public boolean unmutePlayer(@NotNull UUID playerUuid) {
        if (!mutedPlayers.containsKey(playerUuid)) {
            return false;
        }

        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM muted_players WHERE uuid = ?")) {
                stmt.setString(1, playerUuid.toString());
                int affected = stmt.executeUpdate();
                
                if (affected > 0) {
                    mutedPlayers.remove(playerUuid);
                    
                    // Notify player if online
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player != null) {
                        if (localeManager != null) {
                            player.sendMessage(localeManager.getComponent("chat.mute.unmuted"));
                        } else {
                            player.sendMessage(Component.text("You have been unmuted"));
                        }
                    }
                    
                    return true;
                }
                return false;
            }
        } catch (SQLException e) {
            DebugLogger.severe("ChatManager", "Failed to unmute player: " + playerUuid, e);
            return false;
        }
    }

    public boolean isPlayerMuted(@NotNull UUID playerUuid) {
        MuteData muteData = mutedPlayers.get(playerUuid);
        if (muteData == null) {
            return false;
        }
        
        if (muteData.getExpiry() != null && Instant.now().isAfter(muteData.getExpiry())) {
            unmutePlayer(playerUuid);
            return false;
        }
        
        return true;
    }

    @Nullable
    public MuteData getMuteData(@NotNull UUID playerUuid) {
        return mutedPlayers.get(playerUuid);
    }

    @NotNull
    public Set<String> getChannelNames() {
        return Collections.unmodifiableSet(channels.keySet());
    }

    @NotNull
    public Map<String, ChatChannel> getChannels() {
        return Collections.unmodifiableMap(channels);
    }

    @Nullable
    public String getPlayerChannel(@NotNull UUID playerUuid) {
        return playerChannels.get(playerUuid);
    }

    private void broadcastToChannel(@NotNull String channel, @NotNull Component message) {
        for (Map.Entry<UUID, String> entry : playerChannels.entrySet()) {
            if (entry.getValue().equals(channel)) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    player.sendMessage(message);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChat(AsyncChatEvent event) {
        if (event.isCancelled()) return;
        
        Player player = event.getPlayer();
        
        // Check if player is muted
        if (isPlayerMuted(player.getUniqueId())) {
            MuteData muteData = getMuteData(player.getUniqueId());
            
            if (localeManager != null) {
                String timeText = muteData.getExpiry() != null 
                    ? formatDuration(Duration.between(Instant.now(), muteData.getExpiry()))
                    : localeManager.getMessage("chat.mute.permanent");
                
                player.sendMessage(localeManager.getComponent("chat.mute.muted", 
                    muteData.getReason() != null ? muteData.getReason() : "", 
                    timeText));
            } else {
                String reasonText = muteData.getReason() != null ? " for: " + muteData.getReason() : "";
                String timeText = muteData.getExpiry() != null 
                    ? formatDuration(Duration.between(Instant.now(), muteData.getExpiry()))
                    : "PERMANENT";
                
                player.sendMessage(Component.text("You are muted" + reasonText + ". Time remaining: " + timeText));
            }
            
            event.setCancelled(true);
            return;
        }
        
        // Get player's channel
        String channelName = playerChannels.getOrDefault(player.getUniqueId(), DEFAULT_CHANNEL);
        ChatChannel channel = channels.get(channelName);
        
        if (channel == null) {
            // Fallback to default channel
            channel = channels.get(DEFAULT_CHANNEL);
            playerChannels.put(player.getUniqueId(), DEFAULT_CHANNEL);
        }

        // Get player's prefix
        String prefix = getPlayerPrefix(player);
        
        // Get the message content
        Component messageComponent = event.message();
        
        // Broadcast the message
        Component formattedMessage = localeManager.getComponent("chat.format.global", 
            prefix + player.getName(), 
            PlainTextComponentSerializer.plainText().serialize(messageComponent));
        Bukkit.broadcast(formattedMessage);
        
        // Cancel the original event since we're handling the broadcast ourselves
        event.setCancelled(true);
        
        // Store last message for reply command
        lastMessage.put(player.getUniqueId(), PlainTextComponentSerializer.plainText().serialize(messageComponent));
    }

    public void sendPrivateMessage(Player sender, Player recipient, String message) {
        // Get sender's prefix
        String senderPrefix = getPlayerPrefix(sender);
        
        // Get recipient's prefix
        String recipientPrefix = getPlayerPrefix(recipient);
        
        // Format and send messages
        Component outgoingMessage = localeManager.getComponent("chat.format.outgoing", 
            senderPrefix + sender.getName(), message);
        Component incomingMessage = localeManager.getComponent("chat.format.incoming", 
            recipientPrefix + recipient.getName(), message);
        
        sender.sendMessage(outgoingMessage);
        recipient.sendMessage(incomingMessage);
        
        // Store last message data
        lastMessageFrom.put(recipient.getUniqueId(), sender.getUniqueId());
        lastMessage.put(sender.getUniqueId(), message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Set default channel
        if (!playerChannels.containsKey(player.getUniqueId())) {
            playerChannels.put(player.getUniqueId(), DEFAULT_CHANNEL);
        }
        
        // Check if player is muted
        if (isPlayerMuted(player.getUniqueId())) {
            MuteData muteData = getMuteData(player.getUniqueId());
            
            if (localeManager != null) {
                String timeText = muteData.getExpiry() != null 
                    ? formatDuration(Duration.between(Instant.now(), muteData.getExpiry()))
                    : localeManager.getMessage("chat.mute.permanent");
                
                player.sendMessage(localeManager.getComponent("chat.mute.muted_join", 
                    muteData.getReason() != null ? muteData.getReason() : "", 
                    timeText));
            } else {
                String reasonText = muteData.getReason() != null ? " for: " + muteData.getReason() : "";
                String timeText = muteData.getExpiry() != null 
                    ? formatDuration(Duration.between(Instant.now(), muteData.getExpiry()))
                    : "PERMANENT";
                
                player.sendMessage(Component.text("You are currently muted" + reasonText + ". Time remaining: " + timeText));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        
        // Remove from channel
        String channel = playerChannels.remove(playerUuid);
        if (channel != null && localeManager != null) {
            try {
                Component message = localeManager.getComponent("chat.format.global", player.getName());
                broadcastToChannel(channel, message);
            } catch (Exception e) {
                // Fallback if locale manager fails
                broadcastToChannel(channel, Component.text(player.getName() + " has left the channel"));
            }
        }
        
        // Clean up last message data
        lastMessage.remove(playerUuid);
        lastMessageFrom.remove(playerUuid);
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m ");
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append("s");
        }

        return sb.toString().trim();
    }

    public static class ChatChannel {
        private final String name;
        private final String format;
        private final String permission;
        private final boolean global;

        public ChatChannel(String name, String format, String permission, boolean global) {
            this.name = name;
            this.format = format;
            this.permission = permission;
            this.global = global;
        }

        public String getName() {
            return name;
        }

        public String getFormat() {
            return format;
        }

        public String getPermission() {
            return permission;
        }

        public boolean isGlobal() {
            return global;
        }
    }

    public static class MuteData {
        private final Instant muteTime;
        private final Instant expiry;
        private final String reason;
        private final UUID mutedBy;

        public MuteData(Instant muteTime, Instant expiry, String reason, UUID mutedBy) {
            this.muteTime = muteTime;
            this.expiry = expiry;
            this.reason = reason;
            this.mutedBy = mutedBy;
        }

        public Instant getMuteTime() {
            return muteTime;
        }

        public Instant getExpiry() {
            return expiry;
        }

        public String getReason() {
            return reason;
        }

        public UUID getMutedBy() {
            return mutedBy;
        }

        public boolean hasExpired() {
            return expiry != null && Instant.now().isAfter(expiry);
        }
    }
} 