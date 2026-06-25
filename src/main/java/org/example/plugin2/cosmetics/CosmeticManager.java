package org.example.plugin2.cosmetics;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.Database;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Gère les deux catégories de cosmétiques (trails, compass-skins) :
 * chargement des définitions depuis cosmetics.yml, et possession/équipement
 * persistés en SQLite via Database (table "cosmetics", partagée par les
 * deux catégories — voir Database.grantCosmetic / setEquippedCosmetic).
 *
 * Une seule classe pour les deux catégories plutôt que deux managers
 * séparés : la logique d'achat/équipement est strictement identique, seule
 * la "forme" de la définition change (TrailDefinition /
 * CompassSkinDefinition). Le menu (CosmeticsMenu) et le moteur de particules
 * (TrailEngine) viennent ensuite consommer ces définitions.
 */
public class CosmeticManager {

    /** Identifiants des catégories, utilisés comme clé dans la table "cosmetics". */
    public static final String CATEGORY_TRAIL = "trail";
    public static final String CATEGORY_COMPASS_SKIN = "compass-skin";

    /** Définition d'un trail de particules (catégorie "trail"). */
    public static class TrailDefinition {
        public final String id;
        public final Particle particle;
        public final double prix;
        public final String displayName;
        public final Material icon;
        public final int intervalleTicks;
        public final int nombre;
        public final double rayonX, rayonY, rayonZ;
        public final double vitesse;
        public final double hauteur;

        public TrailDefinition(String id, Particle particle, double prix, String displayName, Material icon,
                               int intervalleTicks, int nombre, double rayonX, double rayonY, double rayonZ,
                               double vitesse, double hauteur) {
            this.id = id;
            this.particle = particle;
            this.prix = prix;
            this.displayName = displayName;
            this.icon = icon;
            this.intervalleTicks = Math.max(1, intervalleTicks);
            this.nombre = nombre;
            this.rayonX = rayonX;
            this.rayonY = rayonY;
            this.rayonZ = rayonZ;
            this.vitesse = vitesse;
            this.hauteur = hauteur;
        }
    }

    /** Définition d'un skin de boussole (catégorie "compass-skin"). */
    public static class CompassSkinDefinition {
        public final String id;
        public final Material material;
        public final String displayName;
        public final double prix;
        public final Material icon;

        public CompassSkinDefinition(String id, Material material, String displayName, double prix, Material icon) {
            this.id = id;
            this.material = material;
            this.displayName = displayName;
            this.prix = prix;
            this.icon = icon;
        }
    }

    private final Plugin2 plugin;
    private final Logger logger;
    private final Database database;
    private final File file;

    // LinkedHashMap pour préserver l'ordre de cosmetics.yml dans les menus
    private final Map<String, TrailDefinition> trails = new LinkedHashMap<>();
    private final Map<String, CompassSkinDefinition> compassSkins = new LinkedHashMap<>();

    public CosmeticManager(Plugin2 plugin, Database database) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.database = database;
        this.file = new File(plugin.getDataFolder(), "config/cosmetics.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("config/cosmetics.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        InputStream defaultStream = plugin.getResource("config/cosmetics.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaultConfig);
        }

        loadTrails(config);
        loadCompassSkins(config);

        logger.info(trails.size() + " trail(s), " + compassSkins.size()
                + " skin(s) de boussole chargés depuis cosmetics.yml.");
    }

    public void reload() {
        load();
    }

    private void loadTrails(YamlConfiguration config) {
        trails.clear();
        ConfigurationSection section = config.getConfigurationSection("trails");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;

            Particle particle = parseParticle(s.getString("particle", "FLAME"));
            Material icon = parseMaterial(s.getString("icon", "STONE"), Material.STONE);

            trails.put(id, new TrailDefinition(
                    id,
                    particle,
                    s.getDouble("prix", 0),
                    s.getString("display-name", id),
                    icon,
                    s.getInt("intervalle-ticks", 4),
                    s.getInt("nombre", 2),
                    s.getDouble("rayon-x", 0.25),
                    s.getDouble("rayon-y", 0.1),
                    s.getDouble("rayon-z", 0.25),
                    s.getDouble("vitesse", 0.0),
                    s.getDouble("hauteur", 0.1)
            ));
        }
    }

    private void loadCompassSkins(YamlConfiguration config) {
        compassSkins.clear();
        ConfigurationSection section = config.getConfigurationSection("compass-skins");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;

            compassSkins.put(id, new CompassSkinDefinition(
                    id,
                    parseMaterial(s.getString("material", "COMPASS"), Material.COMPASS),
                    s.getString("display-name", id),
                    s.getDouble("prix", 0),
                    parseMaterial(s.getString("icon", "COMPASS"), Material.COMPASS)
            ));
        }
    }

    private Material parseMaterial(String name, Material fallback) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("Matériau invalide '" + name + "' dans cosmetics.yml, utilisation de " + fallback);
            return fallback;
        }
    }

    private Particle parseParticle(String name) {
        try {
            return Particle.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("Particule invalide '" + name + "' dans cosmetics.yml, utilisation de FLAME");
            return Particle.FLAME;
        }
    }

    // ---------------------------------------------------------------
    // Accès aux définitions (utilisé par les menus et le TrailEngine)
    // ---------------------------------------------------------------

    public Map<String, TrailDefinition> getTrailDefinitions() {
        return trails;
    }

    public TrailDefinition getTrail(String id) {
        return trails.get(id);
    }

    public Map<String, CompassSkinDefinition> getCompassSkinDefinitions() {
        return compassSkins;
    }

    public CompassSkinDefinition getCompassSkin(String id) {
        return compassSkins.get(id);
    }

    // ---------------------------------------------------------------
    // Possession / équipement (générique, par catégorie)
    // ---------------------------------------------------------------

    public boolean owns(UUID uuid, String category, String cosmeticId) {
        return database.getOwnedCosmetics(uuid, category).contains(cosmeticId);
    }

    public void grant(UUID uuid, String category, String cosmeticId) {
        database.grantCosmetic(uuid, category, cosmeticId);
    }

    public Set<String> getOwned(UUID uuid, String category) {
        return database.getOwnedCosmetics(uuid, category);
    }

    public String getEquipped(UUID uuid, String category) {
        return database.getEquippedCosmetic(uuid, category);
    }

    public void setEquipped(UUID uuid, String category, String cosmeticId) {
        database.setEquippedCosmetic(uuid, category, cosmeticId);
    }
}