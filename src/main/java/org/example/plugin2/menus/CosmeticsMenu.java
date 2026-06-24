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
import org.example.plugin2.cosmetics.CosmeticManager;
import org.example.plugin2.economy.EconomyManager;
import org.example.plugin2.messages.MessagesManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Menu des cosmétiques : un écran d'accueil avec 3 onglets (trails, tags,
 * skins de boussole), chacun listant les cosmétiques de cosmetics.yml avec
 * le même pattern achat/équipement que PetsMenu (possédé → cliquer équipe/
 * déséquipe ; non possédé → cliquer achète puis équipe directement).
 *
 * Comme PetsMenu, tout le contenu vient de cosmetics.yml via CosmeticManager :
 * ajouter un cosmétique dans le YAML l'ajoute automatiquement ici.
 */
public class CosmeticsMenu implements Listener {

    private final Plugin2 plugin;
    private final MessagesManager messages;
    private final CosmeticManager cosmetics;

    private static final String TITLE_ACCUEIL = "cosmetics-menu.titre-accueil";
    private static final String TITLE_TRAILS = "cosmetics-menu.titre-trails";
    private static final String TITLE_TAGS = "cosmetics-menu.titre-tags";
    private static final String TITLE_SKINS = "cosmetics-menu.titre-skins";

    private static final int SLOT_ACCUEIL_TRAILS = 11;
    private static final int SLOT_ACCUEIL_TAGS = 13;
    private static final int SLOT_ACCUEIL_SKINS = 15;
    private static final int SLOT_ACCUEIL_RETOUR = 22;
    private static final int SLOT_RETOUR_SOUS_MENU = 40;

    // Associe chaque slot du sous-menu actuellement ouvert à l'id du cosmétique
    // correspondant. Recalculé à chaque ouverture (placement dynamique).
    private final Map<Integer, String> slotToCosmeticId = new HashMap<>();

    public CosmeticsMenu(Plugin2 plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
        this.cosmetics = plugin.getCosmeticManager();
    }

    // ---------------------------------------------------------------
    // Écran d'accueil : choix de la catégorie
    // ---------------------------------------------------------------

    public void openAccueil(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, HubMenu.colored(messages.get(TITLE_ACCUEIL)));

        inv.setItem(SLOT_ACCUEIL_TRAILS, HubMenu.buildItem(Material.FIRE_CHARGE,
                messages.get("cosmetics-menu.bouton-trails"),
                List.of(messages.get("cosmetics-menu.bouton-trails-lore"))));

        inv.setItem(SLOT_ACCUEIL_TAGS, HubMenu.buildItem(Material.NAME_TAG,
                messages.get("cosmetics-menu.bouton-tags"),
                List.of(messages.get("cosmetics-menu.bouton-tags-lore"))));

        inv.setItem(SLOT_ACCUEIL_SKINS, HubMenu.buildItem(Material.COMPASS,
                messages.get("cosmetics-menu.bouton-skins"),
                List.of(messages.get("cosmetics-menu.bouton-skins-lore"))));

        inv.setItem(SLOT_ACCUEIL_RETOUR, HubMenu.buildItem(Material.ARROW,
                messages.get("cosmetics-menu.retour"), List.of()));

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------
    // Sous-menu : trails
    // ---------------------------------------------------------------

    public void openTrails(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, HubMenu.colored(messages.get(TITLE_TRAILS)));
        slotToCosmeticId.clear();

        int slot = 0;
        for (CosmeticManager.TrailDefinition def : cosmetics.getTrailDefinitions().values()) {
            if (slot >= 36) break;

            boolean owned = cosmetics.owns(player.getUniqueId(), CosmeticManager.CATEGORY_TRAIL, def.id);
            boolean equipped = def.id.equals(cosmetics.getEquipped(player.getUniqueId(), CosmeticManager.CATEGORY_TRAIL));

            ItemStack item = buildCosmeticItem(def.icon, def.displayName, def.prix, owned, equipped);
            inv.setItem(slot, item);
            slotToCosmeticId.put(slot, def.id);
            slot++;
        }

