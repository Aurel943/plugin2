package fr.aurel943.hub.ranks;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import fr.aurel943.hub.economy.Database;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère les ranks et leurs permissions. Fonctionne comme EconomyManager et
 * UpgradeManager : un cache mémoire évite de relire la base à chaque
 * vérification, peuplé depuis la table "ranks" / "player_ranks".
 *
 * Contrairement à EconomyManager, ce manager ne se contente pas de stocker
 * une valeur : il APPLIQUE concrètement les permissions sur chaque joueur
 * connecté via un PermissionAttachment (API standard Bukkit/Paper). C'est ce
 * qui permet à n'importe quel autre plugin (présent ou futur) de reconnaître
 * ces permissions via player.hasPermission(...), sans rien savoir de notre
 * table MySQL.
 *
 * Un joueur n'a qu'UN SEUL rank actif à la fois (pas de cumul pour l'instant).
 */
public class RankManager {

    public static final String RANK_PAR_DEFAUT = "joueur";

    private final Database database;

    // Cache des définitions de ranks : rank_id -> données (prefix, poids, permissions).
    private final Map<String, Database.RankData> ranksCache = new ConcurrentHashMap<>();

    // Cache du rank actif de chaque joueur connecté : uuid -> rank_id.
    private final Map<UUID, String> playerRankCache = new ConcurrentHashMap<>();

    // PermissionAttachment actif par joueur connecté, pour pouvoir le retirer/recréer au reload.
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public RankManager(Database database) {
        this.database = database;
        loadRanksFromDatabase();
    }

    /** (Re)charge toutes les définitions de ranks depuis la base dans le cache. */
    private void loadRanksFromDatabase() {
        ranksCache.clear();
        for (Database.RankData rank : database.getAllRanks()) {
            ranksCache.put(rank.rankId, rank);
        }
    }

    /** Vide les caches (ranks + rank des joueurs) et réapplique les permissions à tous les joueurs connectés. */
    public void reloadAll() {
        loadRanksFromDatabase();
        playerRankCache.clear();
        // Réapplique les permissions à jour à tous les joueurs déjà en ligne,
        // sans attendre une reconnexion (cohérent avec /hub reload).
        for (UUID uuid : new java.util.HashSet<>(attachments.keySet())) {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(uuid);
            if (player != null) {
                applyRank(player);
            }
        }
    }

    /** Retourne le rank_id actif d'un joueur (depuis le cache, sinon lecture base + mise en cache). */
    public String getPlayerRankId(UUID uuid) {
        return playerRankCache.computeIfAbsent(uuid, database::getPlayerRankId);
    }

    /** Retourne les données complètes (prefix, poids, permissions) du rank actif d'un joueur. */
    public Database.RankData getPlayerRank(UUID uuid) {
        String rankId = getPlayerRankId(uuid);
        return ranksCache.getOrDefault(rankId, ranksCache.get(RANK_PAR_DEFAUT));
    }

    /** Retourne les données d'un rank par son id, ou null s'il n'existe pas. */
    public Database.RankData getRank(String rankId) {
        return ranksCache.get(rankId);
    }

    /** Retourne tous les ranks connus (pour /rank list). */
    public java.util.Collection<Database.RankData> getAllRanks() {
        return ranksCache.values();
    }

    public boolean rankExists(String rankId) {
        return ranksCache.containsKey(rankId);
    }

    /** Crée un nouveau rank (ou met à jour un existant) et rafraîchit le cache. */
    public void createOrUpdateRank(String rankId, String prefix, int poids) {
        database.createOrUpdateRank(rankId, prefix, poids);
        loadRanksFromDatabase();
    }

    /** Supprime un rank. Retourne false s'il n'existait pas. Ne touche pas aux joueurs qui l'avaient. */
    public boolean deleteRank(String rankId) {
        boolean supprime = database.deleteRank(rankId);
        if (supprime) {
            ranksCache.remove(rankId);
        }
        return supprime;
    }

    /** Ajoute une permission à un rank et réapplique immédiatement aux joueurs concernés en ligne. */
    public void addPermission(String rankId, String permission) {
        database.addPermissionToRank(rankId, permission);
        loadRanksFromDatabase();
        reapplyToPlayersWithRank(rankId);
    }

    /** Retire une permission d'un rank et réapplique immédiatement aux joueurs concernés en ligne. */
    public void removePermission(String rankId, String permission) {
        database.removePermissionFromRank(rankId, permission);
        loadRanksFromDatabase();
        reapplyToPlayersWithRank(rankId);
    }

    /** Assigne un rank à un joueur (hors-ligne ou en ligne) et réapplique ses permissions s'il est connecté. */
    public void setPlayerRank(UUID uuid, String rankId) {
        database.setPlayerRank(uuid, rankId);
        playerRankCache.put(uuid, rankId);

        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(uuid);
        if (player != null) {
            applyRank(player);
        }
    }

    /**
     * Calcule et applique les permissions du rank actif d'un joueur connecté,
     * via un PermissionAttachment. Doit être appelé au join (et au reload).
     * Retire l'ancien attachment avant d'en créer un nouveau, pour éviter
     * d'accumuler des permissions obsolètes si le rank a changé.
     */
    public void applyRank(Player player) {
        UUID uuid = player.getUniqueId();

        PermissionAttachment ancien = attachments.remove(uuid);
        if (ancien != null) {
            player.removeAttachment(ancien);
        }

        Database.RankData rank = getPlayerRank(uuid);
        if (rank == null) return;

        PermissionAttachment attachment = player.addAttachment(plugin());
        for (String permission : rank.permissions) {
            if (!permission.isBlank()) {
                attachment.setPermission(permission, true);
            }
        }
        attachments.put(uuid, attachment);

        // Tab-list : préfixe le pseudo affiché avec le prefix du rank (Adventure Component).
        String prefixLegacy = rank.prefix.isEmpty() ? "" : rank.prefix + " ";
        String coloredLegacy = org.bukkit.ChatColor.translateAlternateColorCodes('&', prefixLegacy);
        net.kyori.adventure.text.Component tabName = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(coloredLegacy)
                .append(net.kyori.adventure.text.Component.text(player.getName()));
        player.playerListName(tabName);
    }

    /** Retire l'attachment d'un joueur à sa déconnexion (évite une fuite mémoire). */
    public void clearOnQuit(Player player) {
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) {
            player.removeAttachment(attachment);
        }
        playerRankCache.remove(player.getUniqueId());
    }

    private void reapplyToPlayersWithRank(String rankId) {
        for (Map.Entry<UUID, String> entry : playerRankCache.entrySet()) {
            if (entry.getValue().equals(rankId)) {
                org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    applyRank(player);
                }
            }
        }
    }

    // Référence au plugin nécessaire pour addAttachment() ; injectée une fois, pas reconstruite à chaque appel.
    private org.bukkit.plugin.Plugin pluginInstance;

    public void setPluginInstance(org.bukkit.plugin.Plugin plugin) {
        this.pluginInstance = plugin;
    }

    private org.bukkit.plugin.Plugin plugin() {
        return pluginInstance;
    }
}