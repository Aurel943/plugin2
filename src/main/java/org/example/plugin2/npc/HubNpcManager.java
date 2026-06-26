package org.example.plugin2.npc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;
import org.example.plugin2.Plugin2;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Gère les PNJ visuels du hub : des ArmorStand habillés d'une tête de joueur
 * texturée (skin custom via PlayerProfile), qui se baladent tout seuls dans
 * un rayon configurable autour d'un point central défini dans npc.yml.
 *
 * Chaque PNJ est défini par un id (clé sous "npcs" dans npc.yml), ce qui
 * permet d'en ajouter ou retirer autant qu'on veut depuis la config seule,
 * sans recompiler — voir npc.yml pour le détail des clés.
 *
 * Ne gère AUCUNE donnée persistante par joueur : pas de table SQL, pas de
 * passage par Database. Les ArmorStand sont des entités Bukkit éphémères,
 * recréées à chaque (re)chargement de ce manager (au démarrage du plugin,
 * ou via /plugin2 reload).
 *
 * Pas encore d'interaction au clic — voir HubNpcInteractListener (TODO menu).
 */
public class HubNpcManager {

    /** Définition d'un PNJ telle que lue dans npc.yml. */
    private static class NpcDefinition {
        final String id;
        final boolean actif;
        final String nomAffiche;
        final String mondeNom;
        final double centreX, centreY, centreZ;
        final double rayonBalade;
        final long intervalleTicks;
        final double vitesseBlocsParTick;
        final String textureBase64;

        NpcDefinition(String id, boolean actif, String nomAffiche, String mondeNom,
                      double centreX, double centreY, double centreZ,
                      double rayonBalade, long intervalleTicks, double vitesseBlocsParTick,
                      String textureBase64) {
            this.id = id;
            this.actif = actif;
            this.nomAffiche = nomAffiche;
            this.mondeNom = mondeNom;
            this.centreX = centreX;
            this.centreY = centreY;
            this.centreZ = centreZ;
            this.rayonBalade = rayonBalade;
            this.intervalleTicks = intervalleTicks;
            this.vitesseBlocsParTick = vitesseBlocsParTick;
            this.textureBase64 = textureBase64;
        }
    }

    /** État d'exécution d'un PNJ déjà spawné : l'entité + sa tâche de déplacement. */
    private static class NpcInstance {
        ArmorStand entity;
        BukkitTask deplacementTask;
        Location destinationActuelle;
    }

    private final Plugin2 plugin;
    private final Logger logger;
    private final File configFile;
    private final Random random = new Random();

    private YamlConfiguration config;
    private final Map<String, NpcDefinition> definitions = new HashMap<>();
    private final Map<String, NpcInstance> instancesActives = new HashMap<>();

    public HubNpcManager(Plugin2 plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config/npc.yml");
        loadConfig();
    }

