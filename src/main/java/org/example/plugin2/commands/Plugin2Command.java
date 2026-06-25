package org.example.plugin2.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.example.plugin2.Plugin2;
import org.example.plugin2.messages.MessagesManager;

public class Plugin2Command implements CommandExecutor {

    private final Plugin2 plugin;
    private final MessagesManager messages;

    public Plugin2Command(Plugin2 plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("plugin2.admin")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return true;
        }

        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            messages.send(sender, "plugin2.usage");
            return true;
        }

        plugin.reloadAllCaches();
        messages.send(sender, "plugin2.reload.confirme");
        return true;
    }
}