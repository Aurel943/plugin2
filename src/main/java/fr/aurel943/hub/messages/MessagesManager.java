package fr.aurel943.hub.messages;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import fr.aurel943.hub.Hub;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Charge tous les textes du plugin depuis messages.yml et les envoie
 * formatés aux joueurs. Supporte les codes couleur classiques (§ ou &).
 *
 * Utilisation typique :
 *   messages.send(player, "coins.solde-personnel", Map.of("montant", "150 tals"));
 */
public class MessagesManager {

    private final Hub plugin;
    private final Logger logger;
    private YamlConfiguration config;
    private final File file;

    public MessagesManager(Hub plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.file = new File(plugin.getDataFolder(), "config/messages.yml");
        load();
    }

    /**
     * Charge (ou recharge) messages.yml depuis le disque.
     * Si le fichier n'existe pas encore dans le dossier du plugin,
     * il est copié depuis les ressources internes du jar.
     */
    public void load() {
        if (!file.exists()) {
            plugin.saveResource("config/messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);

        // Fusionne avec les valeurs par défaut embarquées dans le jar,
        // au cas où une clé manquerait dans le fichier existant sur le disque
        InputStream defaultStream = plugin.getResource("config/messages.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaultConfig);
        }

        logger.info("messages.yml chargé.");
    }

    /** Recharge le fichier depuis le disque (utile pour une commande /hub reload). */
    public void reload() {
        load();
    }

    /**
     * Récupère un texte brut (non formaté) depuis une clé du type "coins.pay.usage".
     * Retourne la clé elle-même entre crochets si elle est introuvable (facilite le debug).
     */
    public String raw(String path) {
        String value = config.getString(path);
        if (value == null) {
            logger.warning("Clé de message manquante dans messages.yml : " + path);
            return "[" + path + "]";
        }
        return value;
    }

    /**
     * Récupère un message, remplace les {variables}, traduit les couleurs (§ et &),
     * et retourne le texte final prêt à être envoyé.
     */
    public String get(String path, Map<String, String> placeholders) {
        String text = raw(path);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        // Traduit & en § (les deux fonctionnent ainsi dans le YAML), puis applique les couleurs
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public String get(String path) {
        return get(path, null);
    }

    /** Envoie directement un message formaté à un joueur/sender, sans variables. */
    public void send(CommandSender sender, String path) {
        sender.sendMessage(get(path));
    }

    /** Envoie directement un message formaté à un joueur/sender, avec variables. */
    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        sender.sendMessage(get(path, placeholders));
    }
}