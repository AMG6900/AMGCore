package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

public class LogManager {
    private final AMGCore plugin;
    private final File logsDirectory;
    private final SimpleDateFormat dateFormat;
    private final ReentrantLock logLock;
    
    // Log files and configurations
    private YamlConfiguration modLog;
    private YamlConfiguration itemLog;
    private YamlConfiguration banLog;
    private YamlConfiguration chatLog;
    private YamlConfiguration commandLog;
    private File modLogFile;
    private File itemLogFile;
    private File banLogFile;
    private File chatLogFile;
    private File commandLogFile;

    private static final int MAX_LOG_SIZE = 10 * 1024 * 1024; // 10MB

    public LogManager(@NotNull AMGCore plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.logsDirectory = new File(plugin.getDataFolder(), "logs");
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        this.logLock = new ReentrantLock();
        
        initializeLogFiles();
    }

    private void initializeLogFiles() {
        try {
            logLock.lock();
            
            if (!logsDirectory.exists() && !logsDirectory.mkdirs()) {
                plugin.getLogger().severe("Failed to create logs directory!");
                return;
            }

            // Initialize mod actions log
            modLogFile = new File(logsDirectory, "mod_actions.yml");
            modLog = loadOrCreateLog(modLogFile);

            // Initialize item transactions log
            itemLogFile = new File(logsDirectory, "item_transactions.yml");
            itemLog = loadOrCreateLog(itemLogFile);

            // Initialize ban/kick log
            banLogFile = new File(logsDirectory, "bans_kicks.yml");
            banLog = loadOrCreateLog(banLogFile);

            // Initialize chat log
            chatLogFile = new File(logsDirectory, "chat.yml");
            chatLog = loadOrCreateLog(chatLogFile);

            // Initialize command log
            commandLogFile = new File(logsDirectory, "commands.yml");
            commandLog = loadOrCreateLog(commandLogFile);
        } finally {
            logLock.unlock();
        }
    }

    private YamlConfiguration loadOrCreateLog(@NotNull File file) {
        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    plugin.getLogger().severe("Failed to create log file: " + file.getName());
                    return new YamlConfiguration();
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Error creating log file: " + file.getName(), e);
                return new YamlConfiguration();
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    public void logModAction(@NotNull String staffMember, @NotNull String action, @NotNull String target, @NotNull String reason) {
        Objects.requireNonNull(staffMember, "Staff member cannot be null");
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(target, "Target cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");

        try {
            logLock.lock();
            String timestamp = dateFormat.format(new Date());
            String entry = String.format("%s by %s | Target: %s | Details: %s", 
                action, staffMember, target, reason);
            
            modLog.set(timestamp, entry);
            saveLog(modLog, modLogFile, "mod actions");
            checkLogSize(modLogFile);
        } finally {
            logLock.unlock();
        }
    }

    public void logItemTransaction(@NotNull String player, @NotNull String action, @NotNull String item, int amount, @NotNull String location) {
        Objects.requireNonNull(player, "Player cannot be null");
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(item, "Item cannot be null");
        Objects.requireNonNull(location, "Location cannot be null");

        try {
            logLock.lock();
            String timestamp = dateFormat.format(new Date());
            String entry = String.format("%s %dx %s at %s", 
                action, amount, item, location);
            
            itemLog.set(timestamp + "." + player, entry);
            saveLog(itemLog, itemLogFile, "item transactions");
            checkLogSize(itemLogFile);
        } finally {
            logLock.unlock();
        }
    }

    public void logBanKick(@NotNull String staffMember, @NotNull String action, @NotNull String target, String duration, @NotNull String reason) {
        Objects.requireNonNull(staffMember, "Staff member cannot be null");
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(target, "Target cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");

        try {
            logLock.lock();
            String timestamp = dateFormat.format(new Date());
            String entry = String.format("%s by %s | Duration: %s | Reason: %s", 
                action, staffMember, duration != null ? duration : "PERMANENT", reason);
            
            banLog.set(timestamp + "." + target, entry);
            saveLog(banLog, banLogFile, "bans and kicks");
            checkLogSize(banLogFile);
        } finally {
            logLock.unlock();
        }
    }

    public void logChat(@NotNull String player, @NotNull String message, int recipients) {
        Objects.requireNonNull(player, "Player cannot be null");
        Objects.requireNonNull(message, "Message cannot be null");

        try {
            logLock.lock();
            String timestamp = dateFormat.format(new Date());
            String entry = String.format("To %d players: %s", 
                recipients, message);
            
            chatLog.set(timestamp + "." + player, entry);
            saveLog(chatLog, chatLogFile, "chat messages");
            checkLogSize(chatLogFile);
        } finally {
            logLock.unlock();
        }
    }

    public void logCommand(@NotNull String player, @NotNull String command, boolean success) {
        Objects.requireNonNull(player, "Player cannot be null");
        Objects.requireNonNull(command, "Command cannot be null");

        try {
            logLock.lock();
            String timestamp = dateFormat.format(new Date());
            String entry = String.format("%s | Success: %s", 
                command, success ? "Yes" : "No");
            
            commandLog.set(timestamp + "." + player, entry);
            saveLog(commandLog, commandLogFile, "commands");
            checkLogSize(commandLogFile);
        } finally {
            logLock.unlock();
        }
    }

    private void saveLog(@NotNull YamlConfiguration log, @NotNull File file, @NotNull String logType) {
        try {
            log.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + logType + " log", e);
        }
    }

    private void checkLogSize(@NotNull File logFile) {
        if (logFile.length() > MAX_LOG_SIZE) {
            rotateLog(logFile, logFile.getName().replace(".yml", ""));
        }
    }

    public void rotateLogs() {
        try {
            logLock.lock();
            rotateLog(modLogFile, "mod_actions");
            rotateLog(itemLogFile, "item_transactions");
            rotateLog(banLogFile, "bans_kicks");
            rotateLog(chatLogFile, "chat");
            rotateLog(commandLogFile, "commands");
            
            // Reinitialize log files
            initializeLogFiles();
        } finally {
            logLock.unlock();
        }
    }

    private void rotateLog(@NotNull File logFile, @NotNull String baseName) {
        if (!logFile.exists()) {
            return;
        }

        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        File backupFile = new File(logsDirectory, baseName + "_" + date + ".yml");
        
        // If a backup from today already exists, append a number
        int counter = 1;
        while (backupFile.exists()) {
            backupFile = new File(logsDirectory, baseName + "_" + date + "_" + counter + ".yml");
            counter++;
        }
        
        if (!logFile.renameTo(backupFile)) {
            plugin.getLogger().warning("Failed to rotate " + baseName + " log");
            return;
        }
        
        plugin.getLogger().info("Rotated " + baseName + " log to " + backupFile.getName());
        
        try {
            if (!logFile.createNewFile()) {
                plugin.getLogger().severe("Failed to create new log file after rotation: " + logFile.getName());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Error creating new log file after rotation: " + logFile.getName(), e);
        }
    }
} 