package org.example.plugin2.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.example.plugin2.Plugin2;
import org.example.plugin2.economy.Database;
import org.example.plugin2.ranks.RankManager;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Gère le scoreboard latéral (sidebar) affiché aux joueurs dans les mondes
 * listés sous "mondes-actifs" dans scoreboard.yml.
 *
 * Suit le même principe de chargement que HubWorldManager/MotdListener pour
 * le fichier de config. Contrairement à EconomyManager/UpgradeManager/RankManager,
 * ce manager n'a PAS de cache mémoire à lui (il lit juste les autres managers,
 * déjà cachés de leur côté) — pas besoin de reloadAll() pour des données,
 * seulement d'un reload() pour recharger scoreboard.yml depuis le disque.
 *
 * Une seule instance de Scoreboard Bukkit est créée PAR JOUEUR (et non une
 * seule pour tout le serveur) puisque le contenu affiché est personnalisé
 * (pseudo, rank, solde de Cristaux propres à chacun).
 */
public class ScoreboardManager {

    private static final String OBJECTIVE_ID = "plugin2_sidebar";

    private final Plugin2 plugin;
    private final Logger logger;
    private final File configFile;

    private YamlConfiguration config;
    private Set<String> mondesActifs = new HashSet<>();
    private String titreBrut = "";
    private List<String> lignesBrutes = new ArrayList<>();
    private long intervalleTicks = 20L;

    // Dernier contenu affiché par joueur, pour ne réécrire une ligne que si
    // sa valeur a changé (évite tout scintillement et des appels Bukkit inutiles).
    private final Map<UUID, List<String>> dernierAffichage = new HashMap<>();

    private final SimpleDateFormat formatHeure = new SimpleDateFormat("HH:mm");

    public ScoreboardManager(Plugin2 plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config/scoreboard.yml");
        load();
    }

    /** Charge (ou recharge) scoreboard.yml depuis le disque. */
    public void load() {
        if (!configFile.exists()) {
            plugin.saveResource("config/scoreboard.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        InputStream defaultStream = plugin.getResource("config/scoreboard.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }

        mondesActifs = new HashSet<>(config.getStringList("mondes-actifs"));
        titreBrut = config.getString("titre", "&6&l★ Serveur ★");
        lignesBrutes = config.getStringList("lignes");
        intervalleTicks = config.getLong("intervalle-rafraichissement-ticks", 20L);

        if (mondesActifs.isEmpty()) {
            logger.warning("scoreboard.yml : aucun monde actif défini — le scoreboard ne s'affichera nulle part.");
        }
    }

    /** Recharge le fichier depuis le disque (appelé depuis Plugin2.reloadAllCaches()). */
    public void reload() {
        load();
        // Force un réaffichage complet (titre + lignes) à tous les joueurs actuellement
        // suivis, au cas où le contenu texte aurait changé sans changement de valeur.
        dernierAffichage.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (estDansMondeActif(player.getWorld())) {
                update(player);
            }
        }
    }

    public long getIntervalleTicks() {
        return intervalleTicks;
    }

    public boolean estDansMondeActif(World world) {
        return world != null && mondesActifs.contains(world.getName());
    }

    /**
     * Crée et affiche le scoreboard à un joueur. À appeler quand un joueur
     * entre dans un monde actif (connexion, changement de monde).
     */
    public void afficher(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(
                OBJECTIVE_ID, "dummy", versComponent(titreBrut));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        player.setScoreboard(scoreboard);

        dernierAffichage.remove(player.getUniqueId());
        update(player);
    }

    /**
     * Retire le scoreboard d'un joueur (remet le scoreboard vide par défaut).
     * À appeler quand un joueur quitte un monde actif, ou se déconnecte.
     */
    public void retirer(Player player) {
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        dernierAffichage.remove(player.getUniqueId());
    }

    /** Nettoie l'état interne d'un joueur à sa déconnexion (évite une fuite mémoire). */
    public void clearOnQuit(Player player) {
        dernierAffichage.remove(player.getUniqueId());
    }

    /**
     * Recalcule les valeurs courantes et ne réécrit QUE les lignes dont le
     * texte a changé depuis le dernier passage. Suppose que le joueur a déjà
     * un scoreboard avec l'objective OBJECTIVE_ID actif sur la sidebar
     * (voir afficher()) — sinon ne fait rien.
     */
    public void update(Player player) {
        Scoreboard scoreboard = player.getScoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE_ID);
        if (objective == null) return; // joueur sans notre scoreboard actif, on ignore

