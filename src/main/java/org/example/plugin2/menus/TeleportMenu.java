package org.example.plugin2.menus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.example.plugin2.Plugin2;
import org.example.plugin2.messages.MessagesManager;

import java.util.List;

/**
 * Sous-menu de téléportation : Parkour et Spawn pour l'instant — facile à
 * enrichir avec d'autres destinations (mines, arène, etc.) en ajoutant des
 * items + des cases dans onClick.
 */
public class TeleportMenu implements Listener {

    private final Plugin2 plugin;
    private final MessagesManager messages;

    private static final String TITLE_KEY = "teleport-menu.titre";

    public TeleportMenu(Plugin2 plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, HubMenu.colored(messages.get(TITLE_KEY)));

        inv.setItem(11, HubMenu.buildItem(Material.LADDER,
                messages.get("teleport-menu.parkour"), List.of()));

        inv.setItem(13, HubMenu.buildItem(Material.GRASS_BLOCK,
                messages.get("teleport-menu.spawn"), List.of()));

        inv.setItem(22, HubMenu.buildItem(Material.ARROW,
                messages.get("teleport-menu.retour"), List.of()));

        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String invTitle = HubMenu.legacyTitle(event.getView().title());
        String expectedTitle = HubMenu.colored(messages.get(TITLE_KEY));
        if (!invTitle.equals(expectedTitle)) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;

        switch (clicked.getType()) {
            case LADDER -> {
                // Téléporte uniquement vers la zone "entree" du parkour, pas
                // directement sur le départ — le joueur doit ensuite marcher
                // jusqu'à la zone de départ pour lancer son chrono.
                plugin.getParkourManager().teleporterVersEntree(player, "parkour1");
                player.closeInventory();
            }
            case GRASS_BLOCK -> {
                // Utilise le spawn custom configuré dans hub-rules.yml (modifiable via /hub setspawn),
                // avec recherche automatique d'un sol sûr — évite de téléporter le joueur dans le sol
                // comme le faisait l'ancien player.getWorld().getSpawnLocation().
                plugin.getHubWorldManager().teleportToHub(player);
                player.closeInventory();
            }
            case ARROW -> plugin.getHubMenu().open(player);
            default -> { /* rien */ }
        }
    }
}