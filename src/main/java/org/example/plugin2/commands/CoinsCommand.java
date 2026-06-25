package org.example.plugin2.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.EconomyManager;
import org.example.plugin2.messages.MessagesManager;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class CoinsCommand implements CommandExecutor {

    private final Plugin2 plugin;
    private final EconomyManager economy;
    private final MessagesManager messages;

    public CoinsCommand(Plugin2 plugin) {
        this.plugin = plugin;
        this.economy = plugin.getEconomyManager();
        this.messages = plugin.getMessagesManager();
    }

    // Formate joliment "1 cristal" ou "5 cristaux" selon la valeur
    private String formatCristaux(double amount) {
        String nombre = String.format("%.2f", amount);
        return nombre + (amount == 1.0 ? " tal" : " cristaux");
    }

    // Couleur du podium pour baltop (1er = or, 2ème = gris/argent, 3ème = bronze)
    private String podiumTag(int rang) {
        return switch (rang) {
            case 1 -> "&6";
            case 2 -> "&7";
            case 3 -> "&6"; // les codes & n'ont pas d'équivalent bronze ; on reste sur l'or/gris/blanc
            default -> "&f";
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // CAS 1 : /coins seul → affiche son propre solde
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "coins.joueur-requis");
                return true;
            }
            double balance = economy.getBalance(player.getUniqueId());
            messages.send(player, "coins.solde-personnel", Map.of("montant", formatCristaux(balance)));
            return true;
        }

        String sousCommande = args[0].toLowerCase();

        // CAS 2 : /coins <joueur> → un admin regarde le solde de quelqu'un d'autre
        if (!isKnownSubCommand(sousCommande)) {
            if (!sender.hasPermission("plugin2.admin")) {
                messages.send(sender, "coins.permission-refusee-voir-solde");
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(sousCommande);
            double balance = economy.getBalance(target.getUniqueId());
            messages.send(sender, "coins.solde-autre", Map.of(
                    "joueur", sousCommande,
                    "montant", formatCristaux(balance)
            ));
            return true;
        }

        switch (sousCommande) {
            case "pay" -> handlePay(sender, args);
            case "give" -> handleAdminOperation(sender, args, "give");
            case "take" -> handleAdminOperation(sender, args, "take");
            case "set" -> handleAdminOperation(sender, args, "set");
            case "baltop" -> handleBaltop(sender);
            case "reset" -> handleReset(sender);
            case "help" -> handleHelp(sender);
            default -> messages.send(sender, "coins.sous-commande-inconnue");
        }

        return true;
    }

    private boolean isKnownSubCommand(String s) {
        return s.equals("pay") || s.equals("give") || s.equals("take")
                || s.equals("set") || s.equals("baltop") || s.equals("reset") || s.equals("help");
    }

    // /coins pay <joueur> <montant>
    private void handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "coins.pay.seul-joueur");
            return;
        }
        if (args.length < 3) {
            messages.send(sender, "coins.pay.usage");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            messages.send(sender, "coins.pay.cible-hors-ligne");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            messages.send(sender, "coins.pay.auto-paiement");
            return;
        }

        double montant = parseMontant(sender, args[2]);
        if (Double.isNaN(montant)) return;

        boolean success = economy.removeBalance(player.getUniqueId(), montant);
        if (!success) {
            messages.send(sender, "coins.pay.solde-insuffisant");
            return;
        }

        economy.addBalance(target.getUniqueId(), montant);
        messages.send(player, "coins.pay.envoye", Map.of(
                "montant", formatCristaux(montant),
                "joueur", target.getName()
        ));
        messages.send(target, "coins.pay.recu", Map.of(
                "joueur", player.getName(),
                "montant", formatCristaux(montant)
        ));
    }

    // /coins give|take|set <joueur> <montant>  (admin uniquement)
    private void handleAdminOperation(CommandSender sender, String[] args, String action) {
        if (!sender.hasPermission("plugin2.admin")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return;
        }
        if (args.length < 3) {
            messages.send(sender, "coins.admin.usage", Map.of("action", action));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        double montant = parseMontant(sender, args[2]);
        if (Double.isNaN(montant)) return;

        switch (action) {
            case "give" -> {
                economy.addBalance(target.getUniqueId(), montant);
                messages.send(sender, "coins.admin.ajoute", Map.of(
                        "montant", formatCristaux(montant),
                        "joueur", args[1]
                ));
            }
            case "take" -> {
                boolean success = economy.removeBalance(target.getUniqueId(), montant);
                if (success) {
                    messages.send(sender, "coins.admin.retire", Map.of(
                            "montant", formatCristaux(montant),
                            "joueur", args[1]
                    ));
                } else {
                    messages.send(sender, "coins.admin.retire-echec", Map.of("joueur", args[1]));
                }
            }
            case "set" -> {
                economy.setBalance(target.getUniqueId(), montant);
                messages.send(sender, "coins.admin.defini", Map.of(
                        "joueur", args[1],
                        "montant", formatCristaux(montant)
                ));
            }
        }
    }

    // /coins baltop → classement des 10 joueurs les plus riches ayant déjà joué
    private void handleBaltop(CommandSender sender) {
        Map<UUID, Double> tousLesSoldes = plugin.getDatabaseAllBalances();

        messages.send(sender, "coins.baltop.titre");

        var classement = tousLesSoldes.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(10)
                .collect(Collectors.toList());

        int rang = 1;
        for (Map.Entry<UUID, Double> entry : classement) {
            OfflinePlayer p = Bukkit.getOfflinePlayer(entry.getKey());
            String nom = p.getName() != null ? p.getName() : "Inconnu";
            messages.send(sender, "coins.baltop.ligne", Map.of(
                    "rang", String.valueOf(rang),
                    "couleur", podiumTag(rang),
                    "joueur", nom,
                    "montant", formatCristaux(entry.getValue())
            ));
            rang++;
        }
    }

    // /coins reset → remet tous les soldes à 0 (admin uniquement)
    private void handleReset(CommandSender sender) {
        if (!sender.hasPermission("plugin2.admin")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return;
        }
        plugin.getDatabaseResetAll();
        messages.send(sender, "coins.reset.confirme");
    }

    // /coins help → liste des commandes admin (admin uniquement)
    private void handleHelp(CommandSender sender) {
        if (!sender.hasPermission("plugin2.admin")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return;
        }

        messages.send(sender, "coins.help.titre");
        messages.send(sender, "coins.help.ligne-voir");
        messages.send(sender, "coins.help.ligne-give");
        messages.send(sender, "coins.help.ligne-take");
        messages.send(sender, "coins.help.ligne-set");
        messages.send(sender, "coins.help.ligne-baltop");
        messages.send(sender, "coins.help.ligne-reset");
    }

    // Convertit le texte en nombre, et envoie un message d'erreur clair si invalide.
    private double parseMontant(CommandSender sender, String texte) {
        try {
            double valeur = Double.parseDouble(texte);
            if (valeur <= 0) {
                messages.send(sender, "coins.erreurs.montant-negatif-ou-nul");
                return Double.NaN;
            }
            return valeur;
        } catch (NumberFormatException e) {
            messages.send(sender, "coins.erreurs.montant-invalide", Map.of("texte", texte));
            return Double.NaN;
        }
    }
}