package org.example.plugin2.economy;

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
 * Couche d'accès à la base de données MySQL (remplace l'ancienne base SQLite
 * locale). Toutes les données (économie, pets, upgrades, cosmétiques) vivent
 * désormais dans une base MySQL externe, ce qui permet à plusieurs serveurs
 * Paper de partager les mêmes données joueur (ex: hub + survival derrière un
 * proxy BungeeCord/Velocity).
 *
 * Utilise HikariCP comme pool de connexions plutôt qu'une connexion unique :
 * indispensable avec MySQL puisque plusieurs requêtes peuvent arriver en
 * parallèle (plusieurs joueurs simultanés), contrairement à SQLite où une
 * connexion unique suffisait pour un fichier local.
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
        hikariConfig.setPoolName("Plugin2-MySQL-Pool");

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
    // Économie (solde en tals)
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
     * la met à jour sinon). Équivalent MySQL de l'upsert SQLite précédent :
     * INSERT ... ON DUPLICATE KEY UPDATE (au lieu de ON CONFLICT DO UPDATE).
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
     * la ligne existe déjà (clé primaire uuid+pet_id). Équivalent MySQL
     * de "INSERT OR IGNORE" SQLite.
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
}