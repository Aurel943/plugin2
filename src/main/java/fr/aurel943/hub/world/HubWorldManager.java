package fr.aurel943.hub.world;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import fr.aurel943.hub.Hub;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Crée (ou charge s'il existe déjà) le monde dédié au hub, et applique
 * les réglages "figés" du monde lui-même (heure, météo, cycle jour/nuit,
 * mob spawn naturel) lus depuis hub-rules.yml.
 *
 * Ne gère PAS les règles liées au joueur (dégâts, faim, casse de bloc...) —
 * ça, c'est le rôle de HubRulesListener. Cette classe s'occupe uniquement
 * du monde en tant qu'objet Bukkit.
 */
public class HubWorldManager {

    private final Hub plugin;
    private final Logger logger;
    private final File configFile;

    private YamlConfiguration config;
    private World hubWorld;
    private BukkitTask timeLockTask;
    private final Random random = new Random();

    public HubWorldManager(Hub plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config/hub-rules.yml");
        loadConfig();
    }

    public void loadConfig() {
        if (!configFile.exists()) {
            plugin.saveResource("config/hub-rules.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultStream = plugin.getResource("config/hub-rules.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }
    }

    public YamlConfiguration getConfig() {
        return config;
    }

    public World getHubWorld() {
        return hubWorld;
    }

    /**
     * Crée le monde hub s'il n'existe pas encore sur le disque, le charge sinon.
     * À appeler une seule fois dans onEnable().
     */
    public void setupWorld() {
        String worldName = config.getString("monde.nom", "hub");
        String typeStr = config.getString("monde.type", "NORMAL").toUpperCase();

        World existing = plugin.getServer().getWorld(worldName);
        if (existing != null) {
            hubWorld = existing;
            logger.info("Monde hub '" + worldName + "' déjà chargé.");
        } else {
            WorldCreator creator = new WorldCreator(worldName);

            switch (typeStr) {
                case "FLAT" -> {
                    creator.type(WorldType.FLAT);
                    creator.generateStructures(false);
                }
                case "VOID" -> {
                    creator.type(WorldType.NORMAL);
                    creator.generatorSettings("{\"layers\":[],\"biome\":\"plains\"}");
                    creator.generateStructures(false);
                }
                default -> { // NORMAL
                    creator.type(WorldType.NORMAL);
                }
            }

            hubWorld = plugin.getServer().createWorld(creator);
            logger.info("Monde hub '" + worldName + "' créé (type " + typeStr + ").");
        }

        applyWorldRules();
    }

    /** Applique les réglages figés du monde (heure, météo, mob spawn, etc.). */
    private void applyWorldRules() {
        if (hubWorld == null) return;

        boolean heureFixe = config.getBoolean("monde.heure-fixe", true);
        int heureValeur = config.getInt("monde.heure-valeur", 6000);
        boolean meteoBloquee = config.getBoolean("monde.meteo-bloquee", true);
        boolean feuBloque = config.getBoolean("monde.feu-bloque", true);
        boolean mobSpawn = config.getBoolean("monde.mob-spawn-naturel", false);

// Paper 1.21.11 a renommé ces GameRule (DO_DAYLIGHT_CYCLE -> ADVANCE_TIME,
// DO_WEATHER_CYCLE -> ADVANCE_WEATHER, DO_MOB_SPAWNING -> SPAWN_MOBS) : même
// comportement, juste un nouveau nom côté API.
        hubWorld.setGameRule(GameRules.ADVANCE_TIME, !heureFixe);
        if (heureFixe) {
            hubWorld.setTime(heureValeur);
        }

        hubWorld.setGameRule(GameRules.ADVANCE_WEATHER, !meteoBloquee);
        if (meteoBloquee) {
            hubWorld.setStorm(false);
            hubWorld.setThundering(false);
        }

// DO_FIRE_TICK n'a pas d'équivalent booléen direct depuis 1.21.11 : le feu
// est maintenant contrôlé par un rayon de propagation (en blocs) autour des
// joueurs plutôt qu'un simple on/off. 0 = aucune propagation (= ancien "false"),
// 2 = valeur vanilla par défaut (= ancien "true").
        hubWorld.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, feuBloque ? 0 : 2);
        hubWorld.setGameRule(GameRules.SPAWN_MOBS, mobSpawn);

        // Si l'heure est fixe, on la réimpose régulièrement : certains plugins/commandes
        // ou le simple fait que des entités chargent le monde peuvent la faire dériver.
        if (timeLockTask != null) {
            timeLockTask.cancel();
        }
        if (heureFixe) {
            timeLockTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                if (hubWorld.getTime() != heureValeur) {
                    hubWorld.setTime(heureValeur);
                }
                if (meteoBloquee && (hubWorld.hasStorm() || hubWorld.isThundering())) {
                    hubWorld.setStorm(false);
                    hubWorld.setThundering(false);
                }
            }, 100L, 100L); // vérifie toutes les 5 secondes (100 ticks)
        }
    }

    /** Vrai si le monde donné est le hub. */
    public boolean isHubWorld(World world) {
        return hubWorld != null && world != null && world.getUID().equals(hubWorld.getUID());
    }

    /** Téléporte un joueur au point de spawn du hub. */
    public void teleportToHub(Player player) {
        if (hubWorld == null) return;
        Location spawn = readCustomSpawnOrDefault();
        player.teleport(spawn);
    }

    /**
     * Lit le spawn custom depuis hub-rules.yml (section "spawn"). Si un rayon
     * aléatoire est défini (> 0), tire un point aléatoire dans ce rayon autour
     * du centre, puis cherche le sol le plus proche pour éviter de spawn dans
     * le vide ou à l'intérieur d'un bloc. Si la section est absente, retombe
     * sur le spawn par défaut du monde.
     */
    private Location readCustomSpawnOrDefault() {
        ConfigurationSection spawnSection = config.getConfigurationSection("spawn");
        if (spawnSection == null) {
            return hubWorld.getSpawnLocation();
        }

        double centerX = spawnSection.getDouble("x", hubWorld.getSpawnLocation().getX());
        double centerY = spawnSection.getDouble("y", hubWorld.getSpawnLocation().getY());
        double centerZ = spawnSection.getDouble("z", hubWorld.getSpawnLocation().getZ());
        float yaw = (float) spawnSection.getDouble("yaw", 0);
        float pitch = (float) spawnSection.getDouble("pitch", 0);
        double radius = spawnSection.getDouble("rayon-aleatoire", 0);

        double finalX = centerX;
        double finalZ = centerZ;

        if (radius > 0) {
            // Tire un point uniformément réparti dans un disque de rayon "radius"
            // (et non dans un carré) — évite de favoriser les coins.
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            finalX = centerX + Math.cos(angle) * distance;
            finalZ = centerZ + Math.sin(angle) * distance;
        }

        double finalY = findSafeY(finalX, centerY, finalZ);

        return new Location(hubWorld, finalX, finalY, finalZ, yaw, pitch);
    }

    /**
     * Cherche une hauteur Y sûre pour spawn à la position (x, z) donnée :
     * part de startY et descend jusqu'à trouver un bloc solide sous les pieds
     * avec assez d'espace libre au-dessus. Si rien n'est trouvé (ex: vide total),
     * retombe sur startY tel quel pour ne pas bloquer le spawn.
     */
    private double findSafeY(double x, double startY, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int y = (int) Math.floor(startY);

        // Cherche vers le bas, dans une plage raisonnable, le premier sol solide
        // surmonté de deux blocs d'air (place pour les pieds + la tête).
        int minY = hubWorld.getMinHeight();
        for (int currentY = y; currentY > minY; currentY--) {
            org.bukkit.block.Block ground = hubWorld.getBlockAt(blockX, currentY, blockZ);
            org.bukkit.block.Block feet = hubWorld.getBlockAt(blockX, currentY + 1, blockZ);
            org.bukkit.block.Block head = hubWorld.getBlockAt(blockX, currentY + 2, blockZ);

            if (ground.getType().isSolid() && !feet.getType().isSolid() && !head.getType().isSolid()) {
                return currentY + 1.0;
            }
        }

        // Rien trouvé en descendant : on retombe sur la valeur configurée telle quelle
        return startY;
    }

    /**
     * Enregistre la position actuelle du joueur comme nouveau point central
     * de spawn du hub dans hub-rules.yml (utile pour /hub setspawn).
     * Ne touche pas au rayon aléatoire existant : modifie-le directement
     * dans hub-rules.yml (clé spawn.rayon-aleatoire) si besoin.
     */
    public void setSpawn(Location location) {
        config.set("spawn.x", location.getX());
        config.set("spawn.y", location.getY());
        config.set("spawn.z", location.getZ());
        config.set("spawn.yaw", (double) location.getYaw());
        config.set("spawn.pitch", (double) location.getPitch());
        try {
            config.save(configFile);
        } catch (java.io.IOException e) {
            logger.severe("Impossible de sauvegarder le spawn du hub : " + e.getMessage());
        }
    }

    public void shutdown() {
        if (timeLockTask != null) {
            timeLockTask.cancel();
        }
    }
}