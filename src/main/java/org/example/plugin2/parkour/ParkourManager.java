package org.example.plugin2.parkour;

import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.Database;
import org.example.plugin2.messages.MessagesManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Gère tout le système de parkour : chargement des définitions de zones
 * depuis parkour.yml, création/chargement du monde dédié, logique d'un run
 * (entrée, checkpoints, chute, arrivée, sortie), classement des meilleurs
 * temps (avec cache mémoire, comme EconomyManager/UpgradeManager/RankManager),
 * et sauvegarde/restauration de l'inventaire du joueur pendant qu'il est dans
 * le parkour.
 *
 * Suit le même découpage que le reste du plugin : ce manager NE gère PAS les
 * clics/commandes (voir ParkourCommand) ni la détection des zones au
 * déplacement (voir ParkourListener) — il expose juste les opérations
 * (entrer, valider un checkpoint, faire tomber, terminer, sortir) que ces
 * deux classes viennent appeler.
 */
public class ParkourManager {

    /** Représente une zone en boîte : centre + demi-largeur sur chaque axe. */
    public static class ZoneBoite {
        public final double x, y, z;
        public final double rayonX, rayonY, rayonZ;

        public ZoneBoite(double x, double y, double z, double rayonX, double rayonY, double rayonZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.rayonX = rayonX;
            this.rayonY = rayonY;
            this.rayonZ = rayonZ;
        }

        /** Vrai si la position donnée (même monde supposé déjà vérifié par l'appelant) est dans la boîte. */
        public boolean contient(Location loc) {
            return Math.abs(loc.getX() - x) <= rayonX
                    && Math.abs(loc.getY() - y) <= rayonY
                    && Math.abs(loc.getZ() - z) <= rayonZ;
        }

        public Location toLocation(World world) {
            return new Location(world, x, y, z);
        }
    }

    /** Définition complète d'un parkour, telle que lue dans parkour.yml. */
    public static class ParkourDefinition {
        public final String id;
        public final String mondeNom;
        public final double limiteYChute;
        public final double recompenseCristaux;
        public final ZoneBoite entree;
        public final ZoneBoite depart;
        public final List<ZoneBoite> checkpoints;
        public final ZoneBoite arrivee;

        public ParkourDefinition(String id, String mondeNom, double limiteYChute, double recompenseCristaux,
                                 ZoneBoite entree, ZoneBoite depart, List<ZoneBoite> checkpoints, ZoneBoite arrivee) {
            this.id = id;
            this.mondeNom = mondeNom;
            this.limiteYChute = limiteYChute;
            this.recompenseCristaux = recompenseCristaux;
            this.entree = entree;
            this.depart = depart;
            this.checkpoints = checkpoints;
            this.arrivee = arrivee;
        }
    }

    private final Plugin2 plugin;
    private final Logger logger;
    private final Database database;
    private final File configFile;

    private final Map<String, ParkourDefinition> definitions = new HashMap<>();
    private final Map<String, World> mondesCharges = new HashMap<>();

    // Session de run en cours par joueur connecté. Un joueur n'a jamais plus
    // d'une session active à la fois (un seul parkour à la fois).
    private final Map<UUID, ParkourSession> sessionsActives = new ConcurrentHashMap<>();

    public static final org.bukkit.NamespacedKey RETOUR_KEY =
            new org.bukkit.NamespacedKey("plugin2", "parkour_retour");
    public static final org.bukkit.NamespacedKey RESET_KEY =
            new org.bukkit.NamespacedKey("plugin2", "parkour_reset");

