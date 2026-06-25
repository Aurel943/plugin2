package org.example.plugin2.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.example.plugin2.Plugin2;
import org.example.plugin2.cosmetics.CosmeticManager;

/**
 * Applique le tag de chat cosmétique équipé (cosmetics.yml, catégorie "tag")
 * devant le pseudo du joueur, sous la forme : {format}{pseudo} » {message}.
 *
 * Utilise AsyncChatEvent (API Paper, remplace l'ancien AsyncPlayerChatEvent
 * de Bukkit) qui travaille directement avec des Component Adventure plutôt
 * que des String — cohérent avec le reste du plugin qui utilise déjà
 * Adventure pour les titres de menus (voir HubMenu.legacyTitle).
 *
 * Le tag est lu directement en base à chaque message plutôt que mis en
 * cache : la fréquence des messages chat est largement compatible avec une
 * lecture MySQL, et ça évite un cache supplémentaire à invalider quand le
 * joueur change de tag dans CosmeticsMenu.
 */
public class TagChatListener implements Listener {

    private final Plugin2 plugin;

    public TagChatListener(Plugin2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        CosmeticManager cosmetics = plugin.getCosmeticManager();

        String equippedId = cosmetics.getEquipped(player.getUniqueId(), CosmeticManager.CATEGORY_TAG);
        if (equippedId == null) return; // aucun tag équipé : message inchangé

        CosmeticManager.TagDefinition def = cosmetics.getTag(equippedId);
        if (def == null || def.format.isEmpty()) return;

        String prefixLegacy = ChatColor.translateAlternateColorCodes('&', def.format);
        Component prefix = LegacyComponentSerializer.legacySection().deserialize(prefixLegacy);

        // Reconstruit le renderer du message : {prefix}{pseudo} » {message original}
        // On ne touche qu'à l'affichage final, pas au texte saisi par le joueur.
        event.renderer((source, sourceDisplayName, message, viewer) ->
                Component.empty()
                        .append(prefix)
                        .append(sourceDisplayName)
                        .append(Component.text(" » ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(message)
        );
    }
}