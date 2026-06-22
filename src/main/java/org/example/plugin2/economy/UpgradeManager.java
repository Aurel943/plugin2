package org.example.plugin2.economy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère les améliorations achetées une seule fois et valables pour la vie
 * (ex: "super-saut"). Fonctionne comme EconomyManager : un cache mémoire
 * évite de relire la base à chaque vérification, ce qui compte puisque
 * hasUpgrade() est appelé à chaque double-tap espace.
 */
public class UpgradeManager {

    /** Identifiant de l'amélioration "double-tap espace = super saut". */
    public static final String SUPER_SAUT = "super-saut";

    private final Database database;

    // Cache mémoire : uuid -> ensemble des upgrades possédés.
    private final java.util.Map<UUID, Set<String>> cache = new ConcurrentHashMap<>();

    public UpgradeManager(Database database) {
        this.database = database;
    }

    /** Vrai si le joueur possède déjà cette amélioration. */
    public boolean has(UUID uuid, String upgradeId) {
        Set<String> owned = cache.computeIfAbsent(uuid, id -> new HashSet<>(database.getOwnedUpgrades(id)));
        return owned.contains(upgradeId);
    }

    /** Achète (accorde définitivement) une amélioration pour ce joueur. */
    public void grant(UUID uuid, String upgradeId) {
        database.grantUpgrade(uuid, upgradeId);
        cache.computeIfAbsent(uuid, id -> new HashSet<>()).add(upgradeId);
    }
}