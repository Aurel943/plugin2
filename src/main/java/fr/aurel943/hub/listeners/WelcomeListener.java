package fr.aurel943.hub.listeners;

import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import fr.aurel943.hub.Hub;
import fr.aurel943.hub.messages.MessagesManager;

public class WelcomeListener implements Listener {

    private final Hub plugin;

    public WelcomeListener(Hub plugin) {
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