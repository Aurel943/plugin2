package org.example.plugin2.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.example.plugin2.Plugin2;
import org.example.plugin2.parkour.ParkourManager;
import org.example.plugin2.parkour.ParkourSession;

import java.util.List;
import java.util.Map;

/**
 * Détecte tout ce qui se passe "en marchant" dans un monde de parkour :
 * entrée dans la zone de départ, validation des checkpoints, chute sous la
 * limite Y, et arrivée. Gère aussi le clic sur l'objet retour, ainsi que la
 * déconnexion (annule le run en cours) et la connexion (restaure
 * l'inventaire si une sauvegarde était restée en attente après un crash).
 *
 * Optimisation importante : PlayerMoveEvent se déclenche à CHAQUE mouvement,
 * y compris un simple mouvement de caméra sans déplacement. On filtre donc
 * dès l'entrée de la méthode sur : (1) le joueur est dans un monde de
 * parkour connu, ET (2) il a changé de bloc entier depuis le tick précédent
 * (pas juste tourné la tête). Au-delà, les vérifications de zone elles-mêmes
 * sont bon marché (quelques comparaisons de double), donc pas besoin
 * d'optimisation plus fine pour le nombre de joueurs attendu sur ce serveur.
 */
public class ParkourListener implements Listener {

    private final Plugin2 plugin;
    private final ParkourManager parkour;

    public ParkourListener(Plugin2 plugin) {
        this.plugin = plugin;
        this.parkour = plugin.getParkourManager();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (!parkour.isParkourWorld(event.getPlayer().getWorld())) return;

        // Ne traite la logique de zone que si le joueur a changé de bloc entier
        // (évite de recalculer à chaque tout petit mouvement/rotation de caméra).
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();

        ParkourSession session = parkour.getSession(player.getUniqueId());
        String parkourIdCourant = session != null ? session.getParkourId() : trouverParkourDuMonde(player);
        if (parkourIdCourant == null) return;

        ParkourManager.ParkourDefinition def = parkour.getDefinition(parkourIdCourant);
        if (def == null) return;

        // Chute : prioritaire sur tout le reste, vérifiée seulement si un run
        // est en cours (sans run, pas de checkpoint vers lequel revenir).
        if (session != null && event.getTo().getY() < def.limiteYChute) {
            parkour.gererChute(player);
            return;
        }

        // Départ : démarre un run si aucun n'est en cours.
        if (session == null && def.depart.contient(event.getTo())) {
            parkour.demarrerRun(player, def.id);
            return;
        }

        if (session == null) return; // tout ce qui suit nécessite un run actif

        // Checkpoints : on vérifie tous les checkpoints (pas seulement le suivant),
        // pour permettre de valider un checkpoint plus loin si le joueur a sauté
        // une section, ou de revalider sans effet un checkpoint déjà acquis.
        List<ParkourManager.ZoneBoite> checkpoints = def.checkpoints;
        for (int i = 0; i < checkpoints.size(); i++) {
            if (checkpoints.get(i).contient(event.getTo())) {
                parkour.validerCheckpoint(player, i);
                break;
            }
        }

        // Arrivée : termine le run.
        if (def.arrivee.contient(event.getTo())) {
            parkour.terminerRun(player);
        }
    }

    /**
     * Détermine à quel parkour appartient le monde actuel du joueur (utilisé
     * quand il n'a pas encore de session active — ex: vient d'arriver par
     * téléportation et marche vers la zone de départ).
     */
    private String trouverParkourDuMonde(Player player) {
        String mondeNom = player.getWorld().getName();
        for (ParkourManager.ParkourDefinition def : parkour.getAllDefinitions().values()) {
            if (def.mondeNom.equals(mondeNom)) return def.id;
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Objet retour
    // ---------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (parkour.estObjetRetour(event.getItem())) {
            event.setCancelled(true);
            parkour.sortirDuParkour(event.getPlayer());
        }
    }

    // ---------------------------------------------------------------
    // Connexion / déconnexion
    // ---------------------------------------------------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Annule le run en cours (sans restaurer l'inventaire ici : le joueur
        // est hors-ligne, la restauration se fera à sa prochaine connexion
        // via onJoin ci-dessous, grâce à la sauvegarde déjà en base).
        parkour.abandonnerRun(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Cas d'un crash serveur (ou /stop) pendant qu'un joueur était dans le
        // parkour : sa sauvegarde d'inventaire est encore en base à sa
        // reconnexion. On restaure immédiatement avant qu'il ne touche à quoi
        // que ce soit, pour ne jamais le laisser dans un inventaire vidé.
        // Le joueur réapparaît ici au spawn par défaut du serveur (donc déjà
        // dans le bon monde), pas besoin d'attendre une téléportation avant
        // de réactiver pet/trail — contrairement à la sortie normale du
        // parkour, voir ParkourManager.restaurerEtRenvoyerAuHub().
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                var backup = parkour.restaurerInventaire(player);
                parkour.reactiverPetEtTrailApresRestauration(player, backup);
            }
        });
    }
}