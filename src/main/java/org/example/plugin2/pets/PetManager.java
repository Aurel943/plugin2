package org.example.plugin2.pets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.Database;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Gère la définition des pets (chargée depuis pets.yml), les pets possédés
 * par chaque joueur (persistés en SQLite via Database), et le pet actif qui
 * suit chaque joueur en jeu — qu'il s'agisse d'un vrai mob ou d'un armor
 * stand flottant.
 */
public class PetManager {

    /** Définition statique d'un pet, telle que lue dans pets.yml. */
    public static class PetDefinition {
        public final String id;
        public final String type; // "MOB" ou "ARMOR_STAND"
        public final EntityType entityType; // si MOB
        public final Material headItem; // si ARMOR_STAND
        public final boolean mini; // si ARMOR_STAND
        public final double prix;
        public final String displayName;
        public final Material icon;

        public PetDefinition(String id, String type, EntityType entityType, Material headItem,
                             boolean mini, double prix, String displayName, Material icon) {
            this.id = id;
            this.type = type;
            this.entityType = entityType;
            this.headItem = headItem;
            this.mini = mini;
            this.prix = prix;
            this.displayName = displayName;
            this.icon = icon;
        }
    }

    private final Plugin2 plugin;
    private final Logger logger;
    private final Database database;
    private final File file;
    private final Map<String, PetDefinition> definitions = new HashMap<>();

    // Pet actif (l'entité qui suit le joueur) + sa tâche de suivi, par joueur.
    // Ceci reste en mémoire (propre à la session en cours) ; ce qui est persistant
    // en base, c'est la possession des pets et lequel est marqué "équipé".
    private final Map<UUID, Entity> activePetEntity = new HashMap<>();
    private final Map<UUID, BukkitTask> activeFollowTask = new HashMap<>();
    private final Map<UUID, String> activePetId = new HashMap<>();

    public PetManager(Plugin2 plugin, Database database) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.database = database;
        this.file = new File(plugin.getDataFolder(), "config/pets.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("config/pets.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        InputStream defaultStream = plugin.getResource("config/pets.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaultConfig);
        }

        definitions.clear();
        ConfigurationSection section = config.getConfigurationSection("pets");
        if (section == null) {
            logger.warning("pets.yml ne contient aucune section 'pets'.");
            return;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection p = section.getConfigurationSection(id);
            if (p == null) continue;

            String type = p.getString("type", "MOB").toUpperCase();
            double prix = p.getDouble("prix", 0);
            String displayName = p.getString("display-name", id);
            Material icon = parseMaterial(p.getString("icon", "STONE"), Material.STONE);

            EntityType entityType = null;
            Material headItem = null;
            boolean mini = p.getBoolean("mini", true);

            if (type.equals("MOB")) {
                entityType = parseEntityType(p.getString("entity-type", "WOLF"));
            } else if (type.equals("ARMOR_STAND")) {
                headItem = parseMaterial(p.getString("head-item", "STONE"), Material.STONE);
            } else {
                logger.warning("Type de pet inconnu pour '" + id + "' : " + type + " (ignoré)");
                continue;
            }

            definitions.put(id, new PetDefinition(id, type, entityType, headItem, mini, prix, displayName, icon));
        }

        logger.info(definitions.size() + " pet(s) chargé(s) depuis pets.yml.");
    }

    public void reload() {
        load();
    }

