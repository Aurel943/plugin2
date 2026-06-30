package fr.aurel943.hub.commands;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import fr.aurel943.hub.Hub;
import fr.aurel943.hub.messages.MessagesManager;
import fr.aurel943.hub.world.HubWorldManager;

import java.util.Map;

/**
 * Commande /hub — gère les sous-commandes administratives liées au hub.
 * Pour l'instant : "setspawn". Facile à étendre avec d'autres sous-commandes
 * (ex: "reload" pour recharger hub-rules.yml à chaud).
 */
public class HubCommand implements CommandExecutor {

    private final Hub plugin;
    private final HubWorldManager hubWorldManager;
    private final MessagesManager messages;

    public HubCommand(Hub plugin) {
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
        if (!sender.hasPermission("hub.setspawn")) {
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

    /**
     * Reload global du plugin : recharge hub-rules.yml ET tous les caches
     * mémoire (économie, upgrades, ranks, parkour, scoreboard, messages,
     * pets, cosmétiques, boss bar, MOTD) via Hub.reloadAllCaches().
     *
     * Avant la fusion des commandes, ce reload était scindé entre /hub reload
     * (limité à hub-rules.yml) et /plugin2 reload (tout le reste, ancien nom
     * du plugin) — source de
     * confusion pour l'admin. Désormais /hub reload fait tout en une fois.
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("hub.reload")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return;
        }
        hubWorldManager.loadConfig();
        plugin.reloadAllCaches();
        messages.send(sender, "hub.reload.confirme");
    }
}