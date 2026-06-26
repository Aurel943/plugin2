package org.example.plugin2.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.example.plugin2.Plugin2;
import org.example.plugin2.parkour.ParkourManager;
import org.example.plugin2.world.HubWorldManager;

/**
 * Bloque TOUT déplacement d'objet dans l'inventaire du joueur (clic ou drag)
 * tant qu'il se trouve dans le hub ou dans un monde de parkour — peu importe
 * l'item concerné (boussole, objet retour, objet reset, ou n'importe quoi
 * d'autre qui se retrouverait en inventaire).
 *
 * Pourquoi bloquer TOUT plutôt que seulement certains items précis (cf.
 * discussion "comme convenu") : le hub et le parkour sont des mondes
 * entièrement gérés par le plugin, où le joueur n'est censé interagir
 * qu'avec des items spéciaux fournis par le plugin lui-même (boussole,
 * objet retour, objet reset). Autoriser le moindre déplacement libre
 * ouvrait la porte à des incohérences (objet spécial déplacé dans un slot
 * inattendu, dupliqué via un menu encore ouvert, etc.) — bloquer
 * entièrement est plus simple et plus sûr que d'maintenir une liste
 * d'exceptions.
 *
 * Ce listener ne bloque QUE l'inventaire du JOUEUR (event.getView()
 * contient l'inventaire du bas, donc le sien) ; les menus du plugin
 * (HubMenu, PetsMenu, CosmeticsMenu, UpgradeShopMenu, TeleportMenu)
 * gèrent déjà eux-mêmes leur propre InventoryClickEvent avec
 * event.setCancelled(true) et continuent donc de fonctionner normalement
 * (cancel sur cancel ne pose aucun problème).
 */
public class InventoryLockListener implements Listener {

    private final HubWorldManager hubWorldManager;
    private final ParkourManager parkourManager;

    public InventoryLockListener(Plugin2 plugin) {
        this.hubWorldManager = plugin.getHubWorldManager();
        this.parkourManager = plugin.getParkourManager();
    }

    private boolean estDansMondeVerrouille(Player player) {
        return hubWorldManager.isHubWorld(player.getWorld())
                || parkourManager.isParkourWorld(player.getWorld());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!estDansMondeVerrouille(player)) return;

        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!estDansMondeVerrouille(player)) return;

        event.setCancelled(true);
    }
}