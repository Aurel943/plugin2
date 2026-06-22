package org.example.plugin2.menus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.EconomyManager;
import org.example.plugin2.economy.UpgradeManager;
import org.example.plugin2.messages.MessagesManager;

import java.util.List;
import java.util.Map;

/**
 * Boutique des améliorations achetées une seule fois et valables pour la vie
 * (contrairement aux pets, qui s'équipent/se déséquipent). Pour l'instant,
 * un seul article : le super-saut (double-tap espace). Facile à étendre :
 * ajoute un slot ici + un "case" dans onClick pour une nouvelle amélioration.
 */
public class UpgradeShopMenu implements Listener {

    private final Plugin2 plugin;
    private final MessagesManager messages;
    private final EconomyManager economy;
    private final UpgradeManager upgrades;

    private static final String TITLE_KEY = "upgrade-shop.titre";
    private static final int SLOT_SUPER_SAUT = 13;
    private static final int SLOT_RETOUR = 22;

    public UpgradeShopMenu(Plugin2 plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
        this.economy = plugin.getEconomyManager();
        this.upgrades = plugin.getUpgradeManager();
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, HubMenu.colored(messages.get(TITLE_KEY)));

        boolean possede = upgrades.has(player.getUniqueId(), UpgradeManager.SUPER_SAUT);
        double prix = plugin.getHubWorldManager().getConfig().getDouble("super-saut.prix", 250.0);

        List<String> lore;
        if (possede) {
            lore = List.of(messages.get("upgrade-shop.super-saut.lore-possede"));
        } else {
            lore = List.of(
                    messages.get("upgrade-shop.super-saut.lore-description"),
                    messages.get("upgrade-shop.super-saut.lore-prix", Map.of("prix", String.valueOf((int) prix))),
                    messages.get("upgrade-shop.super-saut.lore-cliquer-acheter")
            );
        }

        ItemStack item = HubMenu.buildItem(Material.RABBIT_FOOT,
                messages.get("upgrade-shop.super-saut.nom"), lore);
        if (possede) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        inv.setItem(SLOT_SUPER_SAUT, item);

        inv.setItem(SLOT_RETOUR, HubMenu.buildItem(Material.ARROW, messages.get("upgrade-shop.retour"), List.of()));

        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String invTitle = HubMenu.legacyTitle(event.getView().title());
        String expectedTitle = HubMenu.colored(messages.get(TITLE_KEY));
        if (!invTitle.equals(expectedTitle)) return;

        event.setCancelled(true);
        int slot = event.getRawSlot();

        if (slot == SLOT_RETOUR) {
            plugin.getHubMenu().open(player);
            return;
        }

        if (slot == SLOT_SUPER_SAUT) {
            handleSuperSautClick(player);
        }
    }

    private void handleSuperSautClick(Player player) {
        if (upgrades.has(player.getUniqueId(), UpgradeManager.SUPER_SAUT)) {
            messages.send(player, "upgrade-shop.super-saut.deja-possede");
            return;
        }

        double prix = plugin.getHubWorldManager().getConfig().getDouble("super-saut.prix", 250.0);
        boolean success = economy.removeBalance(player.getUniqueId(), prix);
        if (!success) {
            messages.send(player, "upgrade-shop.super-saut.solde-insuffisant",
                    Map.of("prix", String.valueOf((int) prix)));
            return;
        }

        upgrades.grant(player.getUniqueId(), UpgradeManager.SUPER_SAUT);
        messages.send(player, "upgrade-shop.super-saut.achat-reussi");

        // Rafraîchit le menu pour afficher l'état "déjà possédé"
        open(player);
    }
}