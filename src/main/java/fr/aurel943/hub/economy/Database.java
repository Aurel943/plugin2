package fr.aurel943.hub.economy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Couche d'accès à la base de données MySQL. Toutes les données (économie,
 * pets, upgrades, cosmétiques, ranks, parkour) vivent dans une base MySQL
 * externe, ce qui permet à plusieurs serveurs Paper de partager les mêmes
 * données joueur (ex: hub + survival derrière un proxy BungeeCord/Velocity).
 *
 * Utilise HikariCP comme pool de connexions plutôt qu'une connexion unique :
 * indispensable avec MySQL puisque plusieurs requêtes peuvent arriver en
 * parallèle (plusieurs joueurs simultanés).
 *
 * Les paramètres de connexion (host, port, identifiants, etc.) viennent de
 * database.yml plutôt que d'être codés en dur, pour permettre de changer de
 * serveur MySQL (local pour les tests, distant en production) sans recompiler.
 */
public class Database {

    private final Logger logger;
    private final File configFile;
    private HikariDataSource dataSource;

    public Database(File pluginFolder, Logger logger) {
        this.logger = logger;
        this.configFile = new File(pluginFolder, "config/database.yml");
    }

    /**
     * Charge database.yml (le crée depuis les valeurs par défaut du jar s'il
     * n'existe pas encore), initialise le pool de connexions HikariCP, et
     * crée les tables si besoin. Doit être appelée une seule fois, dans
     * onEnable(), AVANT toute autre méthode de cette classe.
     */
    public void connect(org.bukkit.plugin.java.JavaPlugin plugin) {
        if (!configFile.exists()) {
            plugin.saveResource("config/database.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultStream = plugin.getResource("config/database.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }

        String host = config.getString("mysql.host", "127.0.0.1");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "plugin2_db");
        String utilisateur = config.getString("mysql.utilisateur", "root");
        String motDePasse = config.getString("mysql.mot-de-passe", "");

        // useSSL=false et allowPublicKeyRetrieval=true conviennent à un usage
        // interne (serveur de jeu <-> base de données sur le même réseau privé
        // ou en local) ; à durcir (activer SSL) si la base est exposée publiquement.
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8";

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(utilisateur);
        hikariConfig.setPassword(motDePasse);
        hikariConfig.setMaximumPoolSize(config.getInt("mysql.pool.taille-max", 10));
        hikariConfig.setMinimumIdle(config.getInt("mysql.pool.taille-min-idle", 2));
        hikariConfig.setConnectionTimeout(config.getLong("mysql.pool.timeout-connexion-ms", 10000));
        hikariConfig.setIdleTimeout(config.getLong("mysql.pool.timeout-idle-ms", 600000));
        hikariConfig.setMaxLifetime(config.getLong("mysql.pool.duree-vie-max-ms", 1800000));
        hikariConfig.setPoolName("Hub-MySQL-Pool");

        try {
            dataSource = new HikariDataSource(hikariConfig);
            createTables();
            logger.info("Connexion à la base MySQL réussie (" + host + ":" + port + "/" + database + ")");
        } catch (Exception e) {
            logger.severe("Impossible de se connecter à la base MySQL : " + e.getMessage());
            logger.severe("Vérifie que le serveur MySQL est démarré et que database.yml contient les bons identifiants.");
        }
    }

    /** Ouvre une connexion depuis le pool. À fermer (try-with-resources) après usage. */
    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void createTables() throws SQLException {
        createEconomyTable();
        createPetsTable();
        createUpgradesTable();
        createCosmeticsTable();
        createRanksTable();
        createPlayerRanksTable();
        ensureDefaultRankExists();
        createParkourTimesTable();
        createParkourInventoryBackupTable();
    }

