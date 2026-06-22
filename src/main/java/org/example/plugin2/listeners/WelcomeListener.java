package org.example.plugin2.listeners;

import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.example.plugin2.Plugin2;
import org.example.plugin2.messages.MessagesManager;

public class WelcomeListener implements Listener {

    private final Plugin2 plugin;

    public WelcomeListener(Plugin2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        MessagesManager messages = plugin.getMessagesManager();
        String playerName = event.getPlayer().getName();

        event.getPlayer().sendMessage(
                messages.get("welcome.join-private", java.util.Map.of("joueur", playerName))
        );

        String broadcastRaw = messages.get("welcome.join-broadcast", java.util.Map.of("joueur", playerName));
        event.joinMessage(Component.text(ChatColor.stripColor(broadcastRaw)));
        // Note : si tu veux la couleur dans le message de join broadcast aussi,
        // remplace la ligne du dessus par :
        // event.joinMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
        //         .legacySection().deserialize(broadcastRaw));
    }
}