    private Material parseMaterial(String name, Material fallback) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("Matériau invalide '" + name + "' dans pets.yml, utilisation de " + fallback);
            return fallback;
        }
    }

    private EntityType parseEntityType(String name) {
        try {
            return EntityType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("EntityType invalide '" + name + "' dans pets.yml, utilisation de WOLF");
            return EntityType.WOLF;
        }
    }

    public Map<String, PetDefinition> getDefinitions() {
        return definitions;
    }

    public PetDefinition getDefinition(String id) {
        return definitions.get(id);
    }

    // ---------------------------------------------------------------
    // Possession des pets (persistée en SQLite via Database)
    // ---------------------------------------------------------------

    public boolean owns(UUID uuid, String petId) {
        return database.getOwnedPets(uuid).contains(petId);
    }

    public void grant(UUID uuid, String petId) {
        database.grantPet(uuid, petId);
    }

    public Set<String> getOwned(UUID uuid) {
        return database.getOwnedPets(uuid);
    }

    // ---------------------------------------------------------------
    // Équiper / suivre / retirer le pet actif
    // ---------------------------------------------------------------

    public String getActivePetId(UUID uuid) {
        return activePetId.get(uuid);
    }

    /** Retire le pet actuellement actif du joueur, s'il y en a un. */
    public void unequip(Player player) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = activeFollowTask.remove(uuid);
        if (task != null) {
            task.cancel();
        }

        Entity entity = activePetEntity.remove(uuid);
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }

        activePetId.remove(uuid);
        database.setEquippedPet(uuid, null);
    }

    /** Équipe un nouveau pet pour le joueur (retire l'ancien automatiquement). */
    public void equip(Player player, String petId) {
        PetDefinition def = definitions.get(petId);
        if (def == null) return;

        // Retire l'ancienne entité en jeu, sans toucher à la base
        // (on va écrire le nouvel état juste après, pas besoin de désélectionner puis resélectionner)
        despawnActiveEntity(player.getUniqueId());

        Location spawnLoc = player.getLocation();
        Entity entity;

        if (def.type.equals("MOB")) {
            entity = spawnMobPet(player, def, spawnLoc);
        } else {
            entity = spawnArmorStandPet(def, spawnLoc);
        }

        activePetEntity.put(player.getUniqueId(), entity);
        activePetId.put(player.getUniqueId(), petId);
        database.setEquippedPet(player.getUniqueId(), petId);

        // Tâche répétée toutes les 2 ticks : fait suivre le pet derrière le joueur.
        // Fonctionne aussi bien pour un mob (on le téléporte/guide) que pour un armor stand.
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || entity.isDead()) {
                unequip(player);
                return;
            }
            followPlayer(player, entity, def);
        }, 0L, 2L);

        activeFollowTask.put(player.getUniqueId(), task);
    }

    /** Supprime l'entité en jeu et sa tâche de suivi, sans toucher à la base de données. */
    private void despawnActiveEntity(UUID uuid) {
        BukkitTask task = activeFollowTask.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        Entity entity = activePetEntity.remove(uuid);
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }
        activePetId.remove(uuid);
    }

    private Entity spawnMobPet(Player player, PetDefinition def, Location loc) {
        Entity entity = loc.getWorld().spawnEntity(loc, def.entityType);
        entity.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', def.displayName));
        entity.setCustomNameVisible(true);
        entity.setPersistent(false); // ne doit pas survivre à un redémarrage chargé depuis le monde

        if (entity instanceof Mob mob) {
            mob.setRemoveWhenFarAway(false);
            // Évite que le mob attaque ou erre trop loin tout seul
            mob.setAware(true);
        }
        if (entity instanceof org.bukkit.entity.Ageable ageable) {
            ageable.setBaby(); // version "mini" pour un effet pet plus mignon, si applicable
        }
        entity.setInvulnerable(true);
        entity.setSilent(false);

        return entity;
    }

    private Entity spawnArmorStandPet(PetDefinition def, Location loc) {
        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSmall(def.mini);
        stand.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', def.displayName));
        stand.setCustomNameVisible(true);
        stand.setPersistent(false);
        stand.getEquipment().setHelmet(new ItemStack(def.headItem));
        return stand;
    }

    /**
     * Déplace le pet pour qu'il reste proche du joueur, légèrement décalé derrière lui.
     * Simple et léger — suffisant pour un effet "pet qui suit" en hub.
     */
    private void followPlayer(Player player, Entity entity, PetDefinition def) {
        Location target = player.getLocation().clone();

        // Calcule un point derrière le joueur (1.5 bloc en arrière, selon son regard)
        double yawRad = Math.toRadians(target.getYaw());
        double offsetX = Math.sin(yawRad) * 1.5;
        double offsetZ = -Math.cos(yawRad) * 1.5;
        target.add(-offsetX, 0, -offsetZ);

        if (entity instanceof ArmorStand) {
            // Armor stand : on le téléporte directement (pas de pathfinding, donc pas de saccades de marche)
            target.setY(target.getY() + 0.2);
            entity.teleport(target);
        } else if (entity instanceof Mob mob) {
            // Vrai mob : trop loin → téléportation directe, sinon on le laisse marcher via pathfinder
            if (entity.getLocation().distance(player.getLocation()) > 6) {
                entity.teleport(target);
            } else {
                mob.getPathfinder().moveTo(target, 1.0);
            }
        }
    }

    /** À appeler quand un joueur quitte le serveur, pour nettoyer son pet actif. */
    public void handlePlayerQuit(Player player) {
        // On désactive l'entité visuelle mais on NE touche PAS à "equipped" en base :
        // on veut le ré-équiper automatiquement à sa reconnexion.
        despawnActiveEntity(player.getUniqueId());
    }

    /**
     * À appeler quand un joueur se connecte. Si la base indique qu'il avait
     * un pet équipé avant de quitter, on le fait réapparaître automatiquement.
     */
    public void handlePlayerJoin(Player player) {
        String equippedId = database.getEquippedPet(player.getUniqueId());
        if (equippedId == null) return;

        PetDefinition def = definitions.get(equippedId);
        if (def == null) {
            // Le pet a peut-être été retiré de pets.yml depuis l'achat — on nettoie proprement
            database.setEquippedPet(player.getUniqueId(), null);
            return;
        }

        // On rejoue la logique de spawn sans repasser par setEquippedPet
        // (déjà marqué équipé en base, pas besoin de réécrire)
        Location spawnLoc = player.getLocation();
        Entity entity = def.type.equals("MOB")
                ? spawnMobPet(player, def, spawnLoc)
                : spawnArmorStandPet(def, spawnLoc);

        activePetEntity.put(player.getUniqueId(), entity);
        activePetId.put(player.getUniqueId(), equippedId);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || entity.isDead()) {
                unequip(player);
                return;
            }
            followPlayer(player, entity, def);
        }, 0L, 2L);

        activeFollowTask.put(player.getUniqueId(), task);
    }
}