    private void createEconomyTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS economy (
                uuid VARCHAR(36) PRIMARY KEY,
                balance DOUBLE NOT NULL DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createPetsTable() throws SQLException {
        // Une ligne par pet possédé par un joueur. "equipped" vaut 1 pour
        // au plus un pet par joueur (celui actuellement équipé), 0 sinon.
        String sql = """
            CREATE TABLE IF NOT EXISTS pets (
                uuid VARCHAR(36) NOT NULL,
                pet_id VARCHAR(64) NOT NULL,
                equipped TINYINT NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, pet_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createUpgradesTable() throws SQLException {
        // Une ligne par "amélioration" achetée une fois pour toutes par un joueur
        // (ex: "super-saut"). La POSSESSION (achat) est permanente ; l'état
        // "enabled" est un interrupteur ON/OFF indépendant que le joueur peut
        // basculer librement sans perdre la possession — voir UpgradeManager.
        String sql = """
            CREATE TABLE IF NOT EXISTS upgrades (
                uuid VARCHAR(36) NOT NULL,
                upgrade_id VARCHAR(64) NOT NULL,
                enabled TINYINT NOT NULL DEFAULT 1,
                PRIMARY KEY (uuid, upgrade_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createCosmeticsTable() throws SQLException {
        // Une ligne par cosmétique possédé par un joueur, regroupé par "category"
        // (trail, tag, compass-skin...). "equipped" vaut 1 pour au plus UN
        // cosmétique équipé PAR CATÉGORIE et par joueur.
        String sql = """
            CREATE TABLE IF NOT EXISTS cosmetics (
                uuid VARCHAR(36) NOT NULL,
                category VARCHAR(32) NOT NULL,
                cosmetic_id VARCHAR(64) NOT NULL,
                equipped TINYINT NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, category, cosmetic_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    // ---------------------------------------------------------------
    // Économie (solde en Cristaux)
    // ---------------------------------------------------------------

    /**
     * Récupère le solde d'un joueur. Si le joueur n'existe pas encore en base,
     * retourne 0.0 (sans créer de ligne — la création se fait via setBalance).
     */
    public double getBalance(UUID uuid) {
        String sql = "SELECT balance FROM economy WHERE uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getDouble("balance");
            }
            return 0.0;
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture du solde : " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Définit le solde d'un joueur (crée la ligne si elle n'existe pas,
     * la met à jour sinon) via un upsert MySQL (INSERT ... ON DUPLICATE KEY UPDATE).
     */
    public void setBalance(UUID uuid, double amount) {
        String sql = """
            INSERT INTO economy (uuid, balance) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE balance = VALUES(balance);
            """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de l'écriture du solde : " + e.getMessage());
        }
    }

    /**
     * Retourne tous les soldes de la base, sous forme de Map UUID -> solde.
     * Utilisé pour le classement /coins baltop.
     */
    public java.util.Map<UUID, Double> getAllBalances() {
        java.util.Map<UUID, Double> result = new java.util.HashMap<>();
        String sql = "SELECT uuid, balance FROM economy";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                double balance = rs.getDouble("balance");
                result.put(uuid, balance);
            }
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture de tous les soldes : " + e.getMessage());
        }
        return result;
    }

    /**
     * Remet tous les soldes à 0. Utilisé pour /coins reset.
     */
    public void resetAll() {
        String sql = "UPDATE economy SET balance = 0";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            logger.severe("Erreur lors du reset de l'économie : " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Gestion des pets possédés / équipés
    // ---------------------------------------------------------------

    /**
     * Enregistre qu'un joueur possède ce pet (achat). Ne fait rien si
     * la ligne existe déjà (clé primaire uuid+pet_id, via INSERT IGNORE).
     */
    public void grantPet(UUID uuid, String petId) {
        String sql = "INSERT IGNORE INTO pets (uuid, pet_id, equipped) VALUES (?, ?, 0)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, petId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de l'attribution d'un pet : " + e.getMessage());
        }
    }

    /**
     * Retourne l'ensemble des ids de pets possédés par un joueur.
     */
    public java.util.Set<String> getOwnedPets(UUID uuid) {
        java.util.Set<String> result = new java.util.HashSet<>();
        String sql = "SELECT pet_id FROM pets WHERE uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(rs.getString("pet_id"));
            }
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture des pets possédés : " + e.getMessage());
        }
        return result;
    }

    /**
     * Retourne l'id du pet actuellement marqué comme équipé pour ce joueur,
     * ou null s'il n'en a aucun.
     */
    public String getEquippedPet(UUID uuid) {
        String sql = "SELECT pet_id FROM pets WHERE uuid = ? AND equipped = 1 LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("pet_id");
            }
            return null;
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture du pet équipé : " + e.getMessage());
            return null;
        }
    }

    /**
     * Marque un pet comme équipé pour ce joueur, et désélectionne tous les
     * autres pets de ce joueur (un seul pet équipé à la fois).
     * Passer petId = null désélectionne simplement tout (équivalent à "aucun pet équipé").
     */
    public void setEquippedPet(UUID uuid, String petId) {
        String clearSql = "UPDATE pets SET equipped = 0 WHERE uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(clearSql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la désélection des pets : " + e.getMessage());
            return;
        }

        if (petId == null) return;

        String setSql = "UPDATE pets SET equipped = 1 WHERE uuid = ? AND pet_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(setSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, petId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la sélection du pet équipé : " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Améliorations achetées une fois pour toutes (ex: super-saut)
    // ---------------------------------------------------------------

    /**
     * Vrai si le joueur a déjà acheté cette amélioration (achat permanent,
     * valable pour la vie — pas de notion d'expiration ou d'équipement).
     */
    public boolean hasUpgrade(UUID uuid, String upgradeId) {
        String sql = "SELECT 1 FROM upgrades WHERE uuid = ? AND upgrade_id = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, upgradeId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture d'une amélioration : " + e.getMessage());
            return false;
        }
    }

    /**
     * Retourne l'ensemble des ids d'améliorations possédées par un joueur,
     * en une seule requête (utilisé pour peupler le cache d'UpgradeManager).
     */
    public java.util.Set<String> getOwnedUpgrades(UUID uuid) {
        java.util.Set<String> result = new java.util.HashSet<>();
        String sql = "SELECT upgrade_id FROM upgrades WHERE uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(rs.getString("upgrade_id"));
            }
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture des améliorations possédées : " + e.getMessage());
        }
        return result;
    }

    /**
     * Enregistre l'achat définitif d'une amélioration pour ce joueur.
     * Ne fait rien si l'achat existe déjà (clé primaire uuid+upgrade_id).
     * L'upgrade est activé par défaut (enabled = 1) à l'achat.
     */
    public void grantUpgrade(UUID uuid, String upgradeId) {
        String sql = "INSERT IGNORE INTO upgrades (uuid, upgrade_id, enabled) VALUES (?, ?, 1)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, upgradeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de l'attribution d'une amélioration : " + e.getMessage());
        }
    }

    /**
     * Vrai si l'upgrade est actuellement activé pour ce joueur (toggle ON/OFF,
     * indépendant de la possession). Si le joueur ne possède pas l'upgrade,
     * retourne true par défaut (valeur sans effet puisque has() sera vérifié
     * avant de toute façon, mais évite un null inutile à gérer côté appelant).
     */
    public boolean isUpgradeEnabled(UUID uuid, String upgradeId) {
        String sql = "SELECT enabled FROM upgrades WHERE uuid = ? AND upgrade_id = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, upgradeId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt("enabled") != 0;
            }
            return true;
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture de l'état d'une amélioration : " + e.getMessage());
            return true;
        }
    }

    /** Active ou désactive un upgrade déjà possédé, sans toucher à la possession elle-même. */
    public void setUpgradeEnabled(UUID uuid, String upgradeId, boolean enabled) {
        String sql = "UPDATE upgrades SET enabled = ? WHERE uuid = ? AND upgrade_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, uuid.toString());
            ps.setString(3, upgradeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors du changement d'état d'une amélioration : " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Cosmétiques (achat multiple par catégorie, un seul équipé par catégorie)
    // ---------------------------------------------------------------

    /**
     * Enregistre qu'un joueur possède ce cosmétique dans cette catégorie
     * (achat). Ne fait rien si la ligne existe déjà.
     */
    public void grantCosmetic(UUID uuid, String category, String cosmeticId) {
        String sql = "INSERT IGNORE INTO cosmetics (uuid, category, cosmetic_id, equipped) VALUES (?, ?, ?, 0)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, category);
            ps.setString(3, cosmeticId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de l'attribution d'un cosmétique : " + e.getMessage());
        }
    }

    /**
     * Retourne l'ensemble des ids de cosmétiques possédés par un joueur,
     * pour une catégorie donnée (ex: "trail", "tag", "compass-skin").
     */
    public java.util.Set<String> getOwnedCosmetics(UUID uuid, String category) {
        java.util.Set<String> result = new java.util.HashSet<>();
        String sql = "SELECT cosmetic_id FROM cosmetics WHERE uuid = ? AND category = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, category);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(rs.getString("cosmetic_id"));
            }
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture des cosmétiques possédés : " + e.getMessage());
        }
        return result;
    }

    /**
     * Retourne l'id du cosmétique actuellement équipé pour ce joueur dans
     * cette catégorie, ou null s'il n'en a aucun.
     */
    public String getEquippedCosmetic(UUID uuid, String category) {
        String sql = "SELECT cosmetic_id FROM cosmetics WHERE uuid = ? AND category = ? AND equipped = 1 LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, category);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("cosmetic_id");
            }
            return null;
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture du cosmétique équipé : " + e.getMessage());
            return null;
        }
    }

    /**
     * Marque un cosmétique comme équipé pour ce joueur dans cette catégorie,
     * et désélectionne tous les autres cosmétiques de la même catégorie pour
     * ce joueur. Passer cosmeticId = null désélectionne simplement tout pour
     * cette catégorie (équivalent à "aucun cosmétique équipé").
     */
    public void setEquippedCosmetic(UUID uuid, String category, String cosmeticId) {
        String clearSql = "UPDATE cosmetics SET equipped = 0 WHERE uuid = ? AND category = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(clearSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, category);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la désélection des cosmétiques : " + e.getMessage());
            return;
        }

        if (cosmeticId == null) return;

        String setSql = "UPDATE cosmetics SET equipped = 1 WHERE uuid = ? AND category = ? AND cosmetic_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(setSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, category);
            ps.setString(3, cosmeticId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la sélection du cosmétique équipé : " + e.getMessage());
        }
    }

    /**
     * Ferme le pool de connexions. Doit être appelée dans onDisable().
     */
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
    // ---------------------------------------------------------------
// Ranks et permissions
// ---------------------------------------------------------------

    private void createRanksTable() throws SQLException {
        // Un rank = un id technique ("vip", "admin"...), un préfixe d'affichage,
        // un poids (pour départager l'affichage si besoin plus tard, ex: scoreboard/tab),
        // et la liste de ses permissions stockée en une seule colonne texte
        // (une permission par ligne — suffisant ici, pas besoin d'une table séparée
        // tant que le nombre de permissions par rank reste raisonnable).
        String sql = """
        CREATE TABLE IF NOT EXISTS ranks (
            rank_id VARCHAR(32) PRIMARY KEY,
            prefix VARCHAR(64) NOT NULL DEFAULT '',
            poids INT NOT NULL DEFAULT 0,
            permissions TEXT NOT NULL DEFAULT ''
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createPlayerRanksTable() throws SQLException {
        // Un joueur a AU PLUS un rank actif (clé primaire = uuid seul, pas
        // uuid+rank_id). Si on veut du cumul de ranks plus tard, il faudra
        // changer cette clé primaire en (uuid, rank_id) — à garder en tête.
        String sql = """
        CREATE TABLE IF NOT EXISTS player_ranks (
            uuid VARCHAR(36) PRIMARY KEY,
            rank_id VARCHAR(32) NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /** Crée le rank par défaut "joueur" s'il n'existe pas encore (rank de base, sans permission spéciale). */
    private void ensureDefaultRankExists() throws SQLException {
        String sql = "INSERT IGNORE INTO ranks (rank_id, prefix, poids, permissions) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "joueur");
            ps.setString(2, "&7[Joueur]");
            ps.setInt(3, 0);
            ps.setString(4, "");
            ps.executeUpdate();
        }
    }

    /** Crée un rank, ou met à jour son préfixe/poids s'il existe déjà (les permissions ne sont pas touchées ici). */
    public void createOrUpdateRank(String rankId, String prefix, int poids) {
        String sql = """
        INSERT INTO ranks (rank_id, prefix, poids, permissions) VALUES (?, ?, ?, '')
        ON DUPLICATE KEY UPDATE prefix = VALUES(prefix), poids = VALUES(poids);
        """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rankId);
            ps.setString(2, prefix);
            ps.setInt(3, poids);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la création/mise à jour d'un rank : " + e.getMessage());
        }
    }

    /** Supprime un rank. Les joueurs qui l'avaient devront être réassignés manuellement (pas de cascade automatique). */
    public boolean deleteRank(String rankId) {
        String sql = "DELETE FROM ranks WHERE rank_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rankId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.severe("Erreur lors de la suppression d'un rank : " + e.getMessage());
            return false;
        }
    }

    /** Ajoute une permission à un rank (ne fait rien si elle y est déjà). */
    public void addPermissionToRank(String rankId, String permission) {
        RankData data = getRank(rankId);
        if (data == null) return;
        if (data.permissions.contains(permission)) return;

        java.util.List<String> updated = new java.util.ArrayList<>(data.permissions);
        updated.add(permission);
        savePermissions(rankId, updated);
    }

    /** Retire une permission d'un rank. */
    public void removePermissionFromRank(String rankId, String permission) {
        RankData data = getRank(rankId);
        if (data == null) return;

        java.util.List<String> updated = new java.util.ArrayList<>(data.permissions);
        updated.remove(permission);
        savePermissions(rankId, updated);
    }

    private void savePermissions(String rankId, java.util.List<String> permissions) {
        String joined = String.join("\n", permissions);
        String sql = "UPDATE ranks SET permissions = ? WHERE rank_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, joined);
            ps.setString(2, rankId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de l'enregistrement des permissions d'un rank : " + e.getMessage());
        }
    }

    /** Petite structure de transport pour représenter un rank lu en base (prefix, poids, permissions). */
    public static class RankData {
        public final String rankId;
        public final String prefix;
        public final int poids;
        public final java.util.List<String> permissions;

        public RankData(String rankId, String prefix, int poids, java.util.List<String> permissions) {
            this.rankId = rankId;
            this.prefix = prefix;
            this.poids = poids;
            this.permissions = permissions;
        }
    }

    /** Lit un rank précis depuis la base, ou null s'il n'existe pas. */
    public RankData getRank(String rankId) {
        String sql = "SELECT prefix, poids, permissions FROM ranks WHERE rank_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rankId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String perms = rs.getString("permissions");
                java.util.List<String> permList = perms.isEmpty()
                        ? java.util.List.of()
                        : java.util.Arrays.asList(perms.split("\n"));
                return new RankData(rankId, rs.getString("prefix"), rs.getInt("poids"), permList);
            }
            return null;
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture d'un rank : " + e.getMessage());
            return null;
        }
    }

    /** Retourne tous les ranks existants. Utilisé pour /rank list et pour précharger le cache. */
    public java.util.List<RankData> getAllRanks() {
        java.util.List<RankData> result = new java.util.ArrayList<>();
        String sql = "SELECT rank_id, prefix, poids, permissions FROM ranks";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String perms = rs.getString("permissions");
                java.util.List<String> permList = perms.isEmpty()
                        ? java.util.List.of()
                        : java.util.Arrays.asList(perms.split("\n"));
                result.add(new RankData(rs.getString("rank_id"), rs.getString("prefix"), rs.getInt("poids"), permList));
            }
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture de tous les ranks : " + e.getMessage());
        }
        return result;
    }

    /** Retourne le rank_id du joueur, ou "joueur" (rank par défaut) s'il n'a aucune ligne en base. */
    public String getPlayerRankId(UUID uuid) {
        String sql = "SELECT rank_id FROM player_ranks WHERE uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("rank_id");
            }
            return "joueur";
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture du rank d'un joueur : " + e.getMessage());
            return "joueur";
        }
    }

    /** Définit le rank d'un joueur (crée la ligne si besoin, la remplace sinon — un seul rank actif à la fois). */
    public void setPlayerRank(UUID uuid, String rankId) {
        String sql = """
        INSERT INTO player_ranks (uuid, rank_id) VALUES (?, ?)
        ON DUPLICATE KEY UPDATE rank_id = VALUES(rank_id);
        """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, rankId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de l'attribution d'un rank : " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Parkour : meilleurs temps et sauvegarde d'inventaire pendant un run
    // ---------------------------------------------------------------

    private void createParkourTimesTable() throws SQLException {
        // Un meilleur temps par joueur ET par parkour (clé composite), pour
        // pouvoir avoir plusieurs parkours différents plus tard sans collision.
        // best_time_ms : durée en millisecondes (plus précis qu'en secondes,
        // utile pour départager des temps très proches au classement).
        String sql = """
            CREATE TABLE IF NOT EXISTS parkour_times (
                uuid VARCHAR(36) NOT NULL,
                parkour_id VARCHAR(32) NOT NULL,
                best_time_ms BIGINT NOT NULL,
                PRIMARY KEY (uuid, parkour_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createParkourInventoryBackupTable() throws SQLException {
        // Une ligne par joueur actuellement "dans" un parkour (présente
        // UNIQUEMENT pendant que le joueur est dans le monde parkour, supprimée
        // dès qu'il en ressort normalement). Sert de filet de sécurité : si le
        // serveur crash pendant qu'un joueur est dans le parkour, son inventaire
        // du hub n'est jamais perdu puisqu'il est sauvegardé ici, en base, et
        // pas seulement en mémoire.
        //
        // inventory_data / armor_data : contenu sérialisé (Base64) de
        // l'inventaire principal (36 slots) et de l'armure (4 slots) au moment
        // de l'entrée dans le parkour — voir InventorySerialization.
        // had_pet_equipped / had_trail_equipped : mémorisent si le pet/trail du
        // joueur était actif avant d'entrer, pour savoir s'il faut le relancer
        // à la sortie (PetManager/TrailEngine eux-mêmes ne perdent jamais cette
        // info en base, mais ce flag évite un appel inutile si rien n'était actif).
        String sql = """
            CREATE TABLE IF NOT EXISTS parkour_inventory_backup (
                uuid VARCHAR(36) PRIMARY KEY,
                inventory_data TEXT NOT NULL,
                armor_data TEXT NOT NULL,
                had_pet_equipped TINYINT NOT NULL DEFAULT 0,
                had_trail_equipped TINYINT NOT NULL DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Retourne le meilleur temps (en ms) du joueur sur ce parkour, ou null
     * s'il n'a encore jamais terminé ce parkour.
     */
    public Long getBestTime(UUID uuid, String parkourId) {
        String sql = "SELECT best_time_ms FROM parkour_times WHERE uuid = ? AND parkour_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, parkourId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getLong("best_time_ms");
            }
            return null;
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture du meilleur temps parkour : " + e.getMessage());
            return null;
        }
    }

    /**
     * Enregistre un nouveau temps SEULEMENT s'il est meilleur (plus petit) que
     * le temps déjà enregistré, ou s'il n'y en avait aucun. Retourne true si
     * un nouveau record a été établi (utile pour afficher un message spécial).
     */
    public boolean setBestTimeIfBetter(UUID uuid, String parkourId, long timeMs) {
        Long actuel = getBestTime(uuid, parkourId);
        if (actuel != null && actuel <= timeMs) {
            return false; // le temps existant est déjà aussi bon ou meilleur
        }

        String sql = """
            INSERT INTO parkour_times (uuid, parkour_id, best_time_ms) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE best_time_ms = VALUES(best_time_ms);
            """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, parkourId);
            ps.setLong(3, timeMs);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.severe("Erreur lors de l'enregistrement du temps parkour : " + e.getMessage());
            return false;
        }
    }

    /**
     * Retourne le classement complet d'un parkour (uuid -> meilleur temps en
     * ms), trié du plus rapide au plus lent. Utilisé par /parkour top.
     */
    public java.util.List<java.util.Map.Entry<UUID, Long>> getTopTimes(String parkourId, int limite) {
        java.util.List<java.util.Map.Entry<UUID, Long>> result = new java.util.ArrayList<>();
        String sql = "SELECT uuid, best_time_ms FROM parkour_times WHERE parkour_id = ? ORDER BY best_time_ms ASC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, parkourId);
            ps.setInt(2, limite);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                long temps = rs.getLong("best_time_ms");
                result.add(java.util.Map.entry(uuid, temps));
            }
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture du classement parkour : " + e.getMessage());
        }
        return result;
    }

    /**
     * Sauvegarde l'inventaire/armure d'un joueur juste avant qu'il entre dans
     * le parkour (inventaire vidé ensuite côté appelant). Écrase toute
     * sauvegarde précédente pour ce joueur s'il y en avait une (ne devrait
     * normalement pas arriver — un joueur ne peut être que dans un seul
     * parkour à la fois — mais évite un état incohérent en cas de bug).
     */
    public void saveInventoryBackup(UUID uuid, String inventoryData, String armorData,
                                    boolean hadPetEquipped, boolean hadTrailEquipped) {
        String sql = """
            INSERT INTO parkour_inventory_backup
                (uuid, inventory_data, armor_data, had_pet_equipped, had_trail_equipped)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                inventory_data = VALUES(inventory_data),
                armor_data = VALUES(armor_data),
                had_pet_equipped = VALUES(had_pet_equipped),
                had_trail_equipped = VALUES(had_trail_equipped);
            """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, inventoryData);
            ps.setString(3, armorData);
            ps.setInt(4, hadPetEquipped ? 1 : 0);
            ps.setInt(5, hadTrailEquipped ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la sauvegarde de l'inventaire parkour : " + e.getMessage());
        }
    }

    /** Petite structure de transport pour une sauvegarde d'inventaire parkour lue en base. */
    public static class InventoryBackup {
        public final String inventoryData;
        public final String armorData;
        public final boolean hadPetEquipped;
        public final boolean hadTrailEquipped;

        public InventoryBackup(String inventoryData, String armorData,
                               boolean hadPetEquipped, boolean hadTrailEquipped) {
            this.inventoryData = inventoryData;
            this.armorData = armorData;
            this.hadPetEquipped = hadPetEquipped;
            this.hadTrailEquipped = hadTrailEquipped;
        }
    }

    /**
     * Lit la sauvegarde d'inventaire d'un joueur, ou null s'il n'en a aucune
     * (= il n'est pas/plus dans un parkour).
     */
    public InventoryBackup getInventoryBackup(UUID uuid) {
        String sql = "SELECT inventory_data, armor_data, had_pet_equipped, had_trail_equipped " +
                "FROM parkour_inventory_backup WHERE uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new InventoryBackup(
                        rs.getString("inventory_data"),
                        rs.getString("armor_data"),
                        rs.getInt("had_pet_equipped") != 0,
                        rs.getInt("had_trail_equipped") != 0
                );
            }
            return null;
        } catch (SQLException e) {
            logger.severe("Erreur lors de la lecture de l'inventaire parkour : " + e.getMessage());
            return null;
        }
    }

    /**
     * Vrai si ce joueur a actuellement une sauvegarde d'inventaire en attente
     * (= il est censé être dans le parkour). Utilisé au PlayerJoinEvent pour
     * détecter une reconnexion après crash et restaurer automatiquement.
     */
    public boolean hasInventoryBackup(UUID uuid) {
        return getInventoryBackup(uuid) != null;
    }

    /** Supprime la sauvegarde d'inventaire d'un joueur, une fois restaurée avec succès. */
    public void deleteInventoryBackup(UUID uuid) {
        String sql = "DELETE FROM parkour_inventory_backup WHERE uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la suppression de la sauvegarde d'inventaire parkour : " + e.getMessage());
        }
    }
}