package org.example.plugin2.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.example.plugin2.Plugin2;
import org.example.plugin2.npc.HubNpcManager;

/**
 * Écoute le clic d'un joueur sur un PNJ du hub. Pour l'instant ne fait rien
 * (juste un TODO) — un menu s'ouvrira ici dans une prochaine étape, une fois
 * qu'on aura défini ce que chaque PNJ doit proposer (ex: téléportation,
 * boutique, infos serveur...).
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
}