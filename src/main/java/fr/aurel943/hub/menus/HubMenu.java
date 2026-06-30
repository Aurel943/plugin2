package fr.aurel943.hub.menus;

import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import fr.aurel943.hub.Hub;
import fr.aurel943.hub.messages.MessagesManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu principal ouvert par la boussole du hub.
 * Conçu pour être facilement étendu : ajoute un bouton ici, un nouveau
 * sous-menu dans sa propre classe, et un "case" dans onClick.
 */
public class HubMenu implements Listener {

    private final Hub plugin;
    private final MessagesManager messages;

    // Identifiant du menu, utilisé pour reconnaître les clics dans onClick
    private static final String TITLE_KEY = "hub-menu.titre";

    public HubMenu(Hub plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
    }

    public void open(Player player) {
        Inventory inv = org.bukkit.Bukkit.createInventory(null, 27, colored(messages.get(TITLE_KEY)));

        inv.setItem(10, buildItem(Material.ENDER_PEARL,
                messages.get("hub-menu.bouton-teleport"),
                List.of(messages.get("hub-menu.bouton-teleport-lore"))));

        inv.setItem(12, buildItem(Material.NETHER_STAR,
                messages.get("hub-menu.bouton-ameliorations"),
                List.of(messages.get("hub-menu.bouton-ameliorations-lore"))));

        inv.setItem(14, buildItem(Material.BONE,
                messages.get("hub-menu.bouton-pets"),
                List.of(messages.get("hub-menu.bouton-pets-lore"))));

        inv.setItem(16, buildItem(Material.FEATHER,
                messages.get("hub-menu.bouton-cosmetiques"),
                List.of(messages.get("hub-menu.bouton-cosmetiques-lore"))));

        inv.setItem(22, buildItem(Material.BARRIER,
                messages.get("hub-menu.bouton-fermer"),
                List.of()));

        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String invTitle = legacyTitle(event.getView().title());
        String expectedTitle = colored(messages.get(TITLE_KEY));

        if (!invTitle.equals(expectedTitle)) return; // pas notre menu, on ignore

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;

        switch (clicked.getType()) {
            case ENDER_PEARL -> plugin.getTeleportMenu().open(player);
            case NETHER_STAR -> plugin.getUpgradeShopMenu().open(player);
            case BONE -> plugin.getPetsMenu().open(player);
            case FEATHER -> plugin.getCosmeticsMenu().openAccueil(player);
            case BARRIER -> player.closeInventory();
            default -> { /* rien */ }
        }
    }

    // ---------------------------------------------------------------
    // Utilitaires partagés (réutilisés aussi par PetsMenu / TeleportMenu)
    // ---------------------------------------------------------------

    static ItemStack buildItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(colored(name));
        List<String> coloredLore = new ArrayList<>();
        for (String line : lore) {
            coloredLore.add(colored(line));
        }
        meta.setLore(coloredLore);
        item.setItemMeta(meta);
        return item;
    }

    static String colored(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Convertit le titre Component d'un inventaire en String legacy comparable. */
    static String legacyTitle(Component component) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(component);
    }
}