    private void loadConfig() {
        if (!configFile.exists()) {
            plugin.saveResource("config/npc.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultStream = plugin.getResource("config/npc.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }

        parseDefinitions();
    }

    private void parseDefinitions() {
        definitions.clear();
        ConfigurationSection npcsSection = config.getConfigurationSection("npcs");
        if (npcsSection == null) {
            logger.warning("npc.yml ne contient aucune section 'npcs' — aucun PNJ ne sera créé.");
            return;
        }

        for (String id : npcsSection.getKeys(false)) {
            ConfigurationSection section = npcsSection.getConfigurationSection(id);
            if (section == null) continue;

            boolean actif = section.getBoolean("active", true);
            String nomAffiche = section.getString("nom", "&7PNJ");

            ConfigurationSection centre = section.getConfigurationSection("centre");
            String mondeNom = centre != null ? centre.getString("monde", "hub") : "hub";
            double centreX = centre != null ? centre.getDouble("x", 0) : 0;
            double centreY = centre != null ? centre.getDouble("y", 64) : 64;
            double centreZ = centre != null ? centre.getDouble("z", 0) : 0;

            double rayonBalade = section.getDouble("rayon-balade", 5.0);
            long intervalleTicks = section.getLong("deplacement-intervalle-ticks", 100);
            double vitesse = section.getDouble("vitesse-blocs-par-tick", 0.08);
            String textureBase64 = section.getString("texture-base64", "");

            definitions.put(id, new NpcDefinition(id, actif, nomAffiche, mondeNom,
                    centreX, centreY, centreZ, rayonBalade, intervalleTicks, vitesse, textureBase64));
        }
    }

    /**
     * Détruit tous les PNJ actifs puis recrée ceux définis dans npc.yml.
     * À appeler une fois au démarrage (Plugin2.onEnable), et à nouveau à
     * chaque /plugin2 reload pour que les modifications de npc.yml prennent
     * effet sans redémarrer le serveur.
     */
    public void reload() {
        despawnAll();
        loadConfig();
        spawnAllActive();
    }

    /** Crée (ou recrée) les entités pour tous les PNJ marqués "active: true". */
    public void spawnAllActive() {
        for (NpcDefinition def : definitions.values()) {
            if (def.actif) {
                spawnNpc(def);
            }
        }
    }

    private void spawnNpc(NpcDefinition def) {
        World monde = Bukkit.getWorld(def.mondeNom);
        if (monde == null) {
            logger.warning("PNJ '" + def.id + "' : le monde '" + def.mondeNom + "' n'est pas chargé — PNJ non créé.");
            return;
        }

        Location spawnLoc = new Location(monde, def.centreX, def.centreY, def.centreZ);

        ArmorStand armorStand = (ArmorStand) monde.spawnEntity(spawnLoc, EntityType.ARMOR_STAND);
        armorStand.setCustomName(def.nomAffiche.replace('&', '§'));
        armorStand.setCustomNameVisible(true);

        // Taille adulte normale (le défaut de small=false), contrairement à un
        // ArmorStand "miniature" — explicite ici pour que ce soit lisible sans
        // avoir à connaître la valeur par défaut de l'API.
        armorStand.setSmall(false);

        armorStand.setArms(true);
        armorStand.setBasePlate(false);
        armorStand.setGravity(false);
        armorStand.setInvulnerable(true);
        armorStand.setCanMove(false); // on gère le déplacement nous-mêmes via teleport, pas via l'IA (il n'y en a pas pour un ArmorStand)
        armorStand.setRemoveWhenFarAway(false);
        armorStand.setPersistent(false); // recréé par le plugin à chaque démarrage, pas besoin que Bukkit le sauvegarde sur disque

        equiperTete(armorStand, def.textureBase64);

        NpcInstance instance = new NpcInstance();
        instance.entity = armorStand;
        instancesActives.put(def.id, instance);

        demarrerDeplacement(def, instance);
    }

    /**
     * Pose une tête de joueur texturée sur l'ArmorStand. Le mécanisme : un
     * PlayerProfile fictif (UUID aléatoire, nom bidon) auquel on attache une
     * texture de skin encodée en Base64 (format attendu : le JSON
     * {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/<hash>"}}}
     * encodé en Base64, comme fourni par minecraft-heads.com ou mineskin.org).
     * Le client affiche alors ce skin sur la tête, même si ce "joueur"
     * n'existe pas réellement.
     */
    private void equiperTete(ArmorStand armorStand, String textureBase64) {
        if (textureBase64 == null || textureBase64.isBlank()) {
            return; // pas de texture configurée : l'ArmorStand reste sans tête custom (slot vide)
        }

        try {
            String json = new String(Base64.getDecoder().decode(textureBase64), StandardCharsets.UTF_8);
            String url = extraireUrlTexture(json);
            if (url == null) {
                logger.warning("Impossible d'extraire l'URL de texture depuis le base64 fourni dans npc.yml.");
                return;
            }

            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "NpcHub");
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL(url));
            profile.setTextures(textures);

            ItemStack tete = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) tete.getItemMeta();
            meta.setOwnerProfile(profile);
            tete.setItemMeta(meta);

