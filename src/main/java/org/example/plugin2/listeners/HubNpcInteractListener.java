package org.example.plugin2.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.example.plugin2.Plugin2;
import org.example.plugin2.npc.HubNpcManager;

/**
 * Écoute les interactions d'un joueur avec un PNJ du hub.
 *
 * Deux events Bukkit différents couvrent deux comportements distincts sur un
 * ArmorStand :
 *  - PlayerInteractEntityEvent : le clic "générique" (ouvrirait un futur menu
 *    PNJ une fois implémenté — voir TODO ci-dessous).
 *  - PlayerArmorStandManipulateEvent : spécifique à Bukkit pour le cas où le
 *    joueur essaie d'ÉQUIPER un item sur l'ArmorStand (casque, plastron,
 *    item en main...). Sans l'annuler explicitement, un joueur pouvait
 *    équiper sa boussole du hub (ou n'importe quel item) sur le PNJ, la
 *    perdant ainsi de son inventaire — comportement jamais voulu pour un
 *    PNJ purement décoratif/futur menu.
 */
public class HubNpcInteractListener implements Listener {

    private final Plugin2 plugin;
    private final HubNpcManager npcManager;

    public HubNpcInteractListener(Plugin2 plugin, HubNpcManager npcManager) {
        this.plugin = plugin;
        this.npcManager = npcManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!npcManager.isManagedNpc(event.getRightClicked())) {
            return;
        }

        String npcId = npcManager.getNpcId(event.getRightClicked());

        // TODO: ouvrir un menu spécifique au PNJ cliqué (npcId), une fois
        // qu'on aura défini ce que chaque PNJ doit proposer. Pour l'instant,
        // le clic est intercepté (event consommé plus bas) mais ne fait rien
        // de visible — comportement volontaire en attendant le menu.

        event.setCancelled(true); // évite que le clic déclenche un comportement vanilla parasite (ex: monter sur l'ArmorStand)
    }

    /**
     * Empêche d'équiper/retirer un item sur l'ArmorStand d'un PNJ du hub
     * (casque, plastron, jambières, bottes, ou item en main). Sans ce
     * handler, un clic droit avec un objet en main sur le PNJ pouvait le lui
     * mettre directement — la boussole du hub en particulier ne doit jamais
     * pouvoir quitter l'inventaire du joueur de cette façon.
     */
    @EventHandler
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (!npcManager.isManagedNpc(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
    }
}