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
 *
 * Distingue deux notions indépendantes pour chaque upgrade :
 *   - la POSSESSION (has/grant) : acquise une fois pour toutes à l'achat,
 *     ne peut jamais être retirée.
 *   - l'ÉTAT ACTIVÉ/DÉSACTIVÉ (isEnabled/setEnabled) : un simple interrupteur
 *     que le joueur peut basculer librement une fois l'upgrade possédé,
 *     sans jamais perdre la possession sous-jacente.
 */
public class UpgradeManager {

    /** Identifiant de l'amélioration "double-tap espace = super saut". */
    public static final String SUPER_SAUT = "super-saut";

    private final Database database;

    // Cache mémoire : uuid -> ensemble des upgrades possédés.
    private final java.util.Map<UUID, Set<String>> cache = new ConcurrentHashMap<>();

    // Cache mémoire de l'état activé/désactivé : clé "uuid:upgradeId" -> enabled.
    // Évite de relire la base à chaque double-tap espace, comme pour has().
    private final java.util.Map<String, Boolean> enabledCache = new ConcurrentHashMap<>();

    public UpgradeManager(Database database) {
        this.database = database;
    }

    /** Vrai si le joueur possède déjà cette amélioration (achat fait, pour la vie). */
    public boolean has(UUID uuid, String upgradeId) {
        Set<String> owned = cache.computeIfAbsent(uuid, id -> new HashSet<>(database.getOwnedUpgrades(id)));
        return owned.contains(upgradeId);
    }

    /** Achète (accorde définitivement) une amélioration pour ce joueur. Activée par défaut. */
    public void grant(UUID uuid, String upgradeId) {
        database.grantUpgrade(uuid, upgradeId);
        cache.computeIfAbsent(uuid, id -> new HashSet<>()).add(upgradeId);
        enabledCache.put(cacheKey(uuid, upgradeId), true);
    }

    /**
     * Vrai si l'upgrade est actuellement activé (toggle ON/OFF). Sans rapport
     * avec la possession : à appeler uniquement après avoir vérifié has().
     */
    public boolean isEnabled(UUID uuid, String upgradeId) {
        return enabledCache.computeIfAbsent(cacheKey(uuid, upgradeId),
                key -> database.isUpgradeEnabled(uuid, upgradeId));
    }

    /** Bascule l'état activé/désactivé et retourne le nouvel état. */
    public boolean toggle(UUID uuid, String upgradeId) {
        boolean nouveauEtat = !isEnabled(uuid, upgradeId);
        setEnabled(uuid, upgradeId, nouveauEtat);
        return nouveauEtat;
    }

    /** Définit explicitement l'état activé/désactivé d'un upgrade déjà possédé. */
    public void setEnabled(UUID uuid, String upgradeId, boolean enabled) {
        database.setUpgradeEnabled(uuid, upgradeId, enabled);
        enabledCache.put(cacheKey(uuid, upgradeId), enabled);
    }

    /**
     * Vide les deux caches (possession + état activé/désactivé) pour tous
     * les joueurs. À utiliser après une modification SQL directe en base.
     */
    public void reloadAll() {
        cache.clear();
        enabledCache.clear();
    }

    private String cacheKey(UUID uuid, String upgradeId) {
        return uuid + ":" + upgradeId;
    }
}