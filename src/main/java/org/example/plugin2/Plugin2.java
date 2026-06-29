package org.example.plugin2;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.plugin2.bossbar.HubBossBarManager;
import org.example.plugin2.commands.*;
import org.example.plugin2.cosmetics.CosmeticManager;
import org.example.plugin2.cosmetics.TrailEngine;
import org.example.plugin2.economy.Database;
import org.example.plugin2.economy.EconomyManager;
import org.example.plugin2.economy.UpgradeManager;
import org.example.plugin2.listeners.*;
import org.example.plugin2.menus.CosmeticsMenu;
import org.example.plugin2.menus.HubMenu;
import org.example.plugin2.menus.PetsMenu;
import org.example.plugin2.menus.TeleportMenu;
import org.example.plugin2.menus.UpgradeShopMenu;
import org.example.plugin2.messages.MessagesManager;
import org.example.plugin2.pets.PetManager;
import org.example.plugin2.ranks.RankJoinListener;
import org.example.plugin2.ranks.RankManager;
import org.example.plugin2.world.HubWorldManager;
import org.example.plugin2.parkour.ParkourManager;
import org.example.plugin2.scoreboard.ScoreboardManager;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;

public class Plugin2 extends JavaPlugin {

    private MotdListener motdListener;
    private Database database;
    private EconomyManager economyManager;
    private UpgradeManager upgradeManager;
    private MessagesManager messagesManager;
    private PetManager petManager;
    private CosmeticManager cosmeticManager;
    private TrailEngine trailEngine;
    private HubBossBarManager bossBarManager;
    private RankManager rankManager;
    private HubMenu hubMenu;
    private TeleportMenu teleportMenu;
    private PetsMenu petsMenu;
    private UpgradeShopMenu upgradeShopMenu;
    private CosmeticsMenu cosmeticsMenu;
    private CompassListener compassListener;
    private HubWorldManager hubWorldManager;
    private ParkourManager parkourManager;
    private ScoreboardManager scoreboardManager;
    private BukkitTask scoreboardRefreshTask;

