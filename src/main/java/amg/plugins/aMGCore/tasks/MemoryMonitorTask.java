package amg.plugins.aMGCore.tasks;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Objects;

/**
 * Task that monitors memory usage and triggers optimization when needed.
 */
public class MemoryMonitorTask extends BukkitRunnable {
    private final AMGCore plugin;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private long lastReportTime = 0;
    private static final long REPORT_INTERVAL = 5 * 60 * 1000; // 5 minutes

    public MemoryMonitorTask(@NotNull AMGCore plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
    }

    @Override
    public void run() {
        try {
            // Get current memory usage
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            long used = heapUsage.getUsed();
            long max = heapUsage.getMax();
            
            // Calculate memory usage percentage
            double usagePercent = (double) used / max;
            
            // Check if we should log memory usage (every 5 minutes)
            long now = System.currentTimeMillis();
            if (now - lastReportTime > REPORT_INTERVAL) {
                DebugLogger.debug("Memory", String.format(
                    "Memory usage: %.1f%% (%.1f MB / %.1f MB)",
                    usagePercent * 100,
                    used / 1024.0 / 1024.0,
                    max / 1024.0 / 1024.0
                ));
                lastReportTime = now;
            }
            
            // Check if optimization is needed
            plugin.checkAndOptimizeMemory();
        } catch (Exception e) {
            DebugLogger.severe("Memory", "Error during memory monitoring", e);
        }
    }
} 