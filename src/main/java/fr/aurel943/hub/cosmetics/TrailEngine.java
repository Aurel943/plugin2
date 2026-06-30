package fr.aurel943.hub.cosmetics;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import fr.aurel943.hub.Hub;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fait apparaître en boucle les particules du trail actuellement équipé par
 * chaque joueur, tant qu'il est en ligne. Une seule tâche répétée par joueur
 * équipé (et non une tâche globale qui boucle sur tous les joueurs), pour
 * pouvoir respecter l'intervalle-ticks propre à CHAQUE trail défini dans
 * cosmetics.yml (un trail "dense" comme enchantement peut émettre plus
 * souvent qu'un trail "discret" comme neige).
 *
 * Suit le même principe que PetManager : on démarre/arrête une BukkitTask à
 * l'équipement/déséquipement plutôt que de vérifier l'état à chaque tick pour
 * tout le monde.
 */
public class TrailEngine {

    private final Hub plugin;
    private final CosmeticManager cosmetics;

    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();
    private final Map<UUID, String> activeTrailId = new HashMap<>();

    public TrailEngine(Hub plugin, CosmeticManager cosmetics) {
        this.plugin = plugin;
        this.cosmetics = cosmetics;
    }

    public String getActiveTrailId(UUID uuid) {
        return activeTrailId.get(uuid);
    }

    /** Démarre (ou redémarre) le trail pour ce joueur. Arrête l'ancien trail s'il y en avait un. */
    public void start(Player player, String trailId) {
        CosmeticManager.TrailDefinition def = cosmetics.getTrail(trailId);
        if (def == null) return;

        stop(player.getUniqueId());

        UUID uuid = player.getUniqueId();
        activeTrailId.put(uuid, trailId);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stop(uuid);
                return;
            }
            emit(player, def);
        }, 0L, def.intervalleTicks);

        activeTasks.put(uuid, task);
    }

    /** Arrête le trail actif de ce joueur (sans toucher à la base — voir CosmeticsMenu pour le déséquipement persistant). */
    public void stop(UUID uuid) {
        BukkitTask task = activeTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        activeTrailId.remove(uuid);
    }

    private void emit(Player player, CosmeticManager.TrailDefinition def) {
        Location loc = player.getLocation().clone().add(0, def.hauteur, 0);
        player.getWorld().spawnParticle(
                def.particle,
                loc,
                def.nombre,
                def.rayonX, def.rayonY, def.rayonZ,
                def.vitesse
        );
    }

    /** À appeler à la déconnexion : libère la tâche en mémoire sans toucher à la persistance. */
    public void handlePlayerQuit(Player player) {
        stop(player.getUniqueId());
    }

    /** À appeler à la connexion : relance automatiquement le trail équipé en base, s'il y en a un. */
    public void handlePlayerJoin(Player player) {
        String equippedId = cosmetics.getEquipped(player.getUniqueId(), CosmeticManager.CATEGORY_TRAIL);
        if (equippedId == null) return;

        if (cosmetics.getTrail(equippedId) == null) {
            // Le trail a peut-être été retiré de cosmetics.yml depuis l'achat — on nettoie proprement
            cosmetics.setEquipped(player.getUniqueId(), CosmeticManager.CATEGORY_TRAIL, null);
            return;
        }

        start(player, equippedId);
    }
}