package org.example.plugin2.economy;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

public class Database {

    private final Logger logger;
    private final String dbPath;
    private Connection connection;

    public Database(File pluginFolder, Logger logger) {
        this.logger = logger;
        // pluginFolder est le dossier "plugins/Plugin2/" généré par Bukkit pour notre plugin
        this.dbPath = pluginFolder.getAbsolutePath() + File.separator + "economy.db";
    }

    /**
     * Ouvre la connexion à la base SQLite et crée la table si besoin.
     * Doit être appelée une seule fois, dans onEnable().
     */
    public void connect() {
        try {
            // S'assure que le dossier du plugin existe avant de créer le fichier .db dedans
            File folder = new File(dbPath).getParentFile();
            if (!folder.exists()) {
                folder.mkdirs();
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            createTable();
            logger.info("Connexion à la base SQLite réussie (" + dbPath + ")");
        } catch (SQLException e) {
            logger.severe("Impossible de se connecter à la base SQLite : " + e.getMessage());
        }
    }

    private void createTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS economy (
                uuid TEXT PRIMARY KEY,
                balance REAL NOT NULL DEFAULT 0
            );
            """;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
        createPetsTable();
    }

    private void createPetsTable() throws SQLException {
        // Une ligne par pet possédé par un joueur. "equipped" vaut 1 pour
        // au plus un pet par joueur (celui actuellement équipé), 0 sinon.
        String sql = """
            CREATE TABLE IF NOT EXISTS pets (
                uuid TEXT NOT NULL,
                pet_id TEXT NOT NULL,
                equipped INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, pet_id)
            );
            """;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Récupère le solde d'un joueur. Si le joueur n'existe pas encore en base,
     * retourne 0.0 (sans créer de ligne — la création se fait via setBalance).
     */
    public double getBalance(UUID uuid) {
        String sql = "SELECT balance FROM economy WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
     * la met à jour sinon). C'est ce qu'on appelle un "upsert".
     */
    public void setBalance(UUID uuid, double amount) {
        String sql = """
            INSERT INTO economy (uuid, balance) VALUES (?, ?)
            ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance;
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
        try (Statement stmt = connection.createStatement();
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
        try (Statement stmt = connection.createStatement()) {
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
     * la ligne existe déjà (clé primaire uuid+pet_id).
     */
    public void grantPet(UUID uuid, String petId) {
        String sql = "INSERT OR IGNORE INTO pets (uuid, pet_id, equipped) VALUES (?, ?, 0)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
        try (PreparedStatement ps = connection.prepareStatement(clearSql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la désélection des pets : " + e.getMessage());
            return;
        }

        if (petId == null) return;

        String setSql = "UPDATE pets SET equipped = 1 WHERE uuid = ? AND pet_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(setSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, petId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Erreur lors de la sélection du pet équipé : " + e.getMessage());
        }
    }

    /**
     * Ferme la connexion. Doit être appelée dans onDisable().
     */
    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.warning("Erreur lors de la fermeture de la base : " + e.getMessage());
        }
    }
}