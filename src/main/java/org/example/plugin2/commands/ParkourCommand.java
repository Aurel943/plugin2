package org.example.plugin2.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.example.plugin2.Plugin2;
import org.example.plugin2.messages.MessagesManager;
import org.example.plugin2.parkour.ParkourManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Commande /parkour, réduite à "/parkour top" : l'entrée dans le parkour ne
 * passe plus par une commande (voir la remarque qui a mené à ce choix —
 * l'entrée se fait uniquement à pied via la zone "entree", ou via le bouton
 * du menu de téléportation, voir TeleportMenu). Le classement reste
 * accessible par commande car utile même hors du parkour.
 */
public class ParkourCommand implements CommandExecutor {

    private final Plugin2 plugin;
    private final MessagesManager messages;
    private final ParkourManager parkour;

    // Pour l'instant un seul parkour ; à adapter si "/parkour top <id>" devient
    // nécessaire le jour où plusieurs parkours existent.
    private static final String PARKOUR_PAR_DEFAUT = "parkour1";

    public ParkourCommand(Plugin2 plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
        this.parkour = plugin.getParkourManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("top")) {
            messages.send(sender, "parkour.usage");
            return true;
        }

        handleTop(sender);
        return true;
    }

    private void handleTop(CommandSender sender) {
        List<Map.Entry<UUID, Long>> classement = parkour.getTop(PARKOUR_PAR_DEFAUT, 10);

        if (classement.isEmpty()) {
            messages.send(sender, "parkour.top.vide");
            return;
        }

        messages.send(sender, "parkour.top.titre");

        int rang = 1;
        for (Map.Entry<UUID, Long> entry : classement) {
            OfflinePlayer joueur = Bukkit.getOfflinePlayer(entry.getKey());
            String nom = joueur.getName() != null ? joueur.getName() : "Inconnu";
            String tempsFormate = parkour.formaterTemps(entry.getValue());

            messages.send(sender, "parkour.top.ligne", Map.of(
                    "rang", String.valueOf(rang),
                    "joueur", nom,
                    "temps", tempsFormate
            ));
            rang++;
        }
    }
}