            armorStand.getEquipment().setHelmet(tete);
        } catch (IllegalArgumentException e) {
            logger.warning("texture-base64 invalide dans npc.yml (pas du Base64 valide) : " + e.getMessage());
        } catch (MalformedURLException e) {
            logger.warning("URL de texture invalide décodée depuis npc.yml : " + e.getMessage());
        }
    }

    /**
     * Extrait grossièrement la valeur de "url" depuis le JSON décodé, sans
     * dépendre d'une lib JSON externe pour un besoin aussi simple (une seule
     * clé à lire). Suffisant ici : le format du JSON Mojang est stable.
     */
    private String extraireUrlTexture(String json) {
        int index = json.indexOf("\"url\":\"");
        if (index == -1) return null;
        int debut = index + "\"url\":\"".length();
        int fin = json.indexOf('"', debut);
        if (fin == -1) return null;
        return json.substring(debut, fin);
    }

    /**
     * Démarre la tâche périodique de déplacement : toutes les
     * "deplacement-intervalle-ticks", tire un nouveau point aléatoire dans le
     * disque de rayon "rayon-balade" autour du centre (même technique que
     * HubWorldManager.readCustomSpawnOrDefault pour le tirage dans un disque),
     * puis fait glisser le PNJ vers ce point par petits pas réguliers — un
     * ArmorStand n'a pas d'IA de marche native, donc on simule le mouvement
     * nous-mêmes par téléportations successives très proches (effet de
     * glissement fluide plutôt que des sauts visibles).
     */
    private void demarrerDeplacement(NpcDefinition def, NpcInstance instance) {
        instance.deplacementTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (instance.entity == null || instance.entity.isDead()) return;

            if (instance.destinationActuelle == null
                    || estArrive(instance.entity.getLocation(), instance.destinationActuelle)) {
                instance.destinationActuelle = tirerNouvellePosition(def);
            }

            avancerVers(instance.entity, instance.destinationActuelle, def.vitesseBlocsParTick);
        }, 20L, 4L); // démarre après 1 seconde, puis vérifie 5 fois par seconde (toutes les 4 ticks)
    }

    /** Tire un point aléatoire dans le disque de rayon "rayon-balade" autour du centre du PNJ. */
    private Location tirerNouvellePosition(NpcDefinition def) {
        World monde = Bukkit.getWorld(def.mondeNom);
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = Math.sqrt(random.nextDouble()) * def.rayonBalade;
        double x = def.centreX + Math.cos(angle) * distance;
        double z = def.centreZ + Math.sin(angle) * distance;
        return new Location(monde, x, def.centreY, z);
    }

    private boolean estArrive(Location actuelle, Location destination) {
        return actuelle.distanceSquared(destination) < 0.05;
    }

    /**
     * Avance le PNJ d'un pas vers la destination, et oriente son regard
     * (yaw) dans la direction du mouvement pour un effet "qui marche"
     * plus naturel qu'un déplacement de face figée.
     */
    private void avancerVers(ArmorStand entity, Location destination, double vitesse) {
        Location actuelle = entity.getLocation();
        Vector direction = destination.toVector().subtract(actuelle.toVector());
        double distance = direction.length();
        if (distance < 0.001) return;

        Vector pas = direction.normalize().multiply(Math.min(vitesse, distance));
        Location nouvelle = actuelle.clone().add(pas);

        float yaw = (float) Math.toDegrees(Math.atan2(-pas.getX(), pas.getZ()));
        nouvelle.setYaw(yaw);

        entity.teleport(nouvelle);
    }

    /** Supprime toutes les entités PNJ actives et arrête leurs tâches de déplacement. */
    public void despawnAll() {
        for (NpcInstance instance : instancesActives.values()) {
            if (instance.deplacementTask != null) {
                instance.deplacementTask.cancel();
            }
            if (instance.entity != null && !instance.entity.isDead()) {
                instance.entity.remove();
            }
        }
        instancesActives.clear();
    }

    /** Vrai si l'entité donnée est un PNJ géré par ce manager (utile pour le futur listener de clic). */
    public boolean isManagedNpc(org.bukkit.entity.Entity entity) {
        for (NpcInstance instance : instancesActives.values()) {
            if (instance.entity != null && instance.entity.equals(entity)) {
                return true;
            }
        }
        return false;
    }

    /** Retourne l'id du PNJ correspondant à cette entité, ou null si ce n'est pas un PNJ géré. */
    public String getNpcId(org.bukkit.entity.Entity entity) {
        for (Map.Entry<String, NpcInstance> entry : instancesActives.entrySet()) {
            if (entry.getValue().entity != null && entry.getValue().entity.equals(entity)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public List<String> getActiveNpcIds() {
        return new ArrayList<>(instancesActives.keySet());
    }
}