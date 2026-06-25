package org.example.plugin2.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.example.plugin2.Plugin2;
import org.example.plugin2.messages.MessagesManager;

import java.util.Map;

public class UuidCommand implements CommandExecutor {

    private final MessagesManager messages;

    public UuidCommand(Plugin2 plugin) {
        this.messages = plugin.getMessagesManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("plugin2.admin")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return true;
        }

        if (args.length != 1) {
            messages.send(sender, "uuid.usage");
            return true;
        }

        String pseudo = args[0];
        OfflinePlayer cible = Bukkit.getOfflinePlayer(pseudo);

        // hasPlayedBefore() évite de renvoyer un UUID "offline-mode" bidon
        // pour un pseudo qui n'a jamais existé sur le serveur
        if (!cible.hasPlayedBefore() && !cible.isOnline()) {
            messages.send(sender, "uuid.joueur-inconnu", Map.of("pseudo", pseudo));
            return true;
        }

        messages.send(sender, "uuid.resultat", Map.of(
                "pseudo", cible.getName() != null ? cible.getName() : pseudo,
                "uuid", cible.getUniqueId().toString()
        ));
        return true;
    }
}