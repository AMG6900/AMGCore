package amg.plugins.aMGCore.managers;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.utils.DebugLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class LocaleManager {
    private final AMGCore plugin;
    private final MiniMessage miniMessage;
    private final Map<String, YamlConfiguration> localeConfigs;
    private final Map<UUID, String> playerLocales;
    private String defaultLocale;
    private boolean useClientLocale;
    private String fallbackLocale;
    private static LocaleManager instance;

    public LocaleManager(AMGCore plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.localeConfigs = new HashMap<>();
        this.playerLocales = new HashMap<>();
        loadConfig();
        loadLocales();
        instance = this;
    }

    public static LocaleManager getInstance() {
        return instance;
    }

    private void loadConfig() {
        ConfigurationSection localeSection = plugin.getConfig().getConfigurationSection("locale");
        if (localeSection != null) {
            this.defaultLocale = localeSection.getString("default", "en_US");
            this.useClientLocale = localeSection.getBoolean("use_client_locale", true);
            this.fallbackLocale = localeSection.getString("fallback_locale", "en_US");
        } else {
            this.defaultLocale = "en_US";
            this.useClientLocale = true;
            this.fallbackLocale = "en_US";
        }
    }

    private void saveDefaultLocale(String fileName) {
        File localeFile = new File(plugin.getDataFolder() + "/locales", fileName);
        if (!localeFile.exists()) {
            try {
                plugin.saveResource("locales/" + fileName, false);
            } catch (IllegalArgumentException e) {
                DebugLogger.warning("LocaleManager", "Failed to save locale file: " + fileName + " - " + e.getMessage());
            }
        }
    }

    private void loadLocales() {
        // Ensure locale directory exists
        File localeDir = new File(plugin.getDataFolder(), "locales");
        if (!localeDir.exists()) {
            localeDir.mkdirs();
        }

        // Save default locale files if they don't exist
        String[] defaultLocales = {"en_US.yml", "bg_BG.yml"};
        for (String locale : defaultLocales) {
            saveDefaultLocale(locale);
        }

        // Load all locale files from the directory
        File[] localeFiles = localeDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (localeFiles != null) {
            for (File file : localeFiles) {
                String localeName = file.getName().replace(".yml", "");
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                localeConfigs.put(localeName, config);
                DebugLogger.info("LocaleManager", "Loaded locale file: " + file.getName());

                // Only validate messages in debug mode
                if (plugin.isDebugEnabled()) {
                    validateMessages("", config);
                }
            }
        }

        // Ensure at least one locale is loaded
        if (localeConfigs.isEmpty()) {
            DebugLogger.severe("LocaleManager", "No locale files were loaded! Using fallback messages.");
        }
    }

    public void setPlayerLocale(Player player, String locale) {
        if (localeConfigs.containsKey(locale)) {
            playerLocales.put(player.getUniqueId(), locale);
        }
    }

    public String getPlayerLocale(Player player) {
        if (useClientLocale) {
            Locale clientLocale = player.locale();
            String localeStr = clientLocale.toString().replace('-', '_');
            if (localeConfigs.containsKey(localeStr)) {
                return localeStr;
            }
        }
        return playerLocales.getOrDefault(player.getUniqueId(), defaultLocale);
    }

    public void reloadLocale() {
        loadConfig();
        loadLocales();
    }

    public Component getComponent(String path, Object... args) {
        return getComponent(null, path, args);
    }

    public Component getComponent(Player player, String path, Object... args) {
        YamlConfiguration config = getConfigForPlayer(player);

        // Split path into sections and traverse the config
        String[] sections = path.split("\\.");
        ConfigurationSection currentSection = config;
        
        for (int i = 0; i < sections.length - 1; i++) {
            currentSection = currentSection.getConfigurationSection(sections[i]);
            if (currentSection == null) {
                DebugLogger.warning("LocaleManager", "Missing section: " + path);
                String message = "<red>Missing message: " + path + "</red>";
                return miniMessage.deserialize(message);
            }
        }
        
        String message = currentSection.getString(sections[sections.length - 1]);
        
        // If message not found, return a default message
        if (message == null) {
            DebugLogger.warning("LocaleManager", "Missing message: " + path);
            message = "<red>Missing message: " + path + "</red>";
            return miniMessage.deserialize(message);
        }

        try {
            String formatted = MessageFormat.format(message, args);
            return miniMessage.deserialize(formatted);
        } catch (Exception e) {
            DebugLogger.warning("LocaleManager", "Error formatting message: " + path);
            return Component.text("Error formatting: " + path).color(NamedTextColor.RED);
        }
    }

    public String getMessage(String path, Object... args) {
        return getMessage(null, path, args);
    }

    public String getMessage(Player player, String path, Object... args) {
        YamlConfiguration config = getConfigForPlayer(player);

        // Split path into sections and traverse the config
        String[] sections = path.split("\\.");
        ConfigurationSection currentSection = config;
        
        for (int i = 0; i < sections.length - 1; i++) {
            currentSection = currentSection.getConfigurationSection(sections[i]);
            if (currentSection == null) {
                DebugLogger.warning("LocaleManager", "Missing section: " + path);
                return "Missing message: " + path;
            }
        }
        
        String message = currentSection.getString(sections[sections.length - 1]);
        
        // If message not found, return a default message
        if (message == null) {
            DebugLogger.warning("LocaleManager", "Missing message: " + path);
            return "Missing message: " + path;
        }

        try {
            return MessageFormat.format(message, args);
        } catch (Exception e) {
            DebugLogger.warning("LocaleManager", "Error formatting message: " + path);
            return "Error formatting: " + path;
        }
    }

    private YamlConfiguration getConfigForPlayer(Player player) {
        if (player != null) {
            String locale = getPlayerLocale(player);
            YamlConfiguration config = localeConfigs.get(locale);
            if (config != null) {
                return config;
            }
            
            // Try fallback locale
            config = localeConfigs.get(fallbackLocale);
            if (config != null) {
                return config;
            }
        }
        
        // Use default locale as last resort
        return localeConfigs.getOrDefault(defaultLocale, localeConfigs.get("en_US"));
    }

    private void validateMessages(String prefix, ConfigurationSection section) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                // This is a section, recurse into it
                validateMessages(path, section.getConfigurationSection(key));
            } else {
                // This is a message
                String message = section.getString(key);
                if (message != null) {
                    try {
                        // Try to format the message to check if it's valid
                        String formatted = MessageFormat.format(message, (Object[]) null);
                        miniMessage.deserialize(formatted);
                        if (plugin.isDebugEnabled()) {
                            DebugLogger.info("LocaleManager", "✓ " + path + ": " + message);
                        }
                    } catch (Exception e) {
                        // Log messages that have format errors
                        DebugLogger.warning("LocaleManager", "✗ " + path + ": " + message + " (Format error: " + e.getMessage() + ")");
                    }
                }
            }
        }
    }
} 