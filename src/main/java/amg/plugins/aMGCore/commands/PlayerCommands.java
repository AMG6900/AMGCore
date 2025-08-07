package amg.plugins.aMGCore.commands;

import amg.plugins.aMGCore.AMGCore;
import amg.plugins.aMGCore.managers.PlayerManager;
import amg.plugins.aMGCore.managers.LocaleManager;
import amg.plugins.aMGCore.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class PlayerCommands implements CommandExecutor, TabCompleter {
    private final PlayerManager playerManager;
    private final LocaleManager localeManager;

    public PlayerCommands(AMGCore plugin) {
        // Make sure the player module is enabled
        if (!plugin.isModuleEnabled("player")) {
            plugin.getLogger().info("PlayerCommands: Enabling player module");
            plugin.getModuleRegistry().enableModule("player");
        }
        
        try {
            this.playerManager = (PlayerManager) plugin.getManager("player");
            if (this.playerManager == null) {
                plugin.getLogger().severe("PlayerCommands: Failed to get PlayerManager - it's null!");
            } else {
                plugin.getLogger().info("PlayerCommands: Successfully got PlayerManager");
            }
            
            this.localeManager = plugin.getLocaleManager();
        } catch (Exception e) {
            plugin.getLogger().severe("PlayerCommands: Error getting PlayerManager: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        try {
            // Check if playerManager is null
            if (playerManager == null) {
                sender.sendMessage(localeManager.getComponent("player.error.manager_unavailable"));
                return true;
            }
            
            return switch (command.getName().toLowerCase()) {
                case "vanish", "v" -> handleVanish(sender, args);
                case "god" -> handleGod(sender, args);
                case "fly" -> handleFly(sender, args);
                case "speed" -> handleSpeed(sender, args);
                case "heal" -> handleHeal(sender, args);
                case "feed" -> handleFeed(sender, args);
                default -> false;
            };
        } catch (Exception e) {
            DebugLogger.severe("Error executing player command", "PlayerCommands", e);
            sender.sendMessage(localeManager.getComponent("player.error.command_error"));
            return true;
        }
    }

    private boolean handleVanish(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.vanish")) {
            sender.sendMessage(localeManager.getComponent("player.error.no_permission"));
            return true;
        }

        Player target;
        if (args.length > 0 && sender.hasPermission("amgcore.command.vanish.others")) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(localeManager.getComponent("player.error.player_not_found", args[0]));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(localeManager.getComponent("player.error.console_specify_player"));
            return true;
        }

        boolean isVanished = playerManager.toggleVanish(target);
        if (sender != target) {
            sender.sendMessage(localeManager.getComponent("player.vanish.other", 
                target.getName(), 
                isVanished ? "enabled" : "disabled"));
        } else {
            sender.sendMessage(localeManager.getComponent("player.vanish.self",
                isVanished ? "enabled" : "disabled"));
        }

        return true;
    }

    private boolean handleGod(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.god")) {
            sender.sendMessage(localeManager.getComponent("player.error.no_permission"));
            return true;
        }

        Player target;
        if (args.length > 0 && sender.hasPermission("amgcore.command.god.others")) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(localeManager.getComponent("player.error.player_not_found"));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(localeManager.getComponent("player.error.console_specify_player"));
            return true;
        }

        boolean isGodMode = playerManager.toggleGodMode(target);
        if (sender != target) {
            sender.sendMessage(localeManager.getComponent("player.god.other", 
                target.getName(),
                isGodMode ? "enabled" : "disabled"));
        } else {
            sender.sendMessage(localeManager.getComponent("player.god.self",
                isGodMode ? "enabled" : "disabled"));
        }

        return true;
    }

    private boolean handleFly(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.fly")) {
            sender.sendMessage(localeManager.getComponent("player.error.no_permission"));
            return true;
        }

        Player target;
        boolean enable = true;

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("off")) {
                if (sender instanceof Player) {
                    target = (Player) sender;
                    enable = false;
                } else {
                    sender.sendMessage(localeManager.getComponent("player.error.console_specify_player"));
                    return true;
                }
            } else if (sender.hasPermission("amgcore.command.fly.others")) {
                target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage(localeManager.getComponent("player.error.player_not_found"));
                    return true;
                }
                if (args.length > 1) {
                    enable = !args[1].equalsIgnoreCase("off");
                }
            } else {
                sender.sendMessage(localeManager.getComponent("player.error.no_permission_others"));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
            enable = !target.getAllowFlight();
        } else {
            sender.sendMessage(localeManager.getComponent("player.error.console_specify_player"));
            return true;
        }

        playerManager.setFly(target, enable);
        if (sender != target) {
            sender.sendMessage(localeManager.getComponent("player.fly.other",
                target.getName(),
                enable ? "enabled" : "disabled"));
        } else {
            sender.sendMessage(localeManager.getComponent("player.fly.self",
                enable ? "enabled" : "disabled"));
        }

        return true;
    }

    private boolean handleSpeed(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.speed")) {
            sender.sendMessage(localeManager.getComponent("player.error.no_permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(localeManager.getComponent("player.speed.usage"));
            return true;
        }

        try {
            float speed = Float.parseFloat(args[0]);
            if (speed < 0.0f || speed > 10.0f) {
                sender.sendMessage(localeManager.getComponent("player.speed.error.invalid_range"));
                return true;
            }
            speed = speed / 10.0f; // Convert to Minecraft's 0-1 scale

            boolean flying = args.length > 1 && args[1].equalsIgnoreCase("fly");
            Player target;

            if (args.length > 2 && sender.hasPermission("amgcore.command.speed.others")) {
                target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(localeManager.getComponent("player.error.player_not_found"));
                    return true;
                }
            } else if (sender instanceof Player) {
                target = (Player) sender;
                if (args.length == 1) {
                    flying = target.isFlying();
                }
            } else {
                sender.sendMessage(localeManager.getComponent("player.error.console_specify_player"));
                return true;
            }

            playerManager.setSpeed(target, speed, flying);
            String speedType = flying ? "flight" : "walk";
            if (sender != target) {
                sender.sendMessage(localeManager.getComponent("player.speed.other",
                    target.getName(),
                    speedType,
                    args[0]));
            } else {
                sender.sendMessage(localeManager.getComponent("player.speed.self",
                    speedType,
                    args[0]));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(localeManager.getComponent("player.speed.error.invalid_number"));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(localeManager.getComponent("player.error.command_error"));
        }

        return true;
    }

    private boolean handleHeal(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.heal")) {
            sender.sendMessage(localeManager.getComponent("player.error.no_permission"));
            return true;
        }

        Player target;
        if (args.length > 0 && sender.hasPermission("amgcore.command.heal.others")) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(localeManager.getComponent("player.error.player_not_found"));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(localeManager.getComponent("player.error.console_specify_player"));
            return true;
        }

        playerManager.heal(target);
        if (sender != target) {
            sender.sendMessage(localeManager.getComponent("player.heal.other", target.getName()));
        } else {
            sender.sendMessage(localeManager.getComponent("player.heal.self"));
        }

        return true;
    }

    private boolean handleFeed(CommandSender sender, String[] args) {
        if (!sender.hasPermission("amgcore.command.feed")) {
            sender.sendMessage(localeManager.getComponent("player.error.no_permission"));
            return true;
        }

        Player target;
        if (args.length > 0 && sender.hasPermission("amgcore.command.feed.others")) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(localeManager.getComponent("player.error.player_not_found"));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(localeManager.getComponent("player.error.console_specify_player"));
            return true;
        }

        playerManager.feed(target);
        if (sender != target) {
            sender.sendMessage(localeManager.getComponent("player.feed.other", target.getName()));
        } else {
            sender.sendMessage(localeManager.getComponent("player.feed.self"));
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        switch (command.getName().toLowerCase()) {
            case "vanish", "v", "god", "heal", "feed" -> {
                if (args.length == 1 && sender.hasPermission(command.getPermission() + ".others")) {
                    completions.addAll(Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                            .collect(Collectors.toList()));
                }
            }
            case "fly" -> {
                if (args.length == 1) {
                    if (sender.hasPermission("amgcore.command.fly.others")) {
                        completions.addAll(Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                                .collect(Collectors.toList()));
                    }
                    completions.add("off");
                } else if (args.length == 2 && sender.hasPermission("amgcore.command.fly.others")) {
                    completions.add("off");
                }
            }
            case "speed" -> {
                if (args.length == 1) {
                    completions.addAll(Arrays.asList("1", "2", "3", "4", "5", "10"));
                } else if (args.length == 2) {
                    completions.addAll(Arrays.asList("walk", "fly"));
                } else if (args.length == 3 && sender.hasPermission("amgcore.command.speed.others")) {
                    completions.addAll(Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList()));
                }
            }
        }

        return completions;
    }
} 