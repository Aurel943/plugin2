package org.example.plugin2.bossbar;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.EconomyManager;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Affiche, pour chaque joueur présent dans le hub, une boss bar (Adventure
 * BossBar — l'API qui contrôle la barre normalement réservée aux boss comme
 * le Wither ou l'Ender Dragon) qui fait défiler les slides définies dans
 * bossbar.yml.
 *
 * Une seule tâche globale (et non une tâche par joueur) avance l'index de
 * slide à afficher et recalcule la progression visuelle ; chaque joueur a sa
 * propre instance de BossBar Adventure (montrée/cachée individuellement),
 * ce qui permet à la slide "{solde}" d'afficher un texte différent pour
 * chacun sans dupliquer la logique de rotation.
 */
public class HubBossBarManager {

    /** Une slide affichée en boucle : texte brut (non encore résolu) + durée d'affichage. */
    private record Slide(String texte, int dureeTicks) {
    }

    private final Plugin2 plugin;
    private final EconomyManager economy;
    private final File file;

    private boolean active;
    private BossBar.Color couleur = BossBar.Color.YELLOW;
    private BossBar.Overlay style = BossBar.Overlay.PROGRESS;
    private boolean progressionDecroissante = true;

    private final List<Slide> slides = new ArrayList<>();

    // Une BossBar Adventure par joueur actuellement abonné (présent dans le hub).
    private final Map<UUID, BossBar> activeBars = new HashMap<>();

    // État de rotation, partagé par tout le monde : index de la slide actuelle
    // et nombre de ticks restants avant de passer à la suivante.
    private int slideIndex = 0;
    private int ticksRestantsSlide = 0;

    private BukkitTask tickTask;

    public HubBossBarManager(Plugin2 plugin) {
        this.plugin = plugin;
        this.economy = plugin.getEconomyManager();
        this.file = new File(plugin.getDataFolder(), "config/bossbar.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("config/bossbar.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        InputStream defaultStream = plugin.getResource("config/bossbar.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }

        active = config.getBoolean("general.active", true);
        couleur = parseColor(config.getString("general.couleur", "YELLOW"));
        style = parseStyle(config.getString("general.style", "SOLID"));
        progressionDecroissante = config.getBoolean("general.progression-decroissante", true);

        slides.clear();
        List<Map<?, ?>> rawSlides = config.getMapList("slides");
        for (Map<?, ?> raw : rawSlides) {
            Object texteObj = raw.get("texte");
            Object dureeObj = raw.get("duree-secondes");
            if (texteObj == null) continue;

            double dureeSecondes = dureeObj instanceof Number n ? n.doubleValue() : 5.0;
            int dureeTicks = Math.max(20, (int) (dureeSecondes * 20));
            slides.add(new Slide(texteObj.toString(), dureeTicks));
        }

        // Sécurité : si bossbar.yml ne définit aucune slide valide, on évite
        // une division par zéro / un affichage vide en ajoutant un repli minimal.
        if (slides.isEmpty()) {
            slides.add(new Slide("&6&l💰 Ton solde : &e{solde} tals", 100));
        }

        slideIndex = 0;
        ticksRestantsSlide = slides.get(0).dureeTicks();

        plugin.getLogger().info(slides.size() + " slide(s) de boss bar chargée(s) depuis bossbar.yml.");
    }

    public void reload() {
        boolean wasActive = !activeBars.isEmpty();
        stopAll();
        load();
        if (wasActive) {
            // Réabonne tous les joueurs actuellement dans le hub après le reload
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (plugin.getHubWorldManager().isHubWorld(player.getWorld())) {
                    subscribe(player);
                }
            }
        }
    }

    private BossBar.Color parseColor(String name) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Couleur de boss bar invalide '" + name + "', utilisation de YELLOW");
            return BossBar.Color.YELLOW;
        }
    }

    private BossBar.Overlay parseStyle(String name) {
        return switch (name.toUpperCase()) {
            case "SEGMENTED_6" -> BossBar.Overlay.NOTCHED_6;
            case "SEGMENTED_10" -> BossBar.Overlay.NOTCHED_10;
            case "SEGMENTED_12" -> BossBar.Overlay.NOTCHED_12;
            case "SEGMENTED_20" -> BossBar.Overlay.NOTCHED_20;
            default -> BossBar.Overlay.PROGRESS;
        };
    }

    /** Démarre la tâche globale de rotation. À appeler une seule fois dans onEnable(). */
    public void start() {
        if (!active) return;
        if (tickTask != null) return; // déjà démarrée

        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    private void tick() {
        if (slides.isEmpty()) return;

        ticksRestantsSlide--;
        if (ticksRestantsSlide <= 0) {
            slideIndex = (slideIndex + 1) % slides.size();
            ticksRestantsSlide = slides.get(slideIndex).dureeTicks();
        }

        Slide current = slides.get(slideIndex);
        float progress = progressionDecroissante
                ? Math.max(0f, Math.min(1f, ticksRestantsSlide / (float) current.dureeTicks()))
                : 1f;

        for (Map.Entry<UUID, BossBar> entry : activeBars.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            BossBar bar = entry.getValue();
            bar.name(resolveText(current.texte(), player));
            bar.progress(progress);
        }
    }

    /** Remplace {solde} par le solde en Cristaux du joueur, puis traduit les couleurs &. */
    private Component resolveText(String rawText, Player player) {
        String resolved = rawText;
        if (resolved.contains("{solde}")) {
            double solde = economy.getBalance(player.getUniqueId());
            resolved = resolved.replace("{solde}", String.valueOf((int) solde));
        }
        String legacy = ChatColor.translateAlternateColorCodes('&', resolved);
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    /** Abonne un joueur à la boss bar (créée à la volée, affichée immédiatement). */
    public void subscribe(Player player) {
        if (!active) return;
        if (activeBars.containsKey(player.getUniqueId())) return; // déjà abonné

        Slide current = slides.get(slideIndex);
        BossBar bar = BossBar.bossBar(resolveText(current.texte(), player), 1f, couleur, style);
        player.showBossBar(bar);
        activeBars.put(player.getUniqueId(), bar);
    }

    /** Désabonne un joueur (cache la barre). À appeler en quittant le hub ou le serveur. */
    public void unsubscribe(Player player) {
        BossBar bar = activeBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** Désabonne tout le monde, par exemple avant un reload ou à l'arrêt du plugin. */
    public void stopAll() {
        for (Map.Entry<UUID, BossBar> entry : activeBars.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        activeBars.clear();
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        stopAll();
    }
}