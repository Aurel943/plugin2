package fr.aurel943.hub.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import fr.aurel943.hub.Hub;
import fr.aurel943.hub.parkour.ParkourManager;
import fr.aurel943.hub.parkour.ParkourSession;
import org.bukkit.Location;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;

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

    private final Hub plugin;
    private final ParkourManager parkour;

    public ParkourListener(Hub plugin) {
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

        // Départ : démarre un run si aucun n'est en cours. Si un run est DÉJÀ
        // en cours et que le joueur retouche la zone "depart" (ex: il est
        // reparti en arrière après le 1er checkpoint), on traite ça comme un
        // reset automatique : nouveau chrono à zéro, comme un clic sur l'objet
        // reset — sans ça, revenir au départ pendant un run ne faisait
        // strictement rien (le chrono continuait de tourner depuis le tout
        // premier départ, ce qui n'a pas de sens si le joueur recommence).
        if (def.depart.contient(event.getTo())) {
            if (session == null) {
                parkour.demarrerRun(player, def.id);
            } else {
                parkour.reinitialiserRun(player);
            }
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
            return;
        }

        if (parkour.estObjetReset(event.getItem())) {
            event.setCancelled(true);
            parkour.reinitialiserRun(event.getPlayer());
        }
    }

    // ---------------------------------------------------------------
    // Mort / réapparition dans le monde parkour
    // ---------------------------------------------------------------

    /**
     * Une mort dans le monde parkour ne doit JAMAIS renvoyer le joueur vers
     * le spawn par défaut du serveur ("world") ni lui faire perdre son
     * inventaire parkour (objet retour / objet reset) — contrairement au
     * comportement par défaut de Bukkit sans ce listener. On calcule la
     * destination ici (pendant la mort, le joueur ne peut pas encore être
     * téléporté), et onRespawn() applique cette destination au moment où
     * Bukkit le réapparaît réellement.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!parkour.isParkourWorld(player.getWorld())) return;

        // Pas de perte d'objets à la mort dans le parkour — cohérent avec
        // ParkourRulesListener qui bloque déjà tout drop/pickup manuel.
        event.getDrops().clear();
        event.setKeepInventory(true);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!parkour.isParkourWorld(player.getWorld())) return;

        Location destination = parkour.calculerDestinationApresMort(player);
        if (destination == null) return; // sécurité : définition introuvable, on laisse Bukkit faire son défaut

        event.setRespawnLocation(destination);

        String cleMessage = parkour.messageApresMort(player);
        if (cleMessage != null) {
            // Léger délai : le message juste après setRespawnLocation arrive
            // parfois avant que le client ait fini de traiter la téléportation
            // de réapparition — un tick suffit à éviter que le message se
            // perde visuellement dans le chargement du monde.
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getMessagesManager().send(player, cleMessage));
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