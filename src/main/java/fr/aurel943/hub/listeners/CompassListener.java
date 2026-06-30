package fr.aurel943.hub.listeners;

import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import fr.aurel943.hub.Hub;
import fr.aurel943.hub.cosmetics.CosmeticManager;
import fr.aurel943.hub.messages.MessagesManager;

/**
 * Gère la boussole spéciale du hub :
 * - donnée automatiquement à la connexion (et à la réapparition)
 * - impossible à drop
 * - ouvre le HubMenu au clic droit
 * - son apparence (matériau + nom) suit le skin cosmétique équipé par le
 *   joueur (compass-skins dans cosmetics.yml), tout en restant reconnaissable
 *   par le plugin grâce au PersistentDataTag — voir isHubCompass().
 *
 * Le "super saut" (double-tap espace) n'est plus lié à cet item : c'est une
 * amélioration achetée une fois pour toutes, valable même sans avoir la
 * boussole en main — voir SuperSautListener et UpgradeManager.
 *
 * On marque l'item avec une PersistentDataTag pour le reconnaître de façon fiable
 * (plutôt que de se baser uniquement sur le Material, ce qui est d'autant plus
 * nécessaire maintenant que le matériau peut changer selon le skin équipé).
 */
public class CompassListener implements Listener {

    // Namespace technique volontairement laissé à "plugin2" malgré le renommage du
    // plugin en "Hub" : c'est une clé NBT invisible aux joueurs, la changer casserait
    // les items déjà en possession des joueurs (ils ne seraient plus reconnus par le code).
    public static final NamespacedKey HUB_COMPASS_KEY = new NamespacedKey("plugin2", "hub_compass");

    private final Hub plugin;
    private final MessagesManager messages;

    public CompassListener(Hub plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
    }

    /**
     * Construit l'ItemStack de la boussole du hub, marqué et nommé, en tenant
     * compte du skin cosmétique équipé par CE joueur (matériau + nom). Si le
     * joueur n'a aucun skin équipé (ou que le plugin n'a pas encore chargé
     * CosmeticManager), retombe sur l'apparence par défaut (COMPASS classique).
     */
    public ItemStack createHubCompass(Player player) {
        Material material = Material.COMPASS;
        String displayName = "&b&lMenu du Hub";

        CosmeticManager cosmetics = plugin.getCosmeticManager();
        if (cosmetics != null) {
            String equippedId = cosmetics.getEquipped(player.getUniqueId(), CosmeticManager.CATEGORY_COMPASS_SKIN);
            if (equippedId != null) {
                CosmeticManager.CompassSkinDefinition skin = cosmetics.getCompassSkin(equippedId);
                if (skin != null) {
                    material = skin.material;
                    displayName = skin.displayName;
                }
            }
        }

        ItemStack compass = new ItemStack(material);
        ItemMeta meta = compass.getItemMeta();
        meta.displayName(Component.text(ChatColor.translateAlternateColorCodes('&', displayName)));
        meta.getPersistentDataContainer().set(HUB_COMPASS_KEY, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);
        return compass;
    }

    public static boolean isHubCompass(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(HUB_COMPASS_KEY, PersistentDataType.BYTE);
    }

    /** Donne la boussole au joueur s'il n'en possède pas déjà une. */
    public void giveCompassIfMissing(Player player) {
        boolean hasCompass = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isHubCompass(item)) {
                hasCompass = true;
                break;
            }
        }
        if (!hasCompass) {
            player.getInventory().addItem(createHubCompass(player));
            messages.send(player, "compass.recu");
        }
    }

    /**
     * Remplace la boussole actuelle du joueur par une version à jour (nouveau
     * matériau/nom) après un changement de skin équipé dans le menu cosmétiques.
     * Conserve la position dans l'inventaire ; ne fait rien si le joueur n'a
     * pas (ou plus) la boussole sur lui.
     */
    public void refreshCompassAppearance(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isHubCompass(contents[slot])) {
                player.getInventory().setItem(slot, createHubCompass(player));
                return; // une seule boussole du hub à la fois, pas besoin de continuer
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        giveCompassIfMissing(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // Au cas où la boussole se perde au respawn (selon les règles de ton serveur, ex: keepInventory=false)
        giveCompassIfMissing(event.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isHubCompass(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "compass.drop-interdit");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // On ne réagit qu'au clic droit (évite de double-déclencher avec un clic gauche)
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // Évite de déclencher deux fois pour une interaction à deux mains
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        ItemStack item = event.getItem();
        if (isHubCompass(item)) {
            event.setCancelled(true); // empêche d'ouvrir une boussole "lodestone" classique ou autre comportement
            plugin.getHubMenu().open(event.getPlayer());
        }
    }
}