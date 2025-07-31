package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.ChatManager;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.utils.DebugLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class ChatCommands implements CommandExecutor, TabCompleter {
    private final ChatManager chatManager;
    private final LocaleManager localeManager;
    private final Map<UUID, UUID> lastMessageSender;
    public ChatCommands(AMGCore plugin) {
        this.localeManager = plugin.getLocaleManager();
        this.lastMessageSender = new HashMap<>();

        // Enable chat module if needed
        if (!plugin.isModuleEnabled("chat")) {
            plugin.getModuleRegistry().enableModule("chat");
        }
        this.chatManager = (ChatManager) plugin.getManager("chat");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        try {
            return switch (command.getName().toLowerCase()) {
                case "msg" -> handleMessage(sender, args);
                case "r", "reply" -> handleReply(sender, args);
                case "mute" -> handleMute(sender, args);
                case "unmute" -> handleUnmute(sender, args);
                case "broadcast", "bc" -> handleBroadcast(sender, args);
                default -> false;
            };
        } catch (Exception e) {
            DebugLogger.severe("Error executing chat command", "ChatCommands", e);
            sender.sendMessage(localeManager.getComponent("general.error"));
            return true;
        }
    }

    private boolean handleMessage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("general.player_only"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(localeManager.getComponent("chat.msg.usage"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(localeManager.getComponent("chat.msg.player_not_found", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            sender.sendMessage(localeManager.getComponent("chat.msg.self"));
            return true;
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Component formattedMessage = localeManager.getComponent("chat.format.outgoing", target.getName(), message);
        Component targetMessage = localeManager.getComponent("chat.format.incoming", player.getName(), message);

        player.sendMessage(formattedMessage);
        target.sendMessage(targetMessage);
        lastMessageSender.put(target.getUniqueId(), player.getUniqueId());

        return true;
    }

    private boolean handleReply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(localeManager.getComponent("general.player_only"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(localeManager.getComponent("chat.reply.usage"));
            return true;
        }

        UUID lastSenderUuid = lastMessageSender.get(player.getUniqueId());
        if (lastSenderUuid == null) {
            sender.sendMessage(localeManager.getComponent("chat.reply.no_recipient"));
            return true;
        }

        Player target = Bukkit.getPlayer(lastSenderUuid);
        if (target == null) {
            sender.sendMessage(localeManager.getComponent("chat.reply.recipient_offline"));
            return true;
        }

        String message = String.join(" ", args);
        Component formattedMessage = localeManager.getComponent("chat.format.outgoing", target.getName(), message);
        Component targetMessage = localeManager.getComponent("chat.format.incoming", player.getName(), message);

        player.sendMessage(formattedMessage);
        target.sendMessage(targetMessage);
        lastMessageSender.put(target.getUniqueId(), player.getUniqueId());

        return true;
    }

    private boolean handleMute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.mute")) {
            sender.sendMessage(localeManager.getComponent("chat.mute.no_permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(localeManager.getComponent("chat.mute.usage"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(localeManager.getComponent("chat.msg.player_not_found", args[0]));
            return true;
        }

        try {
            Duration duration = parseDuration(args[1]);
            String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "No reason provided";
            UUID muterUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : new UUID(0, 0);
            
            if (chatManager.mutePlayer(target.getUniqueId(), muterUuid, reason, duration)) {
                sender.sendMessage(localeManager.getComponent("chat.mute.success", target.getName(), formatDuration(duration), reason));
                target.sendMessage(localeManager.getComponent("chat.mute.target_muted", formatDuration(duration), 
                    sender instanceof Player ? ((Player) sender).getName() : "Console", reason));
            } else {
                sender.sendMessage(localeManager.getComponent("chat.mute.already_muted", target.getName()));
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text(e.getMessage()));
        }

        return true;
    }

    private boolean handleUnmute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.unmute")) {
            sender.sendMessage(localeManager.getComponent("chat.mute.no_permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(localeManager.getComponent("chat.mute.usage"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(localeManager.getComponent("chat.msg.player_not_found", args[0]));
            return true;
        }

        if (chatManager.unmutePlayer(target.getUniqueId())) {
            sender.sendMessage(localeManager.getComponent("chat.mute.unmute_success", target.getName()));
            target.sendMessage(localeManager.getComponent("chat.mute.target_unmuted", 
                sender instanceof Player ? ((Player) sender).getName() : "Console"));
        } else {
            sender.sendMessage(localeManager.getComponent("chat.mute.not_muted", target.getName()));
        }

        return true;
    }

    private boolean handleBroadcast(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.broadcast")) {
            sender.sendMessage(localeManager.getComponent("chat.broadcast.no_permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(localeManager.getComponent("chat.broadcast.usage"));
            return true;
        }

        String message = String.join(" ", args);
        String senderName = sender instanceof Player ? ((Player) sender).getName() : "Console";
        Component broadcastMessage = localeManager.getComponent("chat.broadcast.format", senderName, message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(broadcastMessage);
        }
        // Also send to console
        Bukkit.getConsoleSender().sendMessage(broadcastMessage);
        return true;
    }

    private Duration parseDuration(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException(localeManager.getMessage("chat.mute.invalid_duration"));
        }

        String number = input.replaceAll("[^0-9]", "");
        String unit = input.replaceAll("[0-9]", "").toLowerCase();

        if (number.isEmpty() || unit.isEmpty()) {
            throw new IllegalArgumentException(localeManager.getMessage("chat.mute.invalid_duration"));
        }

        long value = Long.parseLong(number);
        return switch (unit) {
            case "s" -> Duration.ofSeconds(value);
            case "m" -> Duration.ofMinutes(value);
            case "h" -> Duration.ofHours(value);
            case "d" -> Duration.ofDays(value);
            default -> throw new IllegalArgumentException(localeManager.getMessage("chat.mute.invalid_time_unit"));
        };
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m";
        } else if (seconds < 86400) {
            return (seconds / 3600) + "h";
        } else {
            return (seconds / 86400) + "d";
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        switch (command.getName().toLowerCase()) {
            case "msg" -> {
                if (args.length == 1) {
                    completions.addAll(Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                            .collect(Collectors.toList()));
                }
            }
            case "mute" -> {
                if (args.length == 1) {
                    completions.addAll(Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                            .collect(Collectors.toList()));
                } else if (args.length == 2) {
                    completions.addAll(Arrays.asList("30s", "5m", "1h", "1d", "7d", "30d"));
                }
            }
            case "unmute" -> {
                if (args.length == 1) {
                    completions.addAll(Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                            .collect(Collectors.toList()));
                }
            }
            case "broadcast", "bc" -> {
                if (args.length == 1) {
                    completions.addAll(Arrays.asList(
                        "Welcome to the server!",
                        "Server restarting in 5 minutes",
                        "Thanks for playing!"
                    ));
                }
            }
        }

        return completions;
    }
} 