    public ParkourManager(Plugin2 plugin, Database database) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.database = database;
        this.configFile = new File(plugin.getDataFolder(), "config/parkour.yml");
        load();
    }

    // ---------------------------------------------------------------
    // Chargement de la configuration et du monde
    // ---------------------------------------------------------------

    private void load() {
        if (!configFile.exists()) {
            plugin.saveResource("config/parkour.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultStream = plugin.getResource("config/parkour.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }

        definitions.clear();

        ConfigurationSection parkoursSection = config.getConfigurationSection("parkours");
        if (parkoursSection == null) {
            logger.warning("parkour.yml ne contient aucune section 'parkours' — le système de parkour sera inactif.");
            return;
        }

        for (String id : parkoursSection.getKeys(false)) {
            ConfigurationSection section = parkoursSection.getConfigurationSection(id);
            if (section == null) continue;

            try {
                ParkourDefinition def = lireParkour(id, section);
                definitions.put(id, def);
            } catch (Exception e) {
                logger.severe("Erreur lors du chargement du parkour '" + id + "' depuis parkour.yml : " + e.getMessage());
            }
        }
    }

    private ParkourDefinition lireParkour(String id, ConfigurationSection section) {
        String mondeNom = section.getString("monde", "parkour");
        double limiteYChute = section.getDouble("limite-y-chute", 0);
        double recompense = section.getDouble("recompense-cristaux", 0);

        ZoneBoite entree = lireZone(section.getConfigurationSection("entree"));
        ZoneBoite depart = lireZone(section.getConfigurationSection("depart"));
        ZoneBoite arrivee = lireZone(section.getConfigurationSection("arrivee"));

        List<ZoneBoite> checkpoints = new java.util.ArrayList<>();
        List<?> liste = section.getList("checkpoints");
        if (liste != null) {
            for (Object obj : liste) {
                if (obj instanceof Map<?, ?> map) {
                    checkpoints.add(lireZoneDepuisMap(map));
                }
            }
        }

        return new ParkourDefinition(id, mondeNom, limiteYChute, recompense, entree, depart, checkpoints, arrivee);
    }

    private ZoneBoite lireZone(ConfigurationSection section) {
        if (section == null) {
            // Zone par défaut au cas où une section manque dans le yml — évite un crash au démarrage,
            // mais signale un comportement potentiellement étrange en jeu si jamais utilisée.
            return new ZoneBoite(0, 64, 0, 1, 1, 1);
        }
        return new ZoneBoite(
                section.getDouble("x", 0),
                section.getDouble("y", 64),
                section.getDouble("z", 0),
                section.getDouble("rayon-x", 1),
                section.getDouble("rayon-y", 1),
                section.getDouble("rayon-z", 1)
        );
    }

    @SuppressWarnings("unchecked")
    private ZoneBoite lireZoneDepuisMap(Map<?, ?> map) {
        Map<String, Object> m = (Map<String, Object>) map;
        return new ZoneBoite(
                toDouble(m.get("x")), toDouble(m.get("y")), toDouble(m.get("z")),
                toDouble(m.getOrDefault("rayon-x", 1.0)),
                toDouble(m.getOrDefault("rayon-y", 1.0)),
                toDouble(m.getOrDefault("rayon-z", 1.0))
        );
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    /**
     * Crée (ou charge s'il existe déjà) chaque monde de parkour référencé
     * dans parkour.yml. À appeler une seule fois dans Plugin2.onEnable(),
     * après la construction de ce manager — suit le même principe que
     * HubWorldManager.setupWorld().
     *
     * Le monde est un VOID complet (aucun bloc généré, aucune structure,
     * aucune grotte) — voir ViderGenerateur ci-dessous. Le parcours lui-même
     * est entièrement construit à la main en jeu, pas généré par le plugin.
     */
    public void setupWorlds() {
        for (ParkourDefinition def : definitions.values()) {
            World existing = Bukkit.getWorld(def.mondeNom);
            if (existing != null) {
                mondesCharges.put(def.mondeNom, existing);
                continue;
            }

            WorldCreator creator = new WorldCreator(def.mondeNom);
            creator.generator(new ViderGenerateur());
            creator.generateStructures(false);

            World monde = Bukkit.createWorld(creator);
            if (monde != null) {
                // Pas de météo ni de mob hostile par défaut dans un monde de parkour —
                // évite des morts/dégâts non liés au parkour lui-même.
                monde.setGameRule(GameRules.ADVANCE_WEATHER, false);
                monde.setGameRule(GameRules.SPAWN_MOBS, false);
                mondesCharges.put(def.mondeNom, monde);
                logger.info("Monde parkour '" + def.mondeNom + "' créé (void).");
            }
        }
    }

    /**
     * Générateur de chunks qui ne place absolument aucun bloc (void total) :
     * aucun terrain, aucune grotte, aucun minerai, aucune structure naturelle.
     * Le parcours est entièrement construit à la main en jeu par-dessus ce
     * vide — voir setupWorlds() ci-dessus.
     */
    private static class ViderGenerateur extends org.bukkit.generator.ChunkGenerator {
        // Aucune méthode à surcharger : le comportement par défaut de
        // ChunkGenerator (sans rien redéfinir) ne place déjà aucun bloc.
        // La classe existe uniquement pour avoir un type concret à passer
        // à WorldCreator.generator(), qui attend un ChunkGenerator non-null.
    }

    public void reload() {
        load();
        setupWorlds();
    }

    /** Vide le cache du classement (les temps eux-mêmes restent en base, juste relus à la demande). */
    public void reloadAll() {
        // Pas de cache mémoire pour le classement (lu à la demande depuis Database) :
        // rien à vider ici, méthode conservée pour la cohérence d'appel depuis
        // Plugin2.reloadAllCaches() et pour un éventuel cache futur.
    }

    public ParkourDefinition getDefinition(String parkourId) {
        return definitions.get(parkourId);
    }

    public Map<String, ParkourDefinition> getAllDefinitions() {
        return definitions;
    }

    /** Vrai si le monde donné est un monde de parkour connu. */
    public boolean isParkourWorld(World world) {
        if (world == null) return false;
        for (ParkourDefinition def : definitions.values()) {
            if (def.mondeNom.equals(world.getName())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------
    // Sessions de run actives
    // ---------------------------------------------------------------

    public ParkourSession getSession(UUID uuid) {
        return sessionsActives.get(uuid);
    }

    public boolean estEnRun(UUID uuid) {
        return sessionsActives.containsKey(uuid);
    }

    /**
     * Vrai si ce joueur a une sauvegarde d'inventaire de parkour en attente
     * en base — couvre à la fois le cas "run actif" (estEnRun()) ET le cas
     * "téléporté dans la zone d'entrée mais pas encore parti" (où aucune
     * ParkourSession n'existe encore, mais l'inventaire est déjà vidé et
     * sauvegardé — voir preparerEntreeJoueur()). Utile pour tout code qui a
     * besoin de savoir "ce joueur doit-il être sorti proprement du parkour
     * avant d'être téléporté ailleurs ?", comme TeleportMenu.
     */
    public boolean aUneSauvegardeEnAttente(Player player) {
        return database.hasInventoryBackup(player.getUniqueId());
    }



    // ---------------------------------------------------------------
    // Entrée dans le parkour (téléportation menu OU zone "entree" à pied)
    // ---------------------------------------------------------------

    /**
     * Téléporte le joueur vers la zone d'entrée du parkour donné, et prépare
     * son inventaire (sauvegarde + vidage + objet retour). N'est PAS la même
     * chose que démarrer un run : le joueur doit ensuite marcher jusqu'à la
     * zone "depart" pour lancer son chrono — voir demarrerRun().
     */
    public void teleporterVersEntree(Player player, String parkourId) {
        ParkourDefinition def = definitions.get(parkourId);
        if (def == null) return;

        World monde = mondesCharges.get(def.mondeNom);
        if (monde == null) {
            logger.warning("Le monde parkour '" + def.mondeNom + "' n'est pas chargé — téléportation annulée.");
            return;
        }

        // Si le joueur a déjà une sauvegarde en attente (déjà dans un parkour
        // d'une session précédente non nettoyée), on ne sauvegarde pas une 2e
        // fois par-dessus — on réutilise l'existante pour ne rien perdre.
        if (!database.hasInventoryBackup(player.getUniqueId())) {
            preparerEntreeJoueur(player);
        }

        player.teleport(def.entree.toLocation(monde));
    }

    /**
     * Sauvegarde l'inventaire/armure actuels du joueur en base, vide son
     * inventaire, place l'objet retour au slot 5, et suspend pet/trail.
     */
    private void preparerEntreeJoueur(Player player) {
        PlayerInventory inv = player.getInventory();

        boolean avaitPet = plugin.getPetManager().getActivePetId(player.getUniqueId()) != null;
        boolean avaitTrail = plugin.getTrailEngine().getActiveTrailId(player.getUniqueId()) != null;

        String inventoryData = serialiser(inv.getContents());
        String armorData = serialiser(inv.getArmorContents());

        database.saveInventoryBackup(player.getUniqueId(), inventoryData, armorData, avaitPet, avaitTrail);

        inv.clear();
        inv.setArmorContents(new ItemStack[4]);

        inv.setItem(8, creerObjetRetour());

        // Suspend le pet/trail visuellement sans toucher à leur équipement en
        // base (mêmes méthodes que celles utilisées à la déconnexion).
        plugin.getPetManager().handlePlayerQuit(player);
        plugin.getTrailEngine().handlePlayerQuit(player);
    }

    private ItemStack creerObjetRetour() {
        ItemStack item = new ItemStack(org.bukkit.Material.RED_BED);
        MessagesManager messages = plugin.getMessagesManager();

        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', messages.get("parkour.objet-retour.nom")));
        meta.setLore(List.of(org.bukkit.ChatColor.translateAlternateColorCodes('&', messages.get("parkour.objet-retour.lore"))));
        // Marqué avec une PersistentDataTag pour être reconnu de façon fiable,
        // même resté en inventaire après un /plugin2 reload — même principe que CompassListener.
        meta.getPersistentDataContainer().set(RETOUR_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);

        return item;
    }

    private ItemStack creerObjetReset() {
        ItemStack item = new ItemStack(org.bukkit.Material.CLOCK);
        MessagesManager messages = plugin.getMessagesManager();

        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', messages.get("parkour.objet-reset.nom")));
        meta.setLore(List.of(org.bukkit.ChatColor.translateAlternateColorCodes('&', messages.get("parkour.objet-reset.lore"))));
        // Même principe que l'objet retour : marqué par PersistentDataTag pour
        // être reconnu de façon fiable même après un /plugin2 reload.
        meta.getPersistentDataContainer().set(RESET_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);

        return item;
    }

    public boolean estObjetReset(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(RESET_KEY, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    public boolean estObjetRetour(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(RETOUR_KEY, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------
    // Déroulement d'un run
    // ---------------------------------------------------------------

    /** Démarre un nouveau run pour ce joueur sur ce parkour (appelé en touchant la zone "depart"). */
    public void demarrerRun(Player player, String parkourId) {
        sessionsActives.put(player.getUniqueId(), new ParkourSession(parkourId));
        // L'item reset n'a de sens que pendant un run actif (avant le départ,
        // il n'y a rien à réinitialiser) — donné ici, retiré dans
        // terminerRun()/abandonnerRun(). Slot 0, à l'opposé de l'objet retour
        // (slot 8) pour ne jamais se chevaucher avec lui.
        player.getInventory().setItem(0, creerObjetReset());
        plugin.getMessagesManager().send(player, "parkour.run.depart");
    }

    /** Valide un checkpoint pour le run en cours du joueur, s'il y en a un. */
    public void validerCheckpoint(Player player, int index) {
        ParkourSession session = sessionsActives.get(player.getUniqueId());
        if (session == null) return;

        if (index > session.getDernierCheckpointValide()) {
            session.validerCheckpoint(index);
            plugin.getMessagesManager().send(player, "parkour.run.checkpoint", Map.of(
                    "numero", String.valueOf(index + 1)
            ));
        }
    }

    /**
     * Gère une chute (joueur descendu sous la limite Y du parkour) : le
     * téléporte au dernier checkpoint validé, ou à la zone de départ si
     * aucun checkpoint n'a encore été touché.
     */
    public void gererChute(Player player) {
        ParkourSession session = sessionsActives.get(player.getUniqueId());
        if (session == null) return;

        ParkourDefinition def = definitions.get(session.getParkourId());
        if (def == null) return;

        World monde = mondesCharges.get(def.mondeNom);
        if (monde == null) return;

        int dernierCheckpoint = session.getDernierCheckpointValide();
        Location cible;
        if (dernierCheckpoint >= 0 && dernierCheckpoint < def.checkpoints.size()) {
            cible = def.checkpoints.get(dernierCheckpoint).toLocation(monde);
        } else {
            cible = def.depart.toLocation(monde);
        }

        player.teleport(cible);
        plugin.getMessagesManager().send(player, "parkour.run.chute");
    }

    /**
     * Gère la mort d'un joueur dans un monde de parkour, quelle que soit la
     * cause (lave, vide hors run, mob si jamais autorisé un jour, etc.) —
     * appelée depuis ParkourListener.onDeath(). Comportement voulu (voir
     * discussion du " comme convenu ") :
     *   - mort PENDANT un run actif : traitée exactement comme une chute
     *     (retour au dernier checkpoint, le run continue, chrono non arrêté).
     *   - mort AVANT le départ du run (pas de session active) : renvoyée à
     *     la zone d'entrée du parkour, sans toucher à l'inventaire (déjà
     *     vidé avec l'objet retour, rien à restaurer).
     * Ne fait la téléportation que dans onRespawn (cette méthode calcule et
     * stocke seulement la destination) — voir ParkourListener pour le détail
     * de l'enchaînement death → respawn.
     */
    public Location calculerDestinationApresMort(Player player) {
        ParkourSession session = sessionsActives.get(player.getUniqueId());

        if (session != null) {
            // Mort pendant un run actif : même destination qu'une chute,
            // mais SANS toucher à la session (on ne l'enlève pas, le run
            // continue normalement après le respawn).
            ParkourDefinition def = definitions.get(session.getParkourId());
            if (def == null) return null;
            World monde = mondesCharges.get(def.mondeNom);
            if (monde == null) return null;

            int dernierCheckpoint = session.getDernierCheckpointValide();
            if (dernierCheckpoint >= 0 && dernierCheckpoint < def.checkpoints.size()) {
                return def.checkpoints.get(dernierCheckpoint).toLocation(monde);
            }
            return def.depart.toLocation(monde);
        }

        // Pas de run actif : mort avant le départ → retour à l'entrée du
        // même parkour. On retrouve le parkour via le monde courant du joueur.
        for (ParkourDefinition def : definitions.values()) {
            if (def.mondeNom.equals(player.getWorld().getName())) {
                World monde = mondesCharges.get(def.mondeNom);
                if (monde == null) return null;
                return def.entree.toLocation(monde);
            }
        }

        return null;
    }

    /** Message à envoyer après un respawn dans le monde parkour — distingue chute/mort en run vs avant départ. */
    public String messageApresMort(Player player) {
        return estEnRun(player.getUniqueId()) ? "parkour.run.mort" : null;
    }

    /**
     * Réinitialise complètement le run en cours (clic sur l'item reset,
     * slot 0) : remet le chrono à zéro et renvoie à la zone de départ. Ne
     * fait rien si aucun run n'est actif (l'item ne devrait normalement pas
     * être en inventaire dans ce cas, mais on sécurise quand même).
     */
    public void reinitialiserRun(Player player) {
        ParkourSession session = sessionsActives.get(player.getUniqueId());
        if (session == null) return;

        ParkourDefinition def = definitions.get(session.getParkourId());
        if (def == null) return;

        World monde = mondesCharges.get(def.mondeNom);
        if (monde == null) return;

        // Remplace la session par une toute neuve : chrono à zéro et
        // dernierCheckpointValide à -1, comme un tout nouveau départ.
        sessionsActives.put(player.getUniqueId(), new ParkourSession(def.id));

        player.teleport(def.depart.toLocation(monde));
        plugin.getMessagesManager().send(player, "parkour.run.reset");
    }

    /**
     * Termine le run en cours du joueur avec succès (zone d'arrivée touchée) :
     * enregistre le temps si c'est un record, donne la récompense, restaure
     * l'inventaire et renvoie au hub.
     */
    public void terminerRun(Player player) {
        ParkourSession session = sessionsActives.remove(player.getUniqueId());
        if (session == null) return;

        retirerObjetReset(player);

        ParkourDefinition def = definitions.get(session.getParkourId());
        if (def == null) return;

        long tempsMs = session.getTempsEcouleMs();
        boolean nouveauRecord = database.setBestTimeIfBetter(player.getUniqueId(), def.id, tempsMs);

        plugin.getEconomyManager().addBalance(player.getUniqueId(), def.recompenseCristaux);

        String tempsFormate = formaterTemps(tempsMs);
        MessagesManager messages = plugin.getMessagesManager();
        messages.send(player, "parkour.run.arrivee", Map.of(
                "temps", tempsFormate,
                "recompense", String.valueOf((int) def.recompenseCristaux)
        ));
        if (nouveauRecord) {
            messages.send(player, "parkour.run.nouveau-record");
        }

        restaurerEtRenvoyerAuHub(player);
    }

    /**
     * Abandonne le run en cours du joueur sans le terminer (objet retour
     * utilisé, ou déconnexion). Le run est simplement annulé — aucun temps
     * n'est enregistré.
     */
    public void abandonnerRun(Player player) {
        if (sessionsActives.remove(player.getUniqueId()) != null) {
            retirerObjetReset(player);
        }
    }

    /** Retire l'item reset de l'inventaire du joueur, s'il l'a encore (slot 0 normalement). */
    private void retirerObjetReset(Player player) {
        ItemStack[] contenu = player.getInventory().getContents();
        for (int slot = 0; slot < contenu.length; slot++) {
            if (estObjetReset(contenu[slot])) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    /** Annule le run en cours (s'il y en a un) et restaure l'inventaire/hub. Utilisé par l'objet retour. */
    public void sortirDuParkour(Player player) {
        abandonnerRun(player);
        restaurerEtRenvoyerAuHub(player);
    }

    private void restaurerEtRenvoyerAuHub(Player player) {
        // Important : on lit/restaure l'inventaire et on récupère l'état
        // pet/trail AVANT de téléporter, mais on ne réactive réellement
        // pet/trail qu'APRÈS la téléportation. PetManager et TrailEngine
        // font apparaître/recommencer leur effet à la position ACTUELLE du
        // joueur au moment de l'appel — les appeler avant la téléportation
        // ferait spawner le pet/trail dans le monde parkour, pendant que le
        // joueur part vers le hub, ce qui cassait le suivi (Location.distance()
        // entre deux mondes différents → exception en boucle dans la tâche
        // de suivi du pet).
        Database.InventoryBackup backup = restaurerInventaire(player);

        plugin.getHubWorldManager().teleportToHub(player);

        reactiverPetEtTrailSiBesoin(player, backup);
    }

    /** Réactive pet/trail si la sauvegarde indique qu'ils étaient actifs. Ne fait rien si backup est null. */
    private void reactiverPetEtTrailSiBesoin(Player player, Database.InventoryBackup backup) {
        if (backup == null) return;
        if (backup.hadPetEquipped) {
            plugin.getPetManager().handlePlayerJoin(player);
        }
        if (backup.hadTrailEquipped) {
            plugin.getTrailEngine().handlePlayerJoin(player);
        }
    }

    // ---------------------------------------------------------------
    // Sauvegarde / restauration d'inventaire
    // ---------------------------------------------------------------

    /**
     * Restaure l'inventaire/armure du joueur depuis la sauvegarde en base
     * (s'il y en a une), puis supprime la sauvegarde. NE réactive PAS
     * pet/trail elle-même (voir restaurerEtRenvoyerAuHub pour comprendre
     * pourquoi l'ordre avec la téléportation compte) — retourne la
     * sauvegarde lue (ou null) pour que l'appelant décide du bon moment.
     *
     * Appelée à la sortie normale du parkour (via restaurerEtRenvoyerAuHub)
     * ET directement au join si une sauvegarde existait encore (cas d'un
     * crash serveur pendant un run — voir ParkourListener.onJoin). Dans ce
     * 2e cas le joueur est déjà dans le bon monde (le hub, où il spawn par
     * défaut), donc réactiver pet/trail immédiatement ne pose pas de problème
     * — voir l'appel à reactiverPetEtTrailSiBesoin() dans ParkourListener.
     *
     * @return la sauvegarde lue, ou null s'il n'y en avait aucune.
     */
    public Database.InventoryBackup restaurerInventaire(Player player) {
        Database.InventoryBackup backup = database.getInventoryBackup(player.getUniqueId());
        if (backup == null) return null;

        PlayerInventory inv = player.getInventory();
        inv.setContents(deserialiser(backup.inventoryData));
        inv.setArmorContents(deserialiser(backup.armorData));

        database.deleteInventoryBackup(player.getUniqueId());

        return backup;
    }

    /** Réactive pet/trail pour ce joueur si la sauvegarde donnée l'indique. Accessible depuis ParkourListener. */
    public void reactiverPetEtTrailApresRestauration(Player player, Database.InventoryBackup backup) {
        reactiverPetEtTrailSiBesoin(player, backup);
    }

    private String serialiser(ItemStack[] items) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(baos)) {
            out.writeInt(items.length);
            for (ItemStack item : items) {
                out.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            logger.severe("Erreur lors de la sérialisation d'un inventaire parkour : " + e.getMessage());
            return "";
        }
    }

    private ItemStack[] deserialiser(String data) {
        if (data == null || data.isEmpty()) return new ItemStack[0];
        try (ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream in = new BukkitObjectInputStream(bais)) {
            int taille = in.readInt();
            ItemStack[] items = new ItemStack[taille];
            for (int i = 0; i < taille; i++) {
                items[i] = (ItemStack) in.readObject();
            }
            return items;
        } catch (IOException | ClassNotFoundException e) {
            logger.severe("Erreur lors de la désérialisation d'un inventaire parkour : " + e.getMessage());
            return new ItemStack[0];
        }
    }

    // ---------------------------------------------------------------
    // Classement
    // ---------------------------------------------------------------

    /** Retourne le top N des meilleurs temps pour un parkour, lu directement depuis la base. */
    public List<Map.Entry<UUID, Long>> getTop(String parkourId, int limite) {
        return database.getTopTimes(parkourId, limite);
    }

    /** Formate un temps en millisecondes en "1m 23.456s" (ou "23.456s" si moins d'une minute). */
    public String formaterTemps(long tempsMs) {
        long minutes = tempsMs / 60000;
        double secondes = (tempsMs % 60000) / 1000.0;
        if (minutes > 0) {
            return String.format("%dm %.3fs", minutes, secondes);
        }
        return String.format("%.3fs", secondes);
    }
}