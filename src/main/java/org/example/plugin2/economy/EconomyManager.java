package org.example.plugin2.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EconomyManager {

    private final Database database;

    // Cache mémoire : évite de re-lire la base SQLite à chaque fois qu'on a besoin du solde.
    // Clé = UUID du joueur, valeur = solde actuel.
    private final Map<UUID, Double> cache = new HashMap<>();

    public EconomyManager(Database database) {
        this.database = database;
    }

    /**
     * Retourne le solde d'un joueur. Lit le cache si possible,
     * sinon va chercher en base et met à jour le cache.
     */
    public double getBalance(UUID uuid) {
        if (cache.containsKey(uuid)) {
            return cache.get(uuid);
        }
        double balance = database.getBalance(uuid);
        cache.put(uuid, balance);
        return balance;
    }

    /**
     * Définit un nouveau solde (écrase l'ancien).
     */
    public void setBalance(UUID uuid, double amount) {
        // On empêche un solde négatif par défaut — règle métier simple à adapter si besoin
        double safeAmount = Math.max(0, amount);
        cache.put(uuid, safeAmount);
        database.setBalance(uuid, safeAmount);
    }

    /**
     * Ajoute un montant au solde existant.
     */
    public void addBalance(UUID uuid, double amount) {
        double newBalance = getBalance(uuid) + amount;
        setBalance(uuid, newBalance);
    }

    /**
     * Retire un montant au solde existant. Ne descend jamais sous 0.
     * Retourne false si le joueur n'a pas assez d'argent (opération refusée).
     */
    public boolean removeBalance(UUID uuid, double amount) {
        double current = getBalance(uuid);
        if (current < amount) {
            return false; // pas assez d'argent, on refuse l'opération
        }
        setBalance(uuid, current - amount);
        return true;
    }

    /**
     * Vérifie si un joueur a au moins ce montant.
     */
    public boolean has(UUID uuid, double amount) {
        return getBalance(uuid) >= amount;
    }

    /**
     * Réinitialise tous les soldes à 0, en base ET dans le cache mémoire.
     */
    public void resetAll(Database database) {
        database.resetAll();
        cache.clear();
    }
}