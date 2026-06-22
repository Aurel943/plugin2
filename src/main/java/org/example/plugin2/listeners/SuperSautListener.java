package org.example.plugin2.listeners;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.UpgradeManager;
import org.example.plugin2.messages.MessagesManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Détecte le double-tap de la touche espace (= deux PlayerJumpEvent rapprochés)
 * et déclenche le "super saut" si le joueur a acheté l'amélioration correspondante
 * (achat unique, valable pour la vie — voir UpgradeManager).
 *
 * Le super saut propulse le joueur verticalement ET horizontalement dans la
 * direction où il regarde, avec un son et des particules. Contrairement à
 * l'ancien système, il ne coûte rien à l'usage : seul l'achat initial coûte
 * des tals (voir UpgradeShopMenu).
 */
public class SuperSautListener implements Listener {

    private final Plugin2 plugin;
    private final MessagesManager messages;
    private final UpgradeManager upgrades;

    // Timestamp (millis) du dernier saut simple détecté par joueur,
    // utilisé pour repérer un second saut rapproché (= double-tap).
    private final Map<UUID, Long> dernierSautSimple = new HashMap<>();

    // Anti-spam séparé pour le super saut lui-même, une fois déclenché
    // (évite qu'un triple/quadruple saut rapide ne le redéclenche en boucle).
    private final Map<UUID, Long> dernierSuperSaut = new HashMap<>();

    public SuperSautListener(Plugin2 plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
        this.upgrades = plugin.getUpgradeManager();
    }

    @EventHandler
    public void onJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getHubWorldManager().isHubWorld(player.getWorld())) return;
        if (!upgrades.has(player.getUniqueId(), UpgradeManager.SUPER_SAUT)) return;

        var config = plugin.getHubWorldManager().getConfig();
        if (!config.getBoolean("super-saut.active", true)) return;

        long maintenant = System.currentTimeMillis();
        long fenetreMs = (long) (config.getDouble("super-saut.fenetre-double-tap-secondes", 0.4) * 1000);
        long cooldownMs = (long) (config.getDouble("super-saut.cooldown-secondes", 1.5) * 1000);

        UUID uuid = player.getUniqueId();

        // Vérifie le cooldown du super saut lui-même, pour ne pas le redéclencher
        // immédiatement si plusieurs sauts rapprochés s'enchaînent.
        Long dernierSuper = dernierSuperSaut.get(uuid);
        if (dernierSuper != null && (maintenant - dernierSuper) < cooldownMs) {
            dernierSautSimple.put(uuid, maintenant);
            return;
        }

        Long dernierSimple = dernierSautSimple.get(uuid);
        dernierSautSimple.put(uuid, maintenant);

        boolean estDoubleTap = dernierSimple != null && (maintenant - dernierSimple) < fenetreMs;
        if (!estDoubleTap) return;

        dernierSuperSaut.put(uuid, maintenant);
        performSuperSaut(player, config);
    }

    /** Applique l'impulsion verticale + horizontale, le son et les particules. */
    private void performSuperSaut(Player player, org.bukkit.configuration.file.YamlConfiguration config) {
        double puissanceVerticale = config.getDouble("super-saut.puissance-verticale", 1.2);
        double puissanceHorizontale = config.getDouble("super-saut.puissance-horizontale", 1.0);

        // Direction horizontale = vers où le joueur regarde (yaw uniquement, pas le pitch,
        // pour ne pas projeter le joueur vers le sol/ciel s'il regarde en haut/bas).
        Location loc = player.getLocation();
        double yawRad = Math.toRadians(loc.getYaw());
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);

        Vector impulsion = new Vector(dirX * puissanceHorizontale, puissanceVerticale, dirZ * puissanceHorizontale);
        player.setVelocity(impulsion);

        player.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.4f);
        player.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0, 0.1, 0), 30, 0.4, 0.1, 0.4, 0.02);
        player.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.5, 0), 12, 0.3, 0.3, 0.3, 0.05);

        messages.send(player, "super-saut.effectue");
    }
}