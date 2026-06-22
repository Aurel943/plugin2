package org.example.plugin2.listeners;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
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
import org.bukkit.util.Vector;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.EconomyManager;
import org.example.plugin2.messages.MessagesManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère la boussole spéciale du hub :
 * - donnée automatiquement à la connexion (et à la réapparition)
 * - impossible à drop
 * - ouvre le HubMenu au clic droit
 * - clic gauche = saut payant (voir onArmSwing)
 *
 * On marque l'item avec une PersistentDataTag pour le reconnaître de façon fiable
 * (plutôt que de se baser uniquement sur le Material, au cas où le joueur ait
 * une autre boussole en main pour une autre raison).
 */
public class CompassListener implements Listener {

    public static final NamespacedKey HUB_COMPASS_KEY = new NamespacedKey("plugin2", "hub_compass");

    private final Plugin2 plugin;
    private final MessagesManager messages;
    private final EconomyManager economy;

    // Anti-spam : timestamp (millis) du dernier saut payant par joueur.
    private final Map<UUID, Long> derniersSauts = new HashMap<>();

    public CompassListener(Plugin2 plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
        this.economy = plugin.getEconomyManager();
    }

    /** Construit l'ItemStack de la boussole du hub, marqué et nommé. */
    public static ItemStack createHubCompass(MessagesManager messages) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        meta.displayName(Component.text(
                ChatColor.translateAlternateColorCodes('&', "&b&lMenu du Hub")
        ));
        meta.getPersistentDataContainer().set(HUB_COMPASS_KEY, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);
        return compass;
    }

    public static boolean isHubCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) return false;
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
            player.getInventory().addItem(createHubCompass(messages));
            messages.send(player, "compass.recu");
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

    // ---------------------------------------------------------------
    // Saut payant (clic gauche avec la boussole en main)
    // ---------------------------------------------------------------
    // PlayerArmSwingEvent (Paper) se déclenche au clic gauche que le joueur
    // vise du vide, un bloc ou une entité — contrairement à PlayerInteractEvent
    // qui n'existe pas pour un clic gauche dans le vide côté API Bukkit standard.

    @EventHandler
    public void onArmSwing(PlayerArmSwingEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isHubCompass(item)) return;

        var config = plugin.getHubWorldManager().getConfig();
        if (!config.getBoolean("saut.active", true)) return;
        if (!plugin.getHubWorldManager().isHubWorld(player.getWorld())) return;

        double prix = config.getDouble("saut.prix", 10.0);
        long cooldownMs = (long) (config.getDouble("saut.cooldown-secondes", 2.0) * 1000);

        long maintenant = System.currentTimeMillis();
        Long dernier = derniersSauts.get(player.getUniqueId());
        if (dernier != null && (maintenant - dernier) < cooldownMs) {
            return; // encore en cooldown, on ignore silencieusement (pas de spam de messages)
        }

        if (!economy.has(player.getUniqueId(), prix)) {
            messages.send(player, "saut.solde-insuffisant", Map.of("prix", String.valueOf((int) prix)));
            return;
        }

        economy.removeBalance(player.getUniqueId(), prix);
        derniersSauts.put(player.getUniqueId(), maintenant);

        performJump(player, config);
        messages.send(player, "saut.effectue", Map.of("prix", String.valueOf((int) prix)));
    }

    /** Applique l'impulsion verticale et joue le son + les particules autour du joueur. */
    private void performJump(Player player, org.bukkit.configuration.file.YamlConfiguration config) {
        double puissance = config.getDouble("saut.puissance", 1.6);

        Vector velocity = player.getVelocity();
        velocity.setY(puissance);
        player.setVelocity(velocity);

        Location loc = player.getLocation();
        player.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.4f);
        player.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0, 0.1, 0), 30, 0.4, 0.1, 0.4, 0.02);
        player.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.5, 0), 12, 0.3, 0.3, 0.3, 0.05);
    }
}