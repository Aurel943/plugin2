package org.example.plugin2.commands;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.example.plugin2.Plugin2;
import org.example.plugin2.messages.MessagesManager;
import org.example.plugin2.world.HubWorldManager;

import java.util.Map;

/**
 * Commande /hub — gère les sous-commandes administratives liées au hub.
 * Pour l'instant : "setspawn". Facile à étendre avec d'autres sous-commandes
 * (ex: "reload" pour recharger hub-rules.yml à chaud).
 */
public class HubCommand implements CommandExecutor {

    private final Plugin2 plugin;
    private final HubWorldManager hubWorldManager;
    private final MessagesManager messages;

    public HubCommand(Plugin2 plugin) {
        this.plugin = plugin;
        this.hubWorldManager = plugin.getHubWorldManager();
        this.messages = plugin.getMessagesManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "hub.usage");
            return true;
        }

        String sousCommande = args[0].toLowerCase();

        switch (sousCommande) {
            case "setspawn" -> handleSetSpawn(sender);
            case "reload" -> handleReload(sender);
            default -> messages.send(sender, "hub.sous-commande-inconnue");
        }

        return true;
    }

    private void handleSetSpawn(CommandSender sender) {
        if (!sender.hasPermission("plugin2.admin")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "hub.setspawn.joueur-requis");
            return;
        }
        if (!hubWorldManager.isHubWorld(player.getWorld())) {
            messages.send(sender, "hub.setspawn.pas-dans-le-hub");
            return;
        }

        Location loc = player.getLocation();
        hubWorldManager.setSpawn(loc);

        messages.send(sender, "hub.setspawn.confirme", Map.of(
                "x", String.format("%.1f", loc.getX()),
                "y", String.format("%.1f", loc.getY()),
                "z", String.format("%.1f", loc.getZ())
        ));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("plugin2.admin")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return;
        }
        hubWorldManager.loadConfig();
        messages.send(sender, "hub.reload.confirme");
    }
}