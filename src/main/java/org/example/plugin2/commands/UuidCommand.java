package org.example.plugin2.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.example.plugin2.Plugin2;
import org.example.plugin2.messages.MessagesManager;

import java.util.Map;

public class UuidCommand implements CommandExecutor {

    private final MessagesManager messages;

    public UuidCommand(Plugin2 plugin) {
        this.messages = plugin.getMessagesManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("plugin2.uuid")) {
            messages.send(sender, "coins.permission-refusee-admin");
            return true;
        }

        if (args.length != 1) {
            messages.send(sender, "uuid.usage");
            return true;
        }

        String pseudo = args[0];
        OfflinePlayer cible = Bukkit.getOfflinePlayer(pseudo);

        if (!cible.hasPlayedBefore() && !cible.isOnline()) {
            messages.send(sender, "uuid.joueur-inconnu", Map.of("pseudo", pseudo));
            return true;
        }

        String nomAffiche = cible.getName() != null ? cible.getName() : pseudo;
        String uuidString = cible.getUniqueId().toString();

        if (sender instanceof Player player) {
            // Message cliquable : clic = copie l'UUID dans le presse-papier
            String prefixLegacy = messages.get("uuid.resultat-prefixe", Map.of("pseudo", nomAffiche));
            Component prefix = LegacyComponentSerializer.legacySection().deserialize(prefixLegacy);

            Component uuidComponent = Component.text(uuidString, NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.copyToClipboard(uuidString))
                    .hoverEvent(HoverEvent.showText(Component.text("Clique pour copier", NamedTextColor.GRAY)));

            player.sendMessage(prefix.append(uuidComponent));
        } else {
            // Console : pas de clic possible, on garde le texte brut classique
            messages.send(sender, "uuid.resultat", Map.of(
                    "pseudo", nomAffiche,
                    "uuid", uuidString
            ));
        }

        return true;
    }
}