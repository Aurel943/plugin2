package org.example.plugin2.ranks;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.example.plugin2.Plugin2;

/**
 * Applique le rank (et ses permissions) à la connexion, et nettoie l'état en
 * mémoire à la déconnexion.
 *
 * Profite aussi de cet évènement pour invalider le cache économie du joueur
 * (EconomyManager.invalidate) : indispensable dès qu'il y aura plusieurs
 * serveurs derrière BungeeCord/Velocity, pour éviter qu'un serveur affiche un
 * solde périmé si le joueur a gagné/dépensé des cristaux sur un autre
 * serveur juste avant de changer de serveur. Sans BungeeCord, cet appel est
 * inoffensif (le cache était déjà à jour de toute façon).
 */
public class RankJoinListener implements Listener {

    private final Plugin2 plugin;

    public RankJoinListener(Plugin2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getEconomyManager().invalidate(event.getPlayer().getUniqueId());
        plugin.getRankManager().applyRank(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getRankManager().clearOnQuit(event.getPlayer());
    }
}