package amg.plugins.aMGCore.utils;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A centralized utility for handling debug logging throughout the plugin.
 * This class is thread-safe and provides consistent logging format.
 */
public final class DebugLogger {
    private static final AtomicBoolean enabled = new AtomicBoolean(false);
    private static volatile Logger logger;
    private static volatile String pluginName;
    private static final Object LOCK = new Object();
    
    // Log level configuration
    public enum LogLevel {
        DEBUG(0),
        INFO(1),
        WARNING(2),
        SEVERE(3);
        
        private final int value;
        
        LogLevel(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public boolean isAtLeast(LogLevel other) {
            return this.value >= other.value;
        }
    }
    
    private static volatile LogLevel minLogLevel = LogLevel.DEBUG;
    
    // Buffered logging
    private static final Queue<LogEntry> logBuffer = new ConcurrentLinkedQueue<>();
    private static final int ADAPTIVE_FLUSH_THRESHOLD = 100; // Flush when buffer reaches this size
    private static final long FLUSH_INTERVAL_MS = 2000; // 2 seconds (increased from 1 second)
    private static ScheduledExecutorService logExecutor;
    private static volatile File logFile;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    
    // Message deduplication
    private static final Map<String, DuplicateEntry> recentMessages = new ConcurrentHashMap<>();
    private static final long DUPLICATE_WINDOW_MS = 5000; // 5 seconds
    private static final int MAX_DUPLICATES_TO_TRACK = 1000;
    
    // Source filtering
    private static final Map<String, LogLevel> sourceLogLevels = new ConcurrentHashMap<>();
    
    // Statistics
    private static final AtomicLong totalLogEntries = new AtomicLong(0);
    private static final AtomicLong skippedLogEntries = new AtomicLong(0);
    private static final AtomicLong duplicateEntries = new AtomicLong(0);
    private static final AtomicLong flushCount = new AtomicLong(0);
    private static final AtomicLong totalFlushTime = new AtomicLong(0);
    private static final AtomicInteger maxBufferSize = new AtomicInteger(0);
    
    // File writer
    private static volatile PrintWriter cachedWriter;
    private static final AtomicLong lastWriteTime = new AtomicLong(0);
    private static final long WRITER_KEEP_ALIVE_MS = 30000; // 30 seconds

    // Private constructor to prevent instantiation
    private DebugLogger() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Initialize the debug logger with plugin information.
     * 
     * @param plugin the plugin instance
     * @param debugEnabled whether debug logging is enabled
     * @throws IllegalArgumentException if plugin is null
     */
    public static void initialize(@NotNull Plugin plugin, boolean debugEnabled) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        
        synchronized (LOCK) {
            logger = plugin.getLogger();
            pluginName = plugin.getName();
            setEnabled(debugEnabled);
            
            // Set up log file
            File logsDir = new File(plugin.getDataFolder(), "logs");
            if (!logsDir.exists() && !logsDir.mkdirs()) {
                logger.warning("Failed to create logs directory");
            }
            
            logFile = new File(logsDir, "debug-" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".log");
            
            // Initialize log executor if needed
            if (logExecutor == null || logExecutor.isShutdown()) {
                logExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "AMGCore-LogWriter");
                    t.setDaemon(true);
                    return t;
                });
                
                // Schedule periodic flush
                logExecutor.scheduleWithFixedDelay(
                    DebugLogger::flushLogBuffer,
                    FLUSH_INTERVAL_MS,
                    FLUSH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
                );
                
                // Schedule periodic cleanup of duplicate tracking
                logExecutor.scheduleWithFixedDelay(
                    DebugLogger::cleanupDuplicateTracking,
                    DUPLICATE_WINDOW_MS,
                    DUPLICATE_WINDOW_MS,
                    TimeUnit.MILLISECONDS
                );
                
