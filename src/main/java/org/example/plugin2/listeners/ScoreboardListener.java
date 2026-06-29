package org.example.plugin2.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.example.plugin2.Plugin2;
import org.example.plugin2.scoreboard.ScoreboardManager;

/**
 * Affiche ou retire le scoreboard latéral selon le monde dans lequel se
 * trouve le joueur (mondes listés dans scoreboard.yml > mondes-actifs).
 * Suit le même principe que HubRulesListener pour l'abonnement à la boss
 * bar : on réagit au join, au changement de monde, et au quit.
 *
 * Ne gère pas le RAFRAÎCHISSEMENT périodique (heure, Cristaux, nombre de
 * joueurs en ligne) — ça, c'est le rôle de la tâche planifiée démarrée
 * dans Plugin2.onEnable() (voir ScoreboardManager.update() appelé en boucle).
 */
public class ScoreboardListener implements Listener {

    private final ScoreboardManager scoreboardManager;

    public ScoreboardListener(Plugin2 plugin) {
        this.scoreboardManager = plugin.getScoreboardManager();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (scoreboardManager.estDansMondeActif(player.getWorld())) {
            scoreboardManager.afficher(player);
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (scoreboardManager.estDansMondeActif(player.getWorld())) {
            scoreboardManager.afficher(player);
        } else {
            scoreboardManager.retirer(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        scoreboardManager.clearOnQuit(event.getPlayer());
    }
}