    @Override
    public void onEnable() {
        getLogger().info("Plugin2 a été activé avec succès !");

        // Charge messages.yml et pets.yml dans le dossier du plugin s'ils n'existent pas encore
        saveDefaultResourceIfMissing("config/messages.yml");
        saveDefaultResourceIfMissing("config/pets.yml");
        saveDefaultResourceIfMissing("config/hub-rules.yml");
        saveDefaultResourceIfMissing("config/cosmetics.yml");
        saveDefaultResourceIfMissing("config/bossbar.yml");

        // Initialisation de la base et du gestionnaire d'économie
        database = new Database(getDataFolder(), getLogger());
        database.connect(this);
        economyManager = new EconomyManager(database);
        upgradeManager = new UpgradeManager(database);
        rankManager = new RankManager(database);
        rankManager.setPluginInstance(this);

        // Managers de contenu paramétrable
        messagesManager = new MessagesManager(this);
        petManager = new PetManager(this, database);
        cosmeticManager = new CosmeticManager(this, database);
        trailEngine = new TrailEngine(this, cosmeticManager);

        // Monde du hub : crée/charge le monde dédié et applique ses règles (heure, météo...)
        hubWorldManager = new HubWorldManager(this);
        hubWorldManager.setupWorld();

        // Système de parkour : chargement de parkour.yml + création/chargement
        // du monde dédié. Après hubWorldManager (même pattern), mais avant les
        // listeners puisque ParkourListener a besoin que ce manager existe déjà.
        saveDefaultResourceIfMissing("config/parkour.yml");
        parkourManager = new ParkourManager(this, database);
        parkourManager.setupWorlds();

        // Scoreboard latéral : affiché uniquement dans les mondes listés dans
        // scoreboard.yml (le hub par défaut). Placé après les autres managers
        // dont il dépend pour ses variables (économie, ranks).
        saveDefaultResourceIfMissing("config/scoreboard.yml");
        scoreboardManager = new ScoreboardManager(this);

        // Boss bar du hub : démarrée après le monde, pour pouvoir abonner les
        // joueurs déjà connectés au reload (cf. HubBossBarManager.reload()).
        bossBarManager = new HubBossBarManager(this);
        bossBarManager.start();

        // Menus (chacun s'enregistre aussi comme listener pour ses propres clics)
        hubMenu = new HubMenu(this);
        teleportMenu = new TeleportMenu(this);
        petsMenu = new PetsMenu(this);
        upgradeShopMenu = new UpgradeShopMenu(this);
        cosmeticsMenu = new CosmeticsMenu(this);
        compassListener = new CompassListener(this);

        // MOTD dans la liste des serveurs
        motdListener = new MotdListener(this);

        // Enregistrement des listeners
        getServer().getPluginManager().registerEvents(new WelcomeListener(this), this);
        getServer().getPluginManager().registerEvents(hubMenu, this);
        getServer().getPluginManager().registerEvents(teleportMenu, this);
        getServer().getPluginManager().registerEvents(petsMenu, this);
        getServer().getPluginManager().registerEvents(upgradeShopMenu, this);
        getServer().getPluginManager().registerEvents(cosmeticsMenu, this);
        getServer().getPluginManager().registerEvents(compassListener, this);
        getServer().getPluginManager().registerEvents(new PetSessionListener(), this);
        getServer().getPluginManager().registerEvents(new HubRulesListener(this, hubWorldManager), this);
        getServer().getPluginManager().registerEvents(new SuperSautListener(this), this);
        getServer().getPluginManager().registerEvents(new TagChatListener(this), this);
        getServer().getPluginManager().registerEvents(motdListener, this);
        getServer().getPluginManager().registerEvents(new RankJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new ParkourListener(this), this);
        getServer().getPluginManager().registerEvents(new ParkourRulesListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryLockListener(this), this);
        getServer().getPluginManager().registerEvents(new PetProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new ScoreboardListener(this), this);

        // Enregistrement de l'executor pour la commande /coins
        // (nécessaire en plus de plugin.yml : c'est ce qui relie le nom de commande au code)
        getCommand("coins").setExecutor(new CoinsCommand(this));
        getCommand("hub").setExecutor(new HubCommand(this));
        getCommand("uuid").setExecutor(new UuidCommand(this));
        getCommand("plugin2").setExecutor(new Plugin2Command(this));
        getCommand("rank").setExecutor(new RankCommand(this));
        getCommand("parkour").setExecutor(new ParkourCommand(this));

        // Rafraîchissement périodique du scoreboard (Cristaux, heure, nombre de
        // joueurs en ligne) — n'écrit que les lignes dont la valeur a changé,
        // donc aucun scintillement même à 1x/seconde (voir ScoreboardManager.update()).
        scoreboardRefreshTask = getServer().getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                if (scoreboardManager.estDansMondeActif(player.getWorld())) {
                    scoreboardManager.update(player);
                }
            }
        }, scoreboardManager.getIntervalleTicks(), scoreboardManager.getIntervalleTicks());
    }

    @Override
    public void onDisable() {
        getLogger().info("Plugin2 a été désactivé.");
        if (bossBarManager != null) {
            bossBarManager.shutdown();
        }
        if (hubWorldManager != null) {
            hubWorldManager.shutdown();
        }
        if (scoreboardRefreshTask != null) {
            scoreboardRefreshTask.cancel();
        }
        if (database != null) {
            database.disconnect();
        }
    }

    private void saveDefaultResourceIfMissing(String name) {
        java.io.File target = new java.io.File(getDataFolder(), name);
        if (!target.exists()) {
            saveResource(name, false);
        }
    }

    public MotdListener getMotdListener() { return motdListener; }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public UpgradeManager getUpgradeManager() {
        return upgradeManager;
    }

    public MessagesManager getMessagesManager() {
        return messagesManager;
    }

    public PetManager getPetManager() {
        return petManager;
    }

    public CosmeticManager getCosmeticManager() {
        return cosmeticManager;
    }

    public TrailEngine getTrailEngine() {
        return trailEngine;
    }

    public HubBossBarManager getBossBarManager() {
        return bossBarManager;
    }

    public CosmeticsMenu getCosmeticsMenu() {
        return cosmeticsMenu;
    }

    public CompassListener getCompassListener() {
        return compassListener;
    }

    public HubMenu getHubMenu() {
        return hubMenu;
    }

    public TeleportMenu getTeleportMenu() { return teleportMenu; }

    public PetsMenu getPetsMenu() {
        return petsMenu;
    }

    public UpgradeShopMenu getUpgradeShopMenu() {
        return upgradeShopMenu;
    }

    public HubWorldManager getHubWorldManager() {
        return hubWorldManager;
    }

    public RankManager getRankManager() { return rankManager; }

    public ParkourManager getParkourManager() { return parkourManager; }

    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }

    // Petits "ponts" pour que CoinsCommand puisse accéder à la database
    // sans avoir à la connaître directement (juste via Plugin2)
    public Map<UUID, Double> getDatabaseAllBalances() {
        return database.getAllBalances();
    }

    public void getDatabaseResetAll() {
        economyManager.resetAll(database);
    }
    /**
     * Recharge TOUT ce qui peut l'être à chaud : caches mémoire (économie,
     * upgrades, ranks, parkour, NPC) ET fichiers de configuration (messages,
     * pets, cosmétiques, boss bar, MOTD). C'est désormais LE reload complet
     * du plugin — voir Plugin2Command ("/plugin2 reload").
     *
     * /hub reload reste séparé et ne recharge QUE hub-rules.yml : son rôle
     * est strictement limité aux réglages du monde du hub (heure, météo,
     * dégâts...), pas un reload général. Avant cette unification, les deux
     * commandes se chevauchaient partiellement sans jamais couvrir le même
     * périmètre, ce qui pouvait faire croire à un admin qu'un fichier était
     * rechargé alors qu'il ne l'était pas.
     */
    public void reloadAllCaches() {
        economyManager.reloadAll();
        upgradeManager.reloadAll();
        rankManager.reloadAll();
        parkourManager.reload();
        scoreboardManager.reload();
        messagesManager.reload();
        petManager.reload();
        cosmeticManager.reload();
        bossBarManager.reload();
        motdListener.reload();
    }

    /** Petit listener interne : gère le pet actif et le trail cosmétique actif d'un joueur à la connexion/déconnexion. */
    private class PetSessionListener implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            petManager.handlePlayerJoin(event.getPlayer());
            trailEngine.handlePlayerJoin(event.getPlayer());
        }

        @org.bukkit.event.EventHandler
        public void onQuit(PlayerQuitEvent event) {
            petManager.handlePlayerQuit(event.getPlayer());
            trailEngine.handlePlayerQuit(event.getPlayer());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bonjour")) {
            if (sender instanceof Player player) {
                player.sendMessage("Bonjour " + player.getName() + " ! Le plugin fonctionne.");
            } else {
                sender.sendMessage("Bonjour depuis la console !");
            }
            return true;
        }
        return false;
    }

}