        List<String> nouvellesLignes = construireLignesColorees(player);
        List<String> precedentes = dernierAffichage.get(player.getUniqueId());

        if (precedentes != null && precedentes.equals(nouvellesLignes)) {
            return; // rien n'a changé, on ne touche à rien (évite tout scintillement)
        }

        // Le scoreboard Bukkit associe un "score" (entier) à chaque ligne de texte
        // pour ordonner l'affichage de haut en bas — c'est un détournement
        // historique de l'API (pensée à l'origine pour des scores de mini-jeu,
        // pas du texte libre). On efface tout puis on réécrit dans l'ordre, ce
        // qui reste très bon marché (quelques lignes) et évite de gérer un diff
        // ligne par ligne avec des entrées invisibles dupliquées.
        for (String entree : scoreboard.getEntries()) {
            scoreboard.resetScores(entree);
        }

        int score = nouvellesLignes.size();
        Set<String> entreesUtilisees = new HashSet<>();
        for (String ligne : nouvellesLignes) {
            // Deux lignes identiques (ex: deux lignes vides) doivent rester des
            // entrées DIFFÉRENTES pour le scoreboard — on rend chaque entrée
            // unique avec des codes couleur invisibles en fin de chaîne.
            String entree = rendreUnique(ligne, entreesUtilisees);
            entreesUtilisees.add(entree);
            objective.getScore(entree).setScore(score);
            score--;
        }

        dernierAffichage.put(player.getUniqueId(), nouvellesLignes);
    }

    // ---------------------------------------------------------------
    // Construction du contenu
    // ---------------------------------------------------------------

    private List<String> construireLignes(Player player) {
        Map<String, String> variables = construireVariables(player);
        List<String> resultat = new ArrayList<>();
        for (String ligneBrute : lignesBrutes) {
            resultat.add(remplacerVariables(ligneBrute, variables));
        }
        return resultat;
    }
    /**
     * Comme construireLignes(), mais convertit en plus les codes couleur
     * "&" en codes couleur réels "§" avant de renvoyer chaque ligne — c'est
     * cette version qui doit être utilisée pour l'AFFICHAGE (entrées du
     * scoreboard), sinon le client Minecraft affiche le "&" littéralement
     * au lieu d'appliquer la couleur (bug observé : "&fJoueur: &eElPepite94"
     * affiché tel quel au lieu d'être coloré).
     */
    private List<String> construireLignesColorees(Player player) {
        List<String> resultat = new ArrayList<>();
        for (String ligne : construireLignes(player)) {
            resultat.add(ChatColor.translateAlternateColorCodes('&', ligne));
        }
        return resultat;
    }

    private Map<String, String> construireVariables(Player player) {
        Map<String, String> variables = new HashMap<>();
        variables.put("pseudo", player.getName());
        variables.put("rank", rankDuJoueur(player));
        variables.put("cristaux", String.valueOf((int) plugin.getEconomyManager().getBalance(player.getUniqueId())));
        variables.put("en-ligne", String.valueOf(Bukkit.getOnlinePlayers().size()));
        variables.put("heure", formatHeure.format(new Date()));
        return variables;
    }

    private String rankDuJoueur(Player player) {
        RankManager rankManager = plugin.getRankManager();
        Database.RankData rank = rankManager.getPlayerRank(player.getUniqueId());
        return rank != null ? rank.rankId : RankManager.RANK_PAR_DEFAUT;
    }

    private String remplacerVariables(String ligne, Map<String, String> variables) {
        String resultat = ligne;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            resultat = resultat.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resultat;
    }

    /**
     * Rend une entrée de scoreboard unique en lui ajoutant des codes couleur
     * invisibles (&r répété) si elle est déjà utilisée — nécessaire car deux
     * lignes avec exactement le même texte (ex: deux lignes vides) doivent
     * pourtant être deux entrées distinctes pour le scoreboard.
     */
    private String rendreUnique(String ligne, Set<String> dejaUtilisees) {
        String candidate = ligne.isEmpty() ? " " : ligne;
        while (dejaUtilisees.contains(candidate)) {
            candidate = candidate + "§r";
        }
        return candidate;
    }

    private Component versComponent(String texteAvecCodesAmpersand) {
        String legacy = ChatColor.translateAlternateColorCodes('&', texteAvecCodesAmpersand);
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }
}