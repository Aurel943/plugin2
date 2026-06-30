package fr.aurel943.hub.listeners;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import fr.aurel943.hub.Hub;
import fr.aurel943.hub.world.HubWorldManager;

/**
 * Applique toutes les règles "joueur" du hub (dégâts, faim, casse/pose de
 * bloc, PVP...) lues depuis hub-rules.yml. Chaque règle ne s'applique QUE
 * si le joueur/le monde concerné est le monde du hub — les autres mondes
 * du serveur ne sont jamais affectés par ce listener.
 */
public class HubRulesListener implements Listener {

    private final Hub plugin;
    private final HubWorldManager hubWorldManager;

    public HubRulesListener(Hub plugin, HubWorldManager hubWorldManager) {
        this.plugin = plugin;
        this.hubWorldManager = hubWorldManager;
    }

    private YamlConfiguration config() {
        return hubWorldManager.getConfig();
    }

    // ---------------------------------------------------------------
    // Téléportation au hub à la connexion
    // ---------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Téléporte systématiquement le joueur dans le hub à sa connexion.
        // Si tu veux que ce comportement soit optionnel plus tard, on pourra
        // ajouter une clé "teleport-on-join" dans hub-rules.yml.
        hubWorldManager.teleportToHub(event.getPlayer());

        if (config().getBoolean("joueur.vol-autorise", false)) {
            event.getPlayer().setAllowFlight(true);
        }

        // La boss bar du hub (voir bossbar.yml) s'affiche dès l'arrivée dans le hub.
        plugin.getBossBarManager().subscribe(event.getPlayer());
    }

    /**
     * Abonne/désabonne la boss bar du hub quand un joueur change de monde
     * (ex: entre dans le hub depuis un autre monde, ou en sort). Couvre le
     * cas où d'autres plugins/commandes téléportent le joueur ailleurs que
     * via les chemins déjà gérés par ce plugin (HubCommand, TeleportMenu...).
     */
    @EventHandler
    public void onChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (hubWorldManager.isHubWorld(player.getWorld())) {
            plugin.getBossBarManager().subscribe(player);
        } else {
            plugin.getBossBarManager().unsubscribe(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getBossBarManager().unsubscribe(event.getPlayer());
    }

    // ---------------------------------------------------------------
    // Dégâts
    // ---------------------------------------------------------------

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!hubWorldManager.isHubWorld(player.getWorld())) return;

        if (!config().getBoolean("joueur.tous-degats", true)) {
            event.setCancelled(true);
            return;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                && !config().getBoolean("joueur.dommages-chute", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player)) return;
        if (!hubWorldManager.isHubWorld(victim.getWorld())) return;

        if (!config().getBoolean("joueur.pvp-autorise", false)) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------
    // Faim
    // ---------------------------------------------------------------

    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!hubWorldManager.isHubWorld(player.getWorld())) return;

        if (!config().getBoolean("joueur.faim-active", false)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20f);
        }
    }

    // ---------------------------------------------------------------
    // Casse / pose de bloc
    // ---------------------------------------------------------------

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!hubWorldManager.isHubWorld(event.getBlock().getWorld())) return;
        if (config().getBoolean("monde-fige.casse-bloc-interdite", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!hubWorldManager.isHubWorld(event.getBlock().getWorld())) return;
        if (config().getBoolean("monde-fige.pose-bloc-interdite", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!hubWorldManager.isHubWorld(event.getPlayer().getWorld())) return;
        if (config().getBoolean("monde-fige.interaction-bloquee", false)) {
            // On ne bloque que l'interaction avec les blocs (portes, leviers, coffres...),
            // pas l'interaction "vide" qui sert par ailleurs à ouvrir le menu de la boussole.
            if (event.getClickedBlock() != null) {
                event.setCancelled(true);
            }
        }
    }

    // ---------------------------------------------------------------
    // Météo
    // ---------------------------------------------------------------

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        if (!hubWorldManager.isHubWorld(event.getWorld())) return;
        if (config().getBoolean("monde.meteo-bloquee", true) && event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------
    // Items au sol (drop / pickup)
    // ---------------------------------------------------------------
    // Empêche tout item de traîner ou d'être récupéré dans le hub : pas de
    // drop volontaire (touche Q) et pas de ramassage d'un item déjà au sol
    // (drop d'un autre joueur, item issu d'un coffre cassé avant la règle
    // anti-casse, etc.). La boussole du hub a déjà sa propre protection
    // anti-drop dans CompassListener — ce handler couvre tous les autres items.

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if (!hubWorldManager.isHubWorld(event.getPlayer().getWorld())) return;
        if (config().getBoolean("monde-fige.drop-item-interdit", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickupItem(PlayerPickupItemEvent event) {
        if (!hubWorldManager.isHubWorld(event.getPlayer().getWorld())) return;
        if (config().getBoolean("monde-fige.pickup-item-interdit", true)) {
            event.setCancelled(true);
        }
    }
}