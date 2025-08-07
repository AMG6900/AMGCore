package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ServerInfoManager {
    private final AMGCore plugin;
    private final LocaleManager localeManager;
    private final MiniMessage miniMessage;
    private final File rulesFile;
    private final YamlConfiguration rulesConfig;
    private final ConcurrentMap<String, List<String>> rules;

    public ServerInfoManager(AMGCore plugin) {
        this.plugin = plugin;
        this.localeManager = plugin.getLocaleManager();
        this.miniMessage = MiniMessage.miniMessage();
        this.rulesFile = new File(plugin.getDataFolder(), "rules.yml");
        this.rules = new ConcurrentHashMap<>();

        if (!rulesFile.exists()) {
            plugin.saveResource("rules.yml", false);
        }
        this.rulesConfig = YamlConfiguration.loadConfiguration(rulesFile);

        loadRules();
    }

    public void loadRules() {
        rules.clear();
        List<String> rulesList = rulesConfig.getStringList("rules");
        rules.put("rules", rulesList);
    }

    public void saveRules() {
        try {
            rulesConfig.set("rules", rules.get("rules"));
            rulesConfig.save(rulesFile);
        } catch (IOException e) {
            DebugLogger.severe("ServerInfoManager", localeManager.getMessage("serverinfo.error.save_rules"), e);
        }
    }

    @NotNull
    public List<String> getRules() {
        return rules.getOrDefault("rules", Collections.emptyList());
    }

    @NotNull
    public Set<String> getRuleCategories() {
        return Collections.unmodifiableSet(rules.keySet());
    }

    public void setRules(@NotNull List<String> ruleList) {
        rules.put("rules", new ArrayList<>(ruleList));
        saveRules();
    }

    public void addRule(@NotNull String rule) {
        List<String> rulesList = rules.computeIfAbsent("rules", k -> new ArrayList<>());
        rulesList.add(rule);
        saveRules();
    }

    public void removeRule(int index) {
        List<String> rulesList = rules.get("rules");
        if (rulesList != null && index >= 0 && index < rulesList.size()) {
            rulesList.remove(index);
            saveRules();
        }
    }

    public Component getFormattedPlayerList() {
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();
        
        StringBuilder builder = new StringBuilder(localeManager.getMessage("serverinfo.playerlist.title", onlinePlayers, maxPlayers))
            .append("\n");

        // Group players by world more efficiently
        Map<World, List<Player>> playersByWorld = new HashMap<>();
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            playersByWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(player);
        }

        // Format player list by world
        AFKManager afkManager = (AFKManager) plugin.getManager("afk");
        
        for (Map.Entry<World, List<Player>> entry : playersByWorld.entrySet()) {
            World world = entry.getKey();
            List<Player> players = entry.getValue();
            
            builder.append(localeManager.getMessage("serverinfo.playerlist.world_header", world.getName(), players.size()))
                .append("\n");

            for (Player player : players) {
                boolean isAfk = afkManager != null && afkManager.isAFK(player);
                builder.append(localeManager.getMessage(
                    isAfk ? "serverinfo.playerlist.player_afk" : "serverinfo.playerlist.player",
                    player.getName()
                )).append("\n");
            }
        }

        return miniMessage.deserialize(builder.toString());
    }

    public Component getServerStats() {
        // Get runtime information
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        Duration uptime = Duration.ofMillis(runtime.getUptime());
        long days = uptime.toDays();
        long hours = uptime.toHoursPart();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();

        StringBuilder uptimeStr = new StringBuilder();
        if (days > 0) uptimeStr.append(days).append("d ");
        if (hours > 0) uptimeStr.append(hours).append("h ");
        if (minutes > 0) uptimeStr.append(minutes).append("m ");
        if (seconds > 0 || uptimeStr.isEmpty()) uptimeStr.append(seconds).append("s");

        // Get memory information
        Runtime jvm = Runtime.getRuntime();
        long totalMemory = jvm.totalMemory() / 1024 / 1024;
        long freeMemory = jvm.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = jvm.maxMemory() / 1024 / 1024;

        // Get TPS information
        double[] tps = Bukkit.getTPS();
        String tpsStr = String.format("%.1f, %.1f, %.1f", tps[0], tps[1], tps[2]);

        StringBuilder stats = new StringBuilder()
            .append(localeManager.getMessage("serverinfo.stats.title")).append("\n")
            .append(localeManager.getMessage("serverinfo.stats.uptime", uptimeStr)).append("\n")
            .append(localeManager.getMessage("serverinfo.stats.memory", usedMemory, totalMemory, maxMemory)).append("\n")
            .append(localeManager.getMessage("serverinfo.stats.tps", tpsStr)).append("\n")
            .append(localeManager.getMessage("serverinfo.stats.online_players", 
                Bukkit.getOnlinePlayers().size(), 
                Bukkit.getMaxPlayers())).append("\n");

        // Get world information
        stats.append(localeManager.getMessage("serverinfo.stats.worlds.header")).append("\n");
        for (World world : Bukkit.getWorlds()) {
            stats.append(localeManager.getMessage("serverinfo.stats.worlds.entry", world.getName())).append("\n");
            stats.append(localeManager.getMessage("serverinfo.stats.worlds.chunks", world.getLoadedChunks().length)).append("\n");
            stats.append(localeManager.getMessage("serverinfo.stats.worlds.entities", world.getEntities().size())).append("\n");
            stats.append(localeManager.getMessage("serverinfo.stats.worlds.living_entities", world.getLivingEntities().size())).append("\n");
        }

        return miniMessage.deserialize(stats.toString());
    }
} 