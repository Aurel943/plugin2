package fr.aurel943.hub.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import fr.aurel943.hub.Hub;
import fr.aurel943.hub.parkour.ParkourManager;

/**
 * Verrouille le monde du parkour comme le hub l'est (cf. HubRulesListener) :
 * aucune casse, pose, drop ou pickup pour un joueur normal, et aucun dégât
 * de chute pour personne (un parkour repose sur les checkpoints/limite-y de
 * ParkourManager pour gérer une chute, pas sur les dégâts de vie classiques).
 * Seule différence avec le hub : un joueur avec la permission hub.admin
 * passe à travers les restrictions de casse/pose/drop/pickup, pour pouvoir
 * construire/modifier le parcours directement en jeu. Pas de nouveau nœud
 * de permission créé — on réutilise hub.admin, déjà utilisé pour toutes
 * les actions administratives du plugin (cf. CONTRAINTES du projet : pas de
 * permission supplémentaire sans le signaler d'abord).
 *
 * Contrairement à HubRulesListener, ces règles ne sont pas paramétrables
 * dans un .yml : il n'y a qu'un seul comportement voulu ici (monde figé pour
 * tous sauf les admins, jamais de dégâts de chute pour personne), pas besoin
 * de la souplesse on/off du hub.
 */
public class ParkourRulesListener implements Listener {

    private final ParkourManager parkour;

    public ParkourRulesListener(Hub plugin) {
        this.parkour = plugin.getParkourManager();
    }

    private boolean estAdmin(Player player) {
        return player.hasPermission("hub.parkour.build");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!parkour.isParkourWorld(event.getBlock().getWorld())) return;
        if (!estAdmin(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!parkour.isParkourWorld(event.getBlock().getWorld())) return;
        if (!estAdmin(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if (!parkour.isParkourWorld(event.getPlayer().getWorld())) return;
        if (!estAdmin(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickupItem(PlayerPickupItemEvent event) {
        if (!parkour.isParkourWorld(event.getPlayer().getWorld())) return;
        if (!estAdmin(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Aucun dégât de chute dans le monde parkour, pour personne (admin
     * compris) : une chute est gérée par téléportation au checkpoint
     * (voir ParkourManager.gererChute), pas par une perte de vie. Sans
     * cette règle, un joueur pourrait mourir de la chute avant même que
     * la téléportation ait eu le temps de se déclencher.
     */
    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!parkour.isParkourWorld(player.getWorld())) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    /**
     * Aucune perte de faim dans le monde parkour, pour personne (admin
     * compris) — même logique que HubRulesListener.onFoodChange() : le
     * parkour est un monde figé où la survie ne doit jamais entrer en jeu.
     * Contrairement au hub, pas de clé .yml pour rendre ça optionnel : un
     * seul comportement voulu ici, donc pas besoin de la souplesse on/off.
     */
    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!parkour.isParkourWorld(player.getWorld())) return;

        event.setCancelled(true);
        player.setFoodLevel(20);
        player.setSaturation(20f);
    }
}