package amg.plugins.aMGCore.modules;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.api.BaseModule;
import amg.plugins.aMGCore.managers.ChatManager;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.utils.DebugLogger;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Module for managing chat functionality.
 */
public class ChatModule extends BaseModule implements Listener {
    private ChatManager chatManager;

    /**
     * Creates a new ChatModule.
     * 
     * @param plugin The plugin instance
     */
    public ChatModule(AMGCore plugin) {
        super("chat", plugin, new String[]{"database", "playerdata"}, 50);
    }

    @Override
    protected void onEnable() throws Exception {
        DebugLogger.debug("ChatModule", "Initializing chat manager");
        chatManager = new ChatManager(plugin, plugin.getDatabaseManager());
        plugin.registerManager("chat", chatManager);
        
        // Create default channels
        createDefaultChannels();
        
        // Register chat prefix handler
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    protected void onDisable() throws Exception {
        DebugLogger.debug("ChatModule", "Shutting down chat manager");
        chatManager = null;
    }
    
    /**
     * Creates default chat channels if they don't exist.
     */
    private void createDefaultChannels() {
        LocaleManager localeManager = (LocaleManager) plugin.getManager("locale");
        
        if (localeManager != null) {
            // Staff channel
            chatManager.createChannel("staff", localeManager.getMessage("chat.channel.formats.staff"), "amgcore.channel.staff");
            
            // Admin channel
            chatManager.createChannel("admin", localeManager.getMessage("chat.channel.formats.admin"), "amgcore.channel.admin");
            
            // Local channel (shorter range)
            chatManager.createChannel("local", localeManager.getMessage("chat.channel.formats.local"), null);
            
            // Trade channel
            chatManager.createChannel("trade", localeManager.getMessage("chat.channel.formats.trade"), null);
            
            // Help channel
            chatManager.createChannel("help", localeManager.getMessage("chat.channel.formats.help"), null);
        } else {
            // Fallback to hardcoded formats if locale manager is not available
            // Staff channel
            chatManager.createChannel("staff", "<gray>[<color:#ff5555>Staff</color>]</gray> {message}", "amgcore.channel.staff");
            
            // Admin channel
            chatManager.createChannel("admin", "<gray>[<color:#aa0000>Admin</color>]</gray> {message}", "amgcore.channel.admin");
            
            // Local channel (shorter range)
            chatManager.createChannel("local", "<gray>[<color:#55ffff>Local</color>]</gray> {message}", null);
            
            // Trade channel
            chatManager.createChannel("trade", "<gray>[<color:#ffaa00>Trade</color>]</gray> {message}", null);
            
            // Help channel
            chatManager.createChannel("help", "<gray>[<color:#55ff55>Help</color>]</gray> {message}", null);
        }
    }
    
    /**
     * Add channel prefixes to chat messages.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
        if (event.isCancelled()) return;
        
        Player player = event.getPlayer();
        
        // Get the player's channel
        String channelName = chatManager.getPlayerChannel(player.getUniqueId());
        if (channelName == null) {
            channelName = "global";
        }
        
        // Get the channel format from ChatManager
        ChatManager.ChatChannel channel = chatManager.getChannels().get(channelName);
        if (channel == null) {
            channel = chatManager.getChannels().get("global");
        }
        
        // Get the format from the channel
        String format = channel.getFormat();
        
        // Create a MiniMessage instance to parse the format
        MiniMessage miniMessage = MiniMessage.miniMessage();
        
        // Parse the format into a component
        Component prefix = miniMessage.deserialize(format.replace("{message}", ""));
        
        // Modify the message by adding the prefix
        event.renderer((source, sourceDisplayName, message, viewer) -> 
            prefix.append(message));
    }
    
    /**
     * Gets the chat manager instance.
     * 
     * @return The chat manager
     */
    public ChatManager getChatManager() {
        return chatManager;
    }
} 