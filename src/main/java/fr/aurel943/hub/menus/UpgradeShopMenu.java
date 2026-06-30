package fr.aurel943.hub.menus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import fr.aurel943.hub.Hub;
import fr.aurel943.hub.economy.EconomyManager;
import fr.aurel943.hub.economy.UpgradeManager;
import fr.aurel943.hub.messages.MessagesManager;

import java.util.List;
import java.util.Map;

/**
 * Boutique des améliorations achetées une seule fois et valables pour la vie
 * (contrairement aux pets, qui s'équipent/se déséquipent). Pour l'instant,
 * un seul article : le super-saut (double-tap espace). Facile à étendre :
 * ajoute un slot ici + un "case" dans onClick pour une nouvelle amélioration.
 */
public class UpgradeShopMenu implements Listener {

    private final Hub plugin;
    private final MessagesManager messages;
    private final EconomyManager economy;
    private final UpgradeManager upgrades;

    private static final String TITLE_KEY = "upgrade-shop.titre";
    private static final int SLOT_SUPER_SAUT = 13;
    private static final int SLOT_RETOUR = 22;

    public UpgradeShopMenu(Hub plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
        this.economy = plugin.getEconomyManager();
        this.upgrades = plugin.getUpgradeManager();
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, HubMenu.colored(messages.get(TITLE_KEY)));

        boolean possede = upgrades.has(player.getUniqueId(), UpgradeManager.SUPER_SAUT);
        boolean active = possede && upgrades.isEnabled(player.getUniqueId(), UpgradeManager.SUPER_SAUT);
        double prix = plugin.getHubWorldManager().getConfig().getDouble("super-saut.prix", 250.0);

        List<String> lore;
        if (possede) {
            String cle = active ? "upgrade-shop.super-saut.lore-active" : "upgrade-shop.super-saut.lore-desactive";
            lore = List.of(
                    messages.get(cle),
                    messages.get("upgrade-shop.super-saut.lore-cliquer-toggle")
            );
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
            meta.setEnchantmentGlintOverride(active);
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
            boolean nouvelEtat = upgrades.toggle(player.getUniqueId(), UpgradeManager.SUPER_SAUT);

            String cle = nouvelEtat ? "upgrade-shop.super-saut.toggle-active" : "upgrade-shop.super-saut.toggle-desactive";
            messages.send(player, cle);

            // Son discret de confirmation : un clic plus aigu pour "activé", plus grave pour "désactivé".
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, nouvelEtat ? 1.4f : 0.8f);

            open(player);
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

        // Rafraîchit le menu pour afficher l'état "activé" par défaut après achat
        open(player);
    }
}