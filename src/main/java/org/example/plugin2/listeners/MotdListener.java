package org.example.plugin2.listeners;

import org.bukkit.event.server.ServerListPingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.example.plugin2.Plugin2;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Fait défiler plusieurs MOTD en boucle dans l'écran multijoueur des clients,
 * configurés depuis config/motd.yml (modifiable sans recompiler).
 *
 * Chaque client Minecraft "ping" le serveur en continu (env. toutes les
 * secondes) tant que l'écran multijoueur est ouvert — on choisit donc la
 * ligne à afficher en fonction du temps système écoulé, sans tâche planifiée
 * séparée : ça suffit à créer un effet d'animation/rotation.
 */
public class MotdListener implements Listener {

    private final Plugin2 plugin;
    private final Logger logger;
    private final File configFile;

    private long intervalleMs = 4000;
    private List<String> motdsFormates = new ArrayList<>();

    public MotdListener(Plugin2 plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config/motd.yml");
        load();
    }

    /** Charge (ou recharge) motd.yml depuis le disque. */
    public void load() {
        if (!configFile.exists()) {
            plugin.saveResource("config/motd.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultStream = plugin.getResource("config/motd.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }

        intervalleMs = config.getLong("intervalle-ms", 4000);

        List<String> nouveauxMotds = new ArrayList<>();
        List<?> liste = config.getList("motds");
        if (liste != null) {
            for (Object obj : liste) {
                if (obj instanceof java.util.Map<?, ?> map) {
                    Object ligne1Obj = ((java.util.Map<String, Object>) map).getOrDefault("ligne1", "");
                    Object ligne2Obj = ((java.util.Map<String, Object>) map).getOrDefault("ligne2", "");
                    String ligne1 = String.valueOf(ligne1Obj);
                    String ligne2 = String.valueOf(ligne2Obj);
                    nouveauxMotds.add((ligne1 + "\n" + ligne2).replace("&", "§"));
                }
            }
        }

        if (nouveauxMotds.isEmpty()) {
            logger.warning("motd.yml ne contient aucun MOTD valide — le MOTD par défaut du serveur sera conservé.");
        }
        motdsFormates = nouveauxMotds;
    }

    /** Recharge le fichier depuis le disque (utile pour /hub reload). */
    public void reload() {
        load();
    }

    @EventHandler
    public void onServerListPing(ServerListPingEvent event) {
        if (motdsFormates.isEmpty()) return; // rien à afficher, on laisse le MOTD par défaut

        int index = (int) ((System.currentTimeMillis() / intervalleMs) % motdsFormates.size());
        Component motd = LegacyComponentSerializer.legacySection().deserialize(motdsFormates.get(index));
        event.motd(motd);
    }
}