        inv.setItem(SLOT_RETOUR_SOUS_MENU, HubMenu.buildItem(Material.ARROW, messages.get("cosmetics-menu.retour"), List.of()));
        player.openInventory(inv);
    }

    // ---------------------------------------------------------------
    // Sous-menu : tags de chat
    // ---------------------------------------------------------------

    public void openTags(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, HubMenu.colored(messages.get(TITLE_TAGS)));
        slotToCosmeticId.clear();

        int slot = 0;
        for (CosmeticManager.TagDefinition def : cosmetics.getTagDefinitions().values()) {
            if (slot >= 36) break;

            boolean owned = cosmetics.owns(player.getUniqueId(), CosmeticManager.CATEGORY_TAG, def.id);
            boolean equipped = def.id.equals(cosmetics.getEquipped(player.getUniqueId(), CosmeticManager.CATEGORY_TAG));

            List<String> lore = new ArrayList<>();
            lore.add(messages.get("cosmetics-menu.tag-apercu", Map.of("apercu", HubMenu.colored(def.format) + "Pseudo")));
            lore.addAll(buildStatusLore(def.prix, owned, equipped));

            ItemStack item = HubMenu.buildItem(def.icon, def.displayName, lore);
            if (equipped) glow(item);

            inv.setItem(slot, item);
            slotToCosmeticId.put(slot, def.id);
            slot++;
        }

        inv.setItem(SLOT_RETOUR_SOUS_MENU, HubMenu.buildItem(Material.ARROW, messages.get("cosmetics-menu.retour"), List.of()));
        player.openInventory(inv);
    }

    // ---------------------------------------------------------------
    // Sous-menu : skins de boussole
    // ---------------------------------------------------------------

    public void openSkins(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, HubMenu.colored(messages.get(TITLE_SKINS)));
        slotToCosmeticId.clear();

        int slot = 0;
        for (CosmeticManager.CompassSkinDefinition def : cosmetics.getCompassSkinDefinitions().values()) {
            if (slot >= 36) break;

            boolean owned = cosmetics.owns(player.getUniqueId(), CosmeticManager.CATEGORY_COMPASS_SKIN, def.id);
            boolean equipped = def.id.equals(cosmetics.getEquipped(player.getUniqueId(), CosmeticManager.CATEGORY_COMPASS_SKIN));

            ItemStack item = buildCosmeticItem(def.icon, def.displayName, def.prix, owned, equipped);
            inv.setItem(slot, item);
            slotToCosmeticId.put(slot, def.id);
            slot++;
        }

        inv.setItem(SLOT_RETOUR_SOUS_MENU, HubMenu.buildItem(Material.ARROW, messages.get("cosmetics-menu.retour"), List.of()));
        player.openInventory(inv);
    }

    // ---------------------------------------------------------------
    // Construction d'item générique (trails et skins partagent la même lore)
    // ---------------------------------------------------------------

    private ItemStack buildCosmeticItem(Material icon, String displayName, double prix, boolean owned, boolean equipped) {
        List<String> lore = buildStatusLore(prix, owned, equipped);
        ItemStack item = HubMenu.buildItem(icon, displayName, lore);
        if (equipped) glow(item);
        return item;
    }

    private List<String> buildStatusLore(double prix, boolean owned, boolean equipped) {
        List<String> lore = new ArrayList<>();
        if (owned) {
            lore.add(messages.get("cosmetics-menu.item-lore-possede"));
            lore.add(equipped
                    ? messages.get("cosmetics-menu.item-lore-cliquer-retirer")
                    : messages.get("cosmetics-menu.item-lore-cliquer-equiper"));
        } else {
            lore.add(messages.get("cosmetics-menu.item-lore-prix", Map.of("prix", String.valueOf((int) prix))));
            lore.add(messages.get("cosmetics-menu.item-lore-cliquer-acheter"));
        }
        return lore;
    }

    private void glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    // ---------------------------------------------------------------
    // Gestion des clics
    // ---------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String invTitle = HubMenu.legacyTitle(event.getView().title());

        if (invTitle.equals(HubMenu.colored(messages.get(TITLE_ACCUEIL)))) {
            handleAccueilClick(event, player);
        } else if (invTitle.equals(HubMenu.colored(messages.get(TITLE_TRAILS)))) {
            handleSousMenuClick(event, player, CosmeticManager.CATEGORY_TRAIL, this::openTrails);
        } else if (invTitle.equals(HubMenu.colored(messages.get(TITLE_TAGS)))) {
            handleSousMenuClick(event, player, CosmeticManager.CATEGORY_TAG, this::openTags);
        } else if (invTitle.equals(HubMenu.colored(messages.get(TITLE_SKINS)))) {
            handleSousMenuClick(event, player, CosmeticManager.CATEGORY_COMPASS_SKIN, this::openSkins);
        }
        // Sinon : pas notre menu, on ignore
    }

    private void handleAccueilClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        int slot = event.getRawSlot();

        switch (slot) {
            case SLOT_ACCUEIL_TRAILS -> openTrails(player);
            case SLOT_ACCUEIL_TAGS -> openTags(player);
            case SLOT_ACCUEIL_SKINS -> openSkins(player);
            case SLOT_ACCUEIL_RETOUR -> plugin.getHubMenu().open(player);
            default -> { /* rien */ }
        }
    }

    /** Logique de clic commune aux trois sous-menus : seule la catégorie et le rafraîchissement changent. */
    private void handleSousMenuClick(InventoryClickEvent event, Player player, String category, java.util.function.Consumer<Player> reopen) {
        event.setCancelled(true);
        int slot = event.getRawSlot();

        if (slot == SLOT_RETOUR_SOUS_MENU) {
            openAccueil(player);
            return;
        }

        String cosmeticId = slotToCosmeticId.get(slot);
        if (cosmeticId == null) return;

        handleCosmeticClick(player, category, cosmeticId);
        reopen.accept(player);
    }

    private void handleCosmeticClick(Player player, String category, String cosmeticId) {
        double prix = getPrix(category, cosmeticId);
        boolean owned = cosmetics.owns(player.getUniqueId(), category, cosmeticId);
        String displayName = getDisplayName(category, cosmeticId);

        if (!owned) {
            EconomyManager economy = plugin.getEconomyManager();
            boolean success = economy.removeBalance(player.getUniqueId(), prix);
            if (!success) {
                messages.send(player, "cosmetics-menu.achat-solde-insuffisant",
                        Map.of("prix", String.valueOf((int) prix)));
                return;
            }
            cosmetics.grant(player.getUniqueId(), category, cosmeticId);
            messages.send(player, "cosmetics-menu.achat-reussi", Map.of("nom", HubMenu.colored(displayName)));
            equip(player, category, cosmeticId);
        } else if (cosmeticId.equals(cosmetics.getEquipped(player.getUniqueId(), category))) {
            unequip(player, category);
            messages.send(player, "cosmetics-menu.retire");
        } else {
            equip(player, category, cosmeticId);
            messages.send(player, "cosmetics-menu.equipe", Map.of("nom", HubMenu.colored(displayName)));
        }
    }

    /** Équipe un cosmétique : met à jour la base ET applique l'effet en jeu (trail/tag/skin). */
    private void equip(Player player, String category, String cosmeticId) {
        cosmetics.setEquipped(player.getUniqueId(), category, cosmeticId);

        if (category.equals(CosmeticManager.CATEGORY_TRAIL)) {
            plugin.getTrailEngine().start(player, cosmeticId);
        } else if (category.equals(CosmeticManager.CATEGORY_COMPASS_SKIN)) {
            plugin.getCompassListener().refreshCompassAppearance(player);
        }
        // Les tags (CATEGORY_TAG) sont lus directement depuis la base au moment
        // d'envoyer un message de chat — pas d'effet "en jeu" immédiat à appliquer ici.
    }

    private void unequip(Player player, String category) {
        cosmetics.setEquipped(player.getUniqueId(), category, null);

        if (category.equals(CosmeticManager.CATEGORY_TRAIL)) {
            plugin.getTrailEngine().stop(player.getUniqueId());
        } else if (category.equals(CosmeticManager.CATEGORY_COMPASS_SKIN)) {
            plugin.getCompassListener().refreshCompassAppearance(player);
        }
    }

    private double getPrix(String category, String cosmeticId) {
        if (category.equals(CosmeticManager.CATEGORY_TRAIL)) {
            CosmeticManager.TrailDefinition def = cosmetics.getTrail(cosmeticId);
            return def != null ? def.prix : 0;
        } else if (category.equals(CosmeticManager.CATEGORY_TAG)) {
            CosmeticManager.TagDefinition def = cosmetics.getTag(cosmeticId);
            return def != null ? def.prix : 0;
        } else {
            CosmeticManager.CompassSkinDefinition def = cosmetics.getCompassSkin(cosmeticId);
            return def != null ? def.prix : 0;
        }
    }

    private String getDisplayName(String category, String cosmeticId) {
        if (category.equals(CosmeticManager.CATEGORY_TRAIL)) {
            CosmeticManager.TrailDefinition def = cosmetics.getTrail(cosmeticId);
            return def != null ? def.displayName : cosmeticId;
        } else if (category.equals(CosmeticManager.CATEGORY_TAG)) {
            CosmeticManager.TagDefinition def = cosmetics.getTag(cosmeticId);
            return def != null ? def.displayName : cosmeticId;
        } else {
            CosmeticManager.CompassSkinDefinition def = cosmetics.getCompassSkin(cosmeticId);
            return def != null ? def.displayName : cosmeticId;
        }
    }
}