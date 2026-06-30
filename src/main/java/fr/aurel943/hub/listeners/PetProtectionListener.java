package fr.aurel943.hub.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import fr.aurel943.hub.Hub;
import fr.aurel943.hub.pets.PetManager;

/**
 * Protège les pets actifs (mobs vivants comme le loup, le chat, le perroquet...)
 * contre toute interaction ou dégât du joueur, sur le même principe que
 * HubNpcInteractListener pour le PNJ du hub :
 *
 *  - EntityDamageByEntityEvent / EntityDamageEvent : empêche un joueur de
 *    "frapper" son propre pet (ou celui d'un autre) — entity.setInvulnerable(true)
 *    dans PetManager empêche déjà la mort, mais pas l'animation de
 *    dégât/recul ni le bruit associé, ce qui restait visuellement cassé
 *    pour un pet cosmétique.
 *  - PlayerInteractEntityEvent : empêche le clic droit (ex: redonner de la
 *    nourriture à un loup/chat déjà apprivoisé, ce qui déclenche des cœurs
 *    et d'autres animations parasites sans aucun effet utile ici).
 *
 * Le cas du perroquet qui monte sur l'épaule en marchant simplement à
 * travers lui (PAS besoin de clic droit, donc PlayerInteractEntityEvent ne
 * le couvre pas) n'est PAS géré ici : il n'existe aucun event Bukkit/Paper
 * permettant d'intercepter ce montage avant qu'il n'ait lieu (confirmé —
 * pas de PlayerShoulderEntityEvent ni équivalent dans l'API). La solution
 * retenue est de mettre le perroquet en mode "assis" dès sa création dans
 * PetManager.spawnMobPet() : un perroquet assis ne monte jamais à l'épaule,
 * que ce soit par clic ou en le traversant — voir le commentaire associé
 * dans PetManager.
 */
public class PetProtectionListener implements Listener {

    private final PetManager pets;

    public PetProtectionListener(Hub plugin) {
        this.pets = plugin.getPetManager();
    }

    @EventHandler
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!pets.isManagedPet(event.getEntity())) return;
        event.setCancelled(true);
    }

    /** Sécurité supplémentaire : couvre aussi les dégâts sans attaquant direct (ex: feu, chute si jamais l'invulnérabilité changeait). */
    @EventHandler
    public void onAnyDamage(EntityDamageEvent event) {
        if (!pets.isManagedPet(event.getEntity())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!pets.isManagedPet(event.getRightClicked())) return;
        event.setCancelled(true);
    }
}