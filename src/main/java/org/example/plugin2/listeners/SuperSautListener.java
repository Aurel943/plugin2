package org.example.plugin2.listeners;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.UpgradeManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Détecte le double-tap de la touche espace et déclenche le "super saut" si le
 * joueur a acheté l'amélioration correspondante (achat unique, valable pour la
 * vie — voir UpgradeManager) ET que l'amélioration est actuellement activée
 * (toggle ON/OFF accessible depuis la boutique d'améliorations — voir
 * UpgradeShopMenu, qui sert aussi de bouton toggle une fois l'achat fait).
 *
 * IMPORTANT : on utilise PlayerInputEvent (et non PlayerJumpEvent) pour détecter
 * l'appui. PlayerJumpEvent se redéclenche en boucle tant que la touche espace est
 * maintenue (le client renvoie un saut à chaque fois que le joueur retouche le
 * sol), ce qui rendait la détection du double-tap peu fiable : rester appuyé
 * déclenchait le super saut, et deux appuis francs rapprochés pouvaient au
 * contraire ne pas être détectés. PlayerInputEvent expose l'état réel des
 * touches côté client à chaque tick ; on ne réagit qu'au "front montant"
 * (passage de relâché à appuyé), ce qui correspond exactement à un appui.
 *
 * Le super saut propulse le joueur verticalement ET horizontalement dans la
 * direction où il regarde, avec un son et une traînée de particules pendant
 * tout le vol. Il ne coûte rien à l'usage : seul l'achat initial coûte des
 * tals (voir UpgradeShopMenu). Aucun message n'est envoyé à l'exécution du
 * saut lui-même — seules les particules et le son signalent l'action.
 */
public class SuperSautListener implements Listener {

    private final Plugin2 plugin;
    private final UpgradeManager upgrades;

    // Dernier état connu de la touche espace par joueur, pour détecter le
    // front montant (appui) plutôt que l'état "maintenu".
    private final Map<UUID, Boolean> espaceEtaitAppuyee = new HashMap<>();

    // Timestamp (millis) du dernier appui simple détecté par joueur,
    // utilisé pour repérer un second appui rapproché (= double-tap).
    private final Map<UUID, Long> dernierAppuiSimple = new HashMap<>();

    // Joueurs actuellement "en vol" de super saut, pour la traînée de particules
    // ET pour empêcher d'en redéclencher un avant d'avoir retouché le sol.
    private final Set<UUID> enVolSuperSaut = new HashSet<>();

    public SuperSautListener(Plugin2 plugin) {
        this.plugin = plugin;
        this.upgrades = plugin.getUpgradeManager();
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        boolean appuieeMaintenant = event.getInput().isJump();
        boolean appuieeAvant = espaceEtaitAppuyee.getOrDefault(uuid, false);
        espaceEtaitAppuyee.put(uuid, appuieeMaintenant);

        // On ne réagit qu'au moment précis où la touche passe de relâchée à
        // appuyée (front montant). Si elle était déjà appuyée au tick précédent,
        // ce n'est pas un nouvel appui : on l'ignore (corrige le "marche en
        // restant appuyé").
        if (!appuieeMaintenant || appuieeAvant) return;

        if (!plugin.getHubWorldManager().isHubWorld(player.getWorld())) return;
        if (!upgrades.has(uuid, UpgradeManager.SUPER_SAUT)) return;
        if (!upgrades.isEnabled(uuid, UpgradeManager.SUPER_SAUT)) return;

        var config = plugin.getHubWorldManager().getConfig();
        if (!config.getBoolean("super-saut.active", true)) return;

        // Tant que le joueur n'a pas retouché le sol depuis son dernier super
        // saut (cf. demarrerTraineeParticules), il ne peut pas en redéclencher
        // un. C'est ce verrou — et non un délai fixe — qui empêche un spam
        // immédiat tout en permettant de rechaîner un super saut dès l'atterrissage.
        if (enVolSuperSaut.contains(uuid)) {
            dernierAppuiSimple.put(uuid, System.currentTimeMillis());
            return;
        }

        long maintenant = System.currentTimeMillis();
        long fenetreMs = (long) (config.getDouble("super-saut.fenetre-double-tap-secondes", 0.4) * 1000);

        Long dernierSimple = dernierAppuiSimple.get(uuid);

        boolean estDoubleTap = dernierSimple != null && (maintenant - dernierSimple) < fenetreMs;

        if (!estDoubleTap) {
            // Premier appui de la paire : il doit obligatoirement partir du sol
            // (le saut "normal" qui suit cet appui fera décoller le joueur ;
            // c'est volontaire et c'est ce second décollage qu'on intercepte).
            // On ne mémorise donc ce timestamp que si le joueur était au sol.
            if (player.isOnGround()) {
                dernierAppuiSimple.put(uuid, maintenant);
            }
            return;
        }

        // Second appui de la paire : on ne vérifie PAS isOnGround() ici. Lors
        // d'un double-tap rapide et précis, ce second appui arrive presque
        // toujours pendant que le joueur est encore en l'air (conséquence du
        // saut normal déclenché par le premier appui) — exiger isOnGround()
        // ici forçait à attendre l'atterrissage avant de pouvoir valider le
        // double-tap, ce qui est l'inverse du comportement voulu.
        dernierAppuiSimple.remove(uuid);
        performSuperSaut(player, config);
    }

    /** Applique l'impulsion verticale + horizontale, le son, et lance la traînée de particules. */
    private void performSuperSaut(Player player, org.bukkit.configuration.file.YamlConfiguration config) {
        double puissanceVerticale = config.getDouble("super-saut.puissance-verticale", 1.2);
        double puissanceHorizontale = config.getDouble("super-saut.puissance-horizontale", 1.0);

        // Direction horizontale = vers où le joueur regarde (yaw uniquement, pas le pitch,
        // pour ne pas projeter le joueur vers le sol/ciel s'il regarde en haut/bas).
        Location loc = player.getLocation();
        double yawRad = Math.toRadians(loc.getYaw());
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);

        Vector impulsion = new Vector(dirX * puissanceHorizontale, puissanceVerticale, dirZ * puissanceHorizontale);
        player.setVelocity(impulsion);

        player.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.4f);

        // Burst initial au moment du décollage (en plus de la traînée qui suit).
        player.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0, 0.1, 0), 30, 0.4, 0.1, 0.4, 0.02);
        player.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.5, 0), 12, 0.3, 0.3, 0.3, 0.05);

        demarrerTraineeParticules(player);
    }

    /**
     * Fait apparaître des particules autour du joueur à chaque tick pendant
     * tout le vol du super saut, jusqu'à ce qu'il retouche le sol (ou après un
     * délai de sécurité si jamais il ne retouchait jamais le sol détecté,
     * par exemple en cas de chute dans le vide).
     */
    private void demarrerTraineeParticules(Player player) {
        UUID uuid = player.getUniqueId();

        // Si une traînée est déjà active pour ce joueur (ne devrait pas arriver
        // puisque enVolSuperSaut bloque déjà tout nouveau déclenchement), on ne
        // la double pas, par sécurité.
        if (enVolSuperSaut.contains(uuid)) return;
        enVolSuperSaut.add(uuid);

        new BukkitRunnable() {
            // Sécurité : 100 ticks (5s) max, largement suffisant pour la trajectoire du saut.
            int ticksRestants = 100;

            @Override
            public void run() {
                // Les 2 premiers ticks sont ignorés pour la détection du sol : au
                // moment du décollage, isOnGround() peut encore renvoyer true le
                // temps que la vélocité prenne effet. Au-delà, l'atterrissage est
                // détecté au tick exact où il se produit, pour permettre de
                // rechaîner un double-tap immédiatement.
                if (!player.isOnline() || ticksRestants-- <= 0 || (ticksRestants < 98 && player.isOnGround())) {
                    enVolSuperSaut.remove(uuid);
                    cancel();
                    return;
                }

                Location loc = player.getLocation();
                // Petit nuage continu sous/autour des pieds.
                player.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0, 0.1, 0), 4, 0.25, 0.05, 0.25, 0.01);
                // Traînée lumineuse au niveau du buste, qui suit le mouvement du joueur.
                player.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.9, 0), 2, 0.15, 0.15, 0.15, 0.01);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}