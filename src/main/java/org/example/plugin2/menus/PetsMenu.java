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
import org.example.plugin2.messages.MessagesManager;
import org.example.plugin2.pets.PetManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Menu d'achat et d'équipement des familiers (pets).
 * Les pets affichés viennent entièrement de pets.yml via PetManager —
 * ajouter un pet dans le YAML l'ajoute automatiquement ici, sans toucher au code.
 */
public class PetsMenu implements Listener {

    private final Plugin2 plugin;
    private final MessagesManager messages;
    private final PetManager pets;

    private static final String TITLE_KEY = "pets-menu.titre";

    // Associe chaque slot du menu actuellement ouvert à l'id du pet correspondant.
    // Recalculé à chaque ouverture — nécessaire car les pets sont placés dynamiquement.
    private final Map<Integer, String> slotToPetId = new HashMap<>();

    public PetsMenu(Plugin2 plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
        this.pets = plugin.getPetManager();
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, HubMenu.colored(messages.get(TITLE_KEY)));
        slotToPetId.clear();

        int slot = 0;
        for (PetManager.PetDefinition def : pets.getDefinitions().values()) {
            if (slot >= 36) break; // garde les 9 derniers slots pour la navigation

            boolean owned = pets.owns(player.getUniqueId(), def.id);
            boolean equipped = def.id.equals(pets.getActivePetId(player.getUniqueId()));

            List<String> lore = new ArrayList<>();
            if (owned) {
                lore.add(messages.get("pets-menu.item-lore-possede"));
                lore.add(equipped
                        ? messages.get("pets-menu.item-lore-cliquer-retirer")
                        : messages.get("pets-menu.item-lore-cliquer-equiper"));
            } else {
                lore.add(messages.get("pets-menu.item-lore-prix",
                        Map.of("prix", String.valueOf((int) def.prix))));
                lore.add(messages.get("pets-menu.item-lore-cliquer-acheter"));
            }

            ItemStack item = HubMenu.buildItem(def.icon, def.displayName, lore);
            if (equipped) {
                ItemMeta meta = item.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }

            inv.setItem(slot, item);
            slotToPetId.put(slot, def.id);
            slot++;
        }

        inv.setItem(40, HubMenu.buildItem(Material.ARROW, messages.get("pets-menu.retour"), List.of()));

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

        int slot = event.getRawSlot();

        if (clicked.getType() == Material.ARROW && slot == 40) {
            plugin.getHubMenu().open(player);
            return;
        }

        String petId = slotToPetId.get(slot);
        if (petId == null) return;

        handlePetClick(player, petId);
    }

    private void handlePetClick(Player player, String petId) {
        PetManager.PetDefinition def = pets.getDefinition(petId);
        if (def == null) return;

        boolean owned = pets.owns(player.getUniqueId(), petId);

        if (!owned) {
            // Tentative d'achat
            EconomyManager economy = plugin.getEconomyManager();
            boolean success = economy.removeBalance(player.getUniqueId(), def.prix);
            if (!success) {
                messages.send(player, "pets-menu.achat-solde-insuffisant",
                        Map.of("prix", String.valueOf((int) def.prix)));
                return;
            }
            pets.grant(player.getUniqueId(), petId);
            messages.send(player, "pets-menu.achat-reussi", Map.of("nom", HubMenu.colored(def.displayName)));
            pets.equip(player, petId);
        } else if (petId.equals(pets.getActivePetId(player.getUniqueId()))) {
            // Déjà équipé → on le retire
            pets.unequip(player);
            messages.send(player, "pets-menu.retire");
        } else {
            // Possédé mais pas équipé → on l'équipe
            pets.equip(player, petId);
            messages.send(player, "pets-menu.equipe", Map.of("nom", HubMenu.colored(def.displayName)));
        }

        // Rafraîchit le menu pour refléter le nouvel état (possédé/équipé)
        open(player);
    }
}