                // Schedule writer cleanup
                logExecutor.scheduleWithFixedDelay(
                    DebugLogger::cleanupWriter,
                    WRITER_KEEP_ALIVE_MS,
                    WRITER_KEEP_ALIVE_MS,
                    TimeUnit.MILLISECONDS
                );
            }
        }
    }

    /**
     * Set whether debug logging is enabled.
     * 
     * @param debugEnabled true to enable debug logging, false to disable it
     */
    public static void setEnabled(boolean debugEnabled) {
        boolean oldValue = enabled.getAndSet(debugEnabled);
        
        if (oldValue != debugEnabled && logger != null) {
            logger.info("Debug mode " + (debugEnabled ? "enabled" : "disabled"));
            
            // Flush buffer immediately when enabling/disabling
            if (debugEnabled) {
                flushLogBuffer();
            }
        }
    }

    /**
     * Check if debug logging is enabled.
     * 
     * @return true if debug logging is enabled, false otherwise
     */
    public static boolean isEnabled() {
        return enabled.get();
    }
    
    /**
     * Set the minimum log level for all sources.
     * 
     * @param level the minimum log level
     */
    public static void setMinLogLevel(LogLevel level) {
        if (level == null) {
            level = LogLevel.DEBUG;
        }
        minLogLevel = level;
    }
    
    /**
     * Set the minimum log level for a specific source.
     * 
     * @param source the source to set the level for
     * @param level the minimum log level
     */
    public static void setSourceLogLevel(String source, LogLevel level) {
        if (source == null || level == null) {
            return;
        }
        sourceLogLevels.put(source, level);
    }
    
    /**
     * Get the minimum log level for a specific source.
     * 
     * @param source the source to get the level for
     * @return the minimum log level
     */
    private static LogLevel getSourceLogLevel(String source) {
        return sourceLogLevels.getOrDefault(source, minLogLevel);
    }
    
    /**
     * Check if a log entry should be logged based on its level and source.
     * 
     * @param source the source of the log entry
     * @param level the log level
     * @return true if the entry should be logged, false otherwise
     */
    private static boolean shouldLog(String source, LogLevel level) {
        if (!enabled.get()) {
            return false;
        }
        
        LogLevel sourceLevel = getSourceLogLevel(source);
        return level.isAtLeast(sourceLevel);
    }
    
    /**
     * Check if a message is a duplicate of a recently logged message.
     * 
     * @param source the source of the log entry
     * @param message the message to check
     * @param level the log level
     * @return true if the message is a duplicate, false otherwise
     */
    private static boolean isDuplicate(String source, String message, LogLevel level) {
        if (level.isAtLeast(LogLevel.WARNING)) {
            // Don't deduplicate warnings and errors
            return false;
        }
        
        String key = source + ":" + message;
        long now = System.currentTimeMillis();
        
        DuplicateEntry entry = recentMessages.get(key);
        if (entry != null && now - entry.timestamp < DUPLICATE_WINDOW_MS) {
            entry.count++;
            entry.timestamp = now;
            duplicateEntries.incrementAndGet();
            return true;
        }
        
        // Limit the size of the duplicate tracking map
        if (recentMessages.size() >= MAX_DUPLICATES_TO_TRACK) {
            cleanupDuplicateTracking();
        }
        
        recentMessages.put(key, new DuplicateEntry(now));
        return false;
    }
    
    /**
     * Clean up old entries in the duplicate tracking map.
     */
    private static void cleanupDuplicateTracking() {
        long cutoff = System.currentTimeMillis() - DUPLICATE_WINDOW_MS;
        recentMessages.entrySet().removeIf(entry -> entry.getValue().timestamp < cutoff);
    }
    
    /**
     * Clean up the writer if it hasn't been used recently.
     */
    private static void cleanupWriter() {
        long now = System.currentTimeMillis();
        if (cachedWriter != null && now - lastWriteTime.get() > WRITER_KEEP_ALIVE_MS) {
            synchronized (LOCK) {
                if (cachedWriter != null) {
                    cachedWriter.close();
                    cachedWriter = null;
                }
            }
        }
    }

    /**
     * Log a debug message if debug mode is enabled.
     * 
     * @param source the class or component generating the debug message
     * @param message the debug message to log
     */
    public static void debug(@NotNull String source, @NotNull String message) {
        log(source, message, null, LogLevel.DEBUG);
    }

    /**
     * Log a debug message with exception details if debug mode is enabled.
     * 
     * @param source the class or component generating the debug message
     * @param message the debug message to log
     * @param throwable the exception to log
     */
    public static void debug(@NotNull String source, @NotNull String message, @NotNull Throwable throwable) {
        log(source, message, throwable, LogLevel.DEBUG);
    }

    /**
     * Log an info message regardless of debug mode.
     * 
     * @param source the class or component generating the message
     * @param message the message to log
     */
    public static void info(@NotNull String source, @NotNull String message) {
        log(source, message, null, LogLevel.INFO);
    }
    
    /**
     * Log an info message with exception details regardless of debug mode.
     * 
     * @param source the class or component generating the message
     * @param message the message to log
     * @param throwable the exception to log
     */
    public static void info(@NotNull String source, @NotNull String message, @NotNull Throwable throwable) {
        log(source, message, throwable, LogLevel.INFO);
    }

    /**
     * Log a warning message that will be shown regardless of debug mode.
     * These are important messages that should always be shown.
     * 
     * @param source the class or component generating the warning
     * @param message the warning message to log
     */
    public static void warning(@NotNull String source, @NotNull String message) {
        log(source, message, null, LogLevel.WARNING);
    }
    
    /**
     * Log a warning message with exception details that will be shown regardless of debug mode.
     * 
     * @param source the class or component generating the warning
     * @param message the warning message to log
     * @param throwable the exception to log
     */
    public static void warning(@NotNull String source, @NotNull String message, @NotNull Throwable throwable) {
        log(source, message, throwable, LogLevel.WARNING);
    }

    /**
     * Log a severe error message that will be shown regardless of debug mode.
     * 
     * @param source the class or component generating the error
     * @param message the error message to log
     * @param throwable the exception to log
     */
    public static void severe(@NotNull String source, @NotNull String message, @NotNull Throwable throwable) {
        log(source, message, throwable, LogLevel.SEVERE);
    }
    
    /**
     * Log a severe error message that will be shown regardless of debug mode.
     * 
     * @param source the class or component generating the error
     * @param message the error message to log
     */
    public static void severe(@NotNull String source, @NotNull String message) {
        log(source, message, null, LogLevel.SEVERE);
    }
    
    /**
     * Log a message with the specified log level.
     * 
     * @param source the source of the log entry
     * @param message the message to log
     * @param throwable the exception to log, or null if none
     * @param level the log level
     */
    private static void log(String source, String message, Throwable throwable, LogLevel level) {
        totalLogEntries.incrementAndGet();
        
        if (!shouldLog(source, level)) {
            skippedLogEntries.incrementAndGet();
            return;
        }
        
        // Check for duplicates
        if (isDuplicate(source, message, level)) {
            return;
        }
        
        // Create log entry
        LogEntry entry = new LogEntry(
            System.currentTimeMillis(),
            source,
            message,
            throwable,
            level
        );
        
        // Add to buffer
        logBuffer.offer(entry);
        
        // Update max buffer size statistic
        int currentSize = logBuffer.size();
        int currentMax = maxBufferSize.get();
        if (currentSize > currentMax) {
            maxBufferSize.compareAndSet(currentMax, currentSize);
        }
        
        // Adaptive flush based on buffer size and log level
        boolean shouldFlushNow = false;
        
        if (level.isAtLeast(LogLevel.SEVERE)) {
            // Always flush immediately for severe errors
            shouldFlushNow = true;
        } else if (level.isAtLeast(LogLevel.WARNING) && currentSize >= ADAPTIVE_FLUSH_THRESHOLD / 2) {
            // Flush sooner for warnings
            shouldFlushNow = true;
        } else if (currentSize >= ADAPTIVE_FLUSH_THRESHOLD) {
            // Regular adaptive flush
            shouldFlushNow = true;
        }
        
        if (shouldFlushNow) {
            // Schedule immediate flush
            if (logExecutor != null && !logExecutor.isShutdown()) {
                logExecutor.execute(DebugLogger::flushLogBuffer);
            } else {
                // Fallback if executor is not available
                flushLogBuffer();
            }
        }
        
        // Also log to console for warnings and severe errors
        if (level.isAtLeast(LogLevel.WARNING) && logger != null) {
            try {
                Level javaLevel = level == LogLevel.WARNING ? Level.WARNING : Level.SEVERE;
                String formattedMessage = "[" + source + "] " + message;
                
                if (throwable != null) {
                    logger.log(javaLevel, formattedMessage, throwable);
                } else {
                    logger.log(javaLevel, formattedMessage);
                }
            } catch (Exception e) {
                // Fallback to system out if logger fails
                System.err.println("[" + pluginName + "] [" + level + "] [" + source + "] " + message);
                if (throwable != null) {
                    throwable.printStackTrace();
                }
            }
        }
    }
    
    /**
     * Get a PrintWriter for the log file, creating it if necessary.
     * 
     * @return a PrintWriter for the log file, or null if it couldn't be created
     */
    private static PrintWriter getWriter() {
        if (cachedWriter != null) {
            return cachedWriter;
        }
        
        synchronized (LOCK) {
            if (cachedWriter != null) {
                return cachedWriter;
            }
            
            if (logFile == null) {
                return null;
            }
            
            try {
                // Use BufferedWriter for better performance
                cachedWriter = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true), 8192));
                lastWriteTime.set(System.currentTimeMillis());
                return cachedWriter;
            } catch (IOException e) {
                System.err.println("Failed to create log writer: " + e.getMessage());
                return null;
            }
        }
    }
    
    /**
     * Flush the log buffer to the log file.
     */
    private static void flushLogBuffer() {
        if (logBuffer.isEmpty() || logFile == null) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        int flushedCount = 0;
        
        PrintWriter writer = getWriter();
        if (writer == null) {
            return;
        }
        
        try {
            LogEntry entry;
            
            // Process duplicate counts
            Map<String, Integer> duplicateCounts = new ConcurrentHashMap<>();
            
            while ((entry = logBuffer.poll()) != null) {
                flushedCount++;
                
                // Format: [TIMESTAMP] [LEVEL] [SOURCE] MESSAGE
                writer.print("[");
                writer.print(dateFormat.format(new Date(entry.timestamp)));
                writer.print("] [");
                writer.print(entry.level);
                writer.print("] [");
                writer.print(entry.source);
                writer.print("] ");
                writer.println(entry.message);
                
                // Print stack trace if available
                if (entry.throwable != null) {
                    entry.throwable.printStackTrace(writer);
                    writer.println();
                }
                
                // Check for duplicates to report
                String key = entry.source + ":" + entry.message;
                DuplicateEntry dupEntry = recentMessages.get(key);
                if (dupEntry != null && dupEntry.count > 1) {
                    duplicateCounts.put(key, dupEntry.count);
                }
            }
            
            // Log duplicate counts
            for (Map.Entry<String, Integer> dupe : duplicateCounts.entrySet()) {
                String[] parts = dupe.getKey().split(":", 2);
                if (parts.length == 2) {
                    writer.println("[" + dateFormat.format(new Date()) + "] [INFO] [" + parts[0] + 
                            "] Last message repeated " + dupe.getValue() + " times");
                }
            }
            
            // Ensure data is written
            writer.flush();
            lastWriteTime.set(System.currentTimeMillis());
            
            // Update statistics
            flushCount.incrementAndGet();
            long flushTime = System.currentTimeMillis() - startTime;
            totalFlushTime.addAndGet(flushTime);
            
            if (flushedCount > 0 && logger != null && isEnabled()) {
                // Only log this in debug mode to avoid spam
                DebugLogger.debug("Logger", "Flushed " + flushedCount + " log entries in " + flushTime + "ms");
            }
        } catch (Exception e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
            
            // Attempt to log to console instead
            if (logger != null) {
                logger.severe("Failed to write to log file: " + e.getMessage());
            }
            
            // Close and reset the writer on error
            synchronized (LOCK) {
                if (cachedWriter != null) {
                    try {
                        cachedWriter.close();
                    } catch (Exception ignored) {
                        // Ignore
                    }
                    cachedWriter = null;
                }
            }
        }
    }
    
    /**
     * Get statistics about the logger.
     * 
     * @return a string containing statistics
     */
    public static String getStatistics() {
        long total = totalLogEntries.get();
        long skipped = skippedLogEntries.get();
        long duplicates = duplicateEntries.get();
        int bufferSize = logBuffer.size();
        int maxBuffer = maxBufferSize.get();
        long flushes = flushCount.get();
        long flushTimeTotal = totalFlushTime.get();
        double avgFlushTime = flushes > 0 ? (double) flushTimeTotal / flushes : 0;
        
        return String.format(
            "Log Statistics: %d total, %d skipped (%.1f%%), %d duplicates, %d in buffer (max: %d), %d flushes (%.2fms avg)",
            total,
            skipped,
            total > 0 ? (double) skipped / total * 100 : 0,
            duplicates,
            bufferSize,
            maxBuffer,
            flushes,
            avgFlushTime
        );
    }
    
    /**
     * Reset the statistics.
     */
    public static void resetStatistics() {
        totalLogEntries.set(0);
        skippedLogEntries.set(0);
        duplicateEntries.set(0);
        flushCount.set(0);
        totalFlushTime.set(0);
        maxBufferSize.set(logBuffer.size());
    }
    
    /**
     * Shutdown the logger, flushing any remaining entries.
     */
    public static void shutdown() {
        flushLogBuffer();
        
        synchronized (LOCK) {
            if (cachedWriter != null) {
                cachedWriter.close();
                cachedWriter = null;
            }
        }
        
        if (logExecutor != null && !logExecutor.isShutdown()) {
            logExecutor.shutdown();
            try {
                if (!logExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logExecutor.shutdownNow();
            }
        }
    }
    
    /**
     * Class representing a log entry.
     */
    private static class LogEntry {
        final long timestamp;
        final String source;
        final String message;
        final Throwable throwable;
        final LogLevel level;
        
        LogEntry(long timestamp, String source, String message, Throwable throwable, LogLevel level) {
            this.timestamp = timestamp;
            this.source = source;
            this.message = message;
            this.throwable = throwable;
            this.level = level;
        }
    }
    
    /**
     * Class for tracking duplicate log entries.
     */
    private static class DuplicateEntry {
        long timestamp;
        int count;
        
        DuplicateEntry(long timestamp) {
            this.timestamp = timestamp;
            this.count = 1;
        }
    }
} 