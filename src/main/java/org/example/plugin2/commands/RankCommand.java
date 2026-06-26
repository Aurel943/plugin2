package org.example.plugin2.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.Database;
import org.example.plugin2.messages.MessagesManager;
import org.example.plugin2.ranks.RankManager;

import java.util.Map;

public class RankCommand implements CommandExecutor {

    private final Plugin2 plugin;
    private final RankManager ranks;
    private final MessagesManager messages;

    public RankCommand(Plugin2 plugin) {
        this.plugin = plugin;
        this.ranks = plugin.getRankManager();
        this.messages = plugin.getMessagesManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("plugin2.admin")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return true;
        }

        if (args.length == 0) {
            messages.send(sender, "rank.usage");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "set" -> handleSet(sender, args);
            case "addperm" -> handleAddPerm(sender, args);
            case "removeperm" -> handleRemovePerm(sender, args);
            case "list" -> handleList(sender);
            case "info" -> handleInfo(sender, args);
            default -> messages.send(sender, "rank.usage");
        }

        return true;
    }

    // /rank create <id> <prefix> [poids]
    private void handleCreate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "rank.create.usage");
            return;
        }
        String rankId = args[1].toLowerCase();
        String prefix = args[2];
        int poids = args.length >= 4 ? parseIntOuZero(args[3]) : 0;

        ranks.createOrUpdateRank(rankId, prefix, poids);
        messages.send(sender, "rank.create.confirme", Map.of("rank", rankId));
    }

    // /rank delete <id>
    private void handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "rank.delete.usage");
            return;
        }
        String rankId = args[1].toLowerCase();
        if (rankId.equals(RankManager.RANK_PAR_DEFAUT)) {
            messages.send(sender, "rank.delete.rank-par-defaut-protege");
            return;
        }

        boolean success = ranks.deleteRank(rankId);
        messages.send(sender, success ? "rank.delete.confirme" : "rank.delete.inexistant",
                Map.of("rank", rankId));
    }

    // /rank set <joueur> <id>
    private void handleSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "rank.set.usage");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);

        // Comme UuidCommand/CoinsCommand : on vérifie que ce pseudo correspond
        // à un joueur ayant réellement existé, pour ne pas assigner
        // silencieusement un rank à un OfflinePlayer "fantôme" créé par Bukkit
        // pour n'importe quel nom mal orthographié.
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            messages.send(sender, "uuid.joueur-inconnu", Map.of("pseudo", args[1]));
            return;
        }

        String rankId = args[2].toLowerCase();

        if (!ranks.rankExists(rankId)) {
            messages.send(sender, "rank.inexistant", Map.of("rank", rankId));
            return;
        }

        ranks.setPlayerRank(target.getUniqueId(), rankId);
        messages.send(sender, "rank.set.confirme", Map.of("joueur", args[1], "rank", rankId));
    }

    // /rank addperm <id> <permission>
    private void handleAddPerm(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "rank.addperm.usage");
            return;
        }
        String rankId = args[1].toLowerCase();
        if (!ranks.rankExists(rankId)) {
            messages.send(sender, "rank.inexistant", Map.of("rank", rankId));
            return;
        }

        ranks.addPermission(rankId, args[2]);
        messages.send(sender, "rank.addperm.confirme", Map.of("permission", args[2], "rank", rankId));
    }

    // /rank removeperm <id> <permission>
    private void handleRemovePerm(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "rank.removeperm.usage");
            return;
        }
        String rankId = args[1].toLowerCase();
        if (!ranks.rankExists(rankId)) {
            messages.send(sender, "rank.inexistant", Map.of("rank", rankId));
            return;
        }

        ranks.removePermission(rankId, args[2]);
        messages.send(sender, "rank.removeperm.confirme", Map.of("permission", args[2], "rank", rankId));
    }

    // /rank list
// /rank list — triés du rank le plus haut (poids le plus grand) au plus bas
    private void handleList(CommandSender sender) {
        messages.send(sender, "rank.list.titre");

        ranks.getAllRanks().stream()
                .sorted((a, b) -> Integer.compare(b.poids, a.poids))
                .forEach(rank -> messages.send(sender, "rank.list.ligne", Map.of(
                        "rank", rank.rankId,
                        "prefix", rank.prefix,
                        "nb-permissions", String.valueOf(rank.permissions.size())
                )));
    }

    // /rank info <id>
    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "rank.info.usage");
            return;
        }
        Database.RankData rank = ranks.getRank(args[1].toLowerCase());
        if (rank == null) {
            messages.send(sender, "rank.inexistant", Map.of("rank", args[1]));
            return;
        }

        messages.send(sender, "rank.info.titre", Map.of("rank", rank.rankId));
        messages.send(sender, "rank.info.prefix", Map.of("prefix", rank.prefix));
        messages.send(sender, "rank.info.poids", Map.of("poids", String.valueOf(rank.poids)));
        if (rank.permissions.isEmpty()) {
            messages.send(sender, "rank.info.aucune-permission");
        } else {
            for (String permission : rank.permissions) {
                messages.send(sender, "rank.info.ligne-permission", Map.of("permission", permission));
            }
        }
    }

    private int parseIntOuZero(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}