package org.example.plugin2.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.Database;

/**
 * Applique le prefix du rank actif devant le pseudo du joueur dans le chat,
 * sous la forme : {prefix} {pseudo} » {message}.
 *
 * Remplace l'ancien système de tags cosmétiques (CosmeticManager.CATEGORY_TAG,
 * désormais supprimé) : un seul système d'identité visuelle (le rank) plutôt
 * que deux qui se chevauchaient.
 */
public class TagChatListener implements Listener {

    private final Plugin2 plugin;

    public TagChatListener(Plugin2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        Database.RankData rank = plugin.getRankManager().getPlayerRank(player.getUniqueId());
        if (rank == null || rank.prefix.isEmpty()) return; // rank par défaut sans prefix : message inchangé

        String prefixLegacy = ChatColor.translateAlternateColorCodes('&', rank.prefix);
        Component prefix = LegacyComponentSerializer.legacySection().deserialize(prefixLegacy);

        event.renderer((source, sourceDisplayName, message, viewer) ->
                Component.empty()
                        .append(prefix)
                        .append(Component.text(" "))
                        .append(sourceDisplayName)
                        .append(Component.text(" » ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(message)
        );
    }
}