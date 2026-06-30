package fr.aurel943.hub.parkour;

/**
 * Représente l'état d'un run de parkour EN COURS pour un joueur. Purement
 * en mémoire (contrairement à la sauvegarde d'inventaire, qui elle est en
 * base — voir Database.saveInventoryBackup) : si le serveur redémarre
 * pendant un run, le run lui-même est perdu (le joueur devra retoucher le
 * départ), mais son inventaire est récupéré normalement grâce à la BDD.
 *
 * "dernierCheckpointValide" vaut -1 tant qu'aucun checkpoint n'a été touché
 * (donc une chute ramène à la zone de départ). Une fois qu'un checkpoint
 * d'index N est validé, on ne descend jamais en dessous de N, même si le
 * joueur retouche un checkpoint antérieur en revenant en arrière.
 */
public class ParkourSession {

    private final String parkourId;
    private final long heureDepartMs;
    private int dernierCheckpointValide = -1;

    public ParkourSession(String parkourId) {
        this.parkourId = parkourId;
        this.heureDepartMs = System.currentTimeMillis();
    }

    public String getParkourId() {
        return parkourId;
    }

    /** Temps écoulé depuis le début du run, en millisecondes. */
    public long getTempsEcouleMs() {
        return System.currentTimeMillis() - heureDepartMs;
    }

    public int getDernierCheckpointValide() {
        return dernierCheckpointValide;
    }

    /**
     * Valide un checkpoint d'index donné. N'a d'effet que si cet index est
     * strictement supérieur au dernier checkpoint déjà validé (on ne revient
     * jamais en arrière sur la progression).
     */
    public void validerCheckpoint(int index) {
        if (index > dernierCheckpointValide) {
            dernierCheckpointValide = index;
        }
    }
}