package de.dorfnetwork.aiassistant;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class AIAssistant extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<UUID, List<String>> chatHistory = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> commandHistory = new ConcurrentHashMap<>();

    private final Map<UUID, Integer> dailyUsage = new ConcurrentHashMap<>();
    private final Set<UUID> optOutPlayers = ConcurrentHashMap.newKeySet();
    private LocalDate lastResetDate = LocalDate.now();

    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupDataFile();
        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        CommandMap commandMap = Bukkit.getCommandMap();
        Command aiCommand = new Command("ai", "KI-Assistent Befehl", "/ai <Frage|optout|reset>", List.of()) {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return AIAssistant.this.onCommand(sender, this, commandLabel, args);
            }

            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                List<String> completions = AIAssistant.this.onTabComplete(sender, this, alias, args);
                return completions != null ? completions : Collections.emptyList();
            }
        };

        commandMap.register("aiassistant", aiCommand);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    /* ---------------- Datenspeicherung (data.yml) ---------------- */

    private void setupDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Konnte data.yml nicht erstellen: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void loadData() {
        checkDailyReset();

        String savedDateStr = dataConfig.getString("last-reset-date");
        if (savedDateStr != null) {
            try {
                lastResetDate = LocalDate.parse(savedDateStr);
            } catch (Exception ignored) {}
        }

        checkDailyReset();

        optOutPlayers.clear();
        List<String> optOutList = dataConfig.getStringList("opt-out");
        for (String uuidStr : optOutList) {
            try {
                optOutPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {}
        }

        dailyUsage.clear();
        if (dataConfig.isConfigurationSection("daily-usage")) {
            for (String key : dataConfig.getConfigurationSection("daily-usage").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    int usage = dataConfig.getInt("daily-usage." + key, 0);
                    dailyUsage.put(uuid, usage);
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void saveData() {
        dataConfig.set("last-reset-date", lastResetDate.toString());

        List<String> optOutList = optOutPlayers.stream().map(UUID::toString).toList();
        dataConfig.set("opt-out", optOutList);

        dataConfig.set("daily-usage", null);
        for (Map.Entry<UUID, Integer> entry : dailyUsage.entrySet()) {
            dataConfig.set("daily-usage." + entry.getKey().toString(), entry.getValue());
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Fehler beim Speichern der data.yml: " + e.getMessage());
        }
    }

    /* ---------------- Events ---------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String messageText = LegacyComponentSerializer.legacyAmpersand().serialize(event.message());

        chatHistory.computeIfAbsent(player.getUniqueId(), k -> Collections.synchronizedList(new LinkedList<>()));
        List<String> history = chatHistory.get(player.getUniqueId());
        history.add(messageText);
        if (history.size() > 5) history.remove(0);

        if (optOutPlayers.contains(player.getUniqueId())) return;

        List<String> keywords = getConfig().getStringList("keywords");
        boolean matched = keywords.stream().anyMatch(kw -> messageText.toLowerCase().contains(kw.toLowerCase()));

        if (matched) {
            if (!checkDailyLimit(player)) {
                player.sendMessage(formatMsg("messages.limit-reached"));
                return;
            }
            incrementDailyLimit(player);

            sendAiRequest(player, messageText).thenAccept(response -> {
                if (response != null && !response.isBlank()) {
                    String formatted = getConfig().getString("messages.public-format", "&8[&bAI&8] &f{response}")
                            .replace("{response}", response)
                            .replace("{player}", player.getName());
                    Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(formatted));
                }
            });
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        commandHistory.computeIfAbsent(player.getUniqueId(), k -> Collections.synchronizedList(new LinkedList<>()));
        List<String> cmds = commandHistory.get(player.getUniqueId());
        cmds.add(event.getMessage());
        if (cmds.size() > 5) cmds.remove(0);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        chatHistory.remove(uuid);
        commandHistory.remove(uuid);
    }

    /* ---------------- Befehle ---------------- */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(formatMsg("messages.usage"));
            return true;
        }

        // Subcommand: /ai reset <spieler|all>
        if (args[0].equalsIgnoreCase("reset")) {
            if (!sender.hasPermission("aiassistant.admin.reset")) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cKeine Rechte dafür."));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cNutze: /ai reset <Spieler|all>"));
                return true;
            }

            if (args[1].equalsIgnoreCase("all")) {
                dailyUsage.clear();
                saveData();
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&aDas tägliche KI-Limit aller Spieler wurde zurückgesetzt."));
            } else {
                Player target = Bukkit.getPlayer(args[1]);
                UUID targetUUID = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                dailyUsage.remove(targetUUID);
                saveData();
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&aTägliches KI-Limit für &e" + args[1] + " &azurückgesetzt."));
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Unterbefehl kann nur von Spielern verwendet werden.");
            return true;
        }

        // Subcommand: /ai optout
        if (args[0].equalsIgnoreCase("optout") || args[0].equalsIgnoreCase("toggle")) {
            if (optOutPlayers.contains(player.getUniqueId())) {
                optOutPlayers.remove(player.getUniqueId());
                player.sendMessage(formatMsg("messages.optout-disabled"));
            } else {
                optOutPlayers.add(player.getUniqueId());
                player.sendMessage(formatMsg("messages.optout-enabled"));
            }
            saveData();
            return true;
        }

        String question = String.join(" ", args);

        if (!checkDailyLimit(player)) {
            player.sendMessage(formatMsg("messages.limit-reached"));
            return true;
        }
        incrementDailyLimit(player);

        player.sendMessage(formatMsg("messages.thinking"));

        sendAiRequest(player, question).thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                String formatted = getConfig().getString("messages.private-format", "&8[&bAI-Privat&8] &f{response}")
                        .replace("{response}", response);
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(formatted));
            } else {
                player.sendMessage(formatMsg("messages.error"));
            }
        });

        return true;
    }

    /* ---------------- Limits & Permissions ---------------- */

    private int getPlayerDailyLimit(Player player) {
        // 1. OPs, Admins & Bypass-Permissions erhalten unbegrenzten Zugriff (-1)
        if (player.isOp()
                || player.hasPermission("aiassistant.limit.bypass")
                || player.hasPermission("aiassistant.limit.unlimited")
                || player.hasPermission("aiassistant.admin")) {
            return -1; // -1 = unendlich
        }

        int highestLimit = -2; // -2 = keine Limit-Permission gesetzt

        // 2. Effektive Permissions des Spielers durchsuchen
        for (PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            if (!pai.getValue()) continue;

            String perm = pai.getPermission().toLowerCase();
            if (perm.startsWith("aiassistant.limit.")) {
                String valStr = perm.substring("aiassistant.limit.".length());

                // Unbegrenzt
                if (valStr.equals("unlimited") || valStr.equals("bypass") || valStr.equals("inf") || valStr.equals("-1")) {
                    return -1;
                }

                try {
                    int val = Integer.parseInt(valStr);
                    if (val == -1) return -1;
                    if (val >= 0 && val > highestLimit) {
                        highestLimit = val;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // Wenn eine explizite Permission gefunden wurde (z.B. aiassistant.limit.0 oder aiassistant.limit.50)
        if (highestLimit != -2) {
            return highestLimit;
        }

        // Fallback: Globales Limit aus config.yml
        return getConfig().getInt("daily-limit", 10);
    }

    private boolean checkDailyLimit(Player player) {
        checkDailyReset();
        int max = getPlayerDailyLimit(player);
        if (max == -1) return true;  // -1 = Unendlich
        if (max == 0) return false;  // 0 = Nutzung gesperrt
        return dailyUsage.getOrDefault(player.getUniqueId(), 0) < max;
    }

    private void incrementDailyLimit(Player player) {
        dailyUsage.merge(player.getUniqueId(), 1, Integer::sum);
        saveData();
    }

    private void checkDailyReset() {
        if (!LocalDate.now().equals(lastResetDate)) {
            dailyUsage.clear();
            lastResetDate = LocalDate.now();
            saveData();
        }
    }

    /* ---------------- KI API & Prompt ---------------- */

    private CompletableFuture<String> sendAiRequest(Player player, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiUrl = getConfig().getString("ollama.url", "https://api.ollama.com/v1/chat/completions");
                String apiKey = getConfig().getString("ollama.api-key", "");
                String model = getConfig().getString("ollama.model", "llama3");

                String systemPrompt = buildSystemPrompt(player);

                String jsonPayload = String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"%s\"},{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":false}",
                        escapeJson(model),
                        escapeJson(systemPrompt),
                        escapeJson(prompt)
                );

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

                if (!apiKey.isBlank()) {
                    builder.header("Authorization", "Bearer " + apiKey);
                }

                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String body = response.body();
                    int contentIdx = body.indexOf("\"content\":\"");
                    if (contentIdx != -1) {
                        int start = contentIdx + 11;
                        int end = body.indexOf("\"", start);
                        if (end != -1) {
                            return body.substring(start, end)
                                    .replace("\\n", "\n")
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\");
                        }
                    }
                }
            } catch (Exception e) {
                getLogger().severe("Fehler bei der Ollama API-Anfrage: " + e.getMessage());
            }
            return null;
        });
    }

    private String buildSystemPrompt(Player player) {
        checkDailyReset();
        String plugins = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(Plugin::getName)
                .collect(Collectors.joining(", "));

        // Alle Permissions des Spielers + vererbte Gruppen-Permissions (z.B. LuckPerms)
        String permissions = player.getEffectivePermissions().stream()
                .filter(PermissionAttachmentInfo::getValue)
                .map(PermissionAttachmentInfo::getPermission)
                .collect(Collectors.joining(", "));

        List<String> playerChat = chatHistory.getOrDefault(player.getUniqueId(), Collections.emptyList());
        List<String> playerCmds = commandHistory.getOrDefault(player.getUniqueId(), Collections.emptyList());

        return getConfig().getString("ollama.system-prompt", "")
                .replace("{player}", player.getName())
                .replace("{plugins}", plugins)
                .replace("{permissions}", permissions)
                .replace("{chat_history}", String.join(" | ", playerChat))
                .replace("{command_history}", String.join(" | ", playerCmds))
                .replace("{online_players}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{server_version}", Bukkit.getVersion());
    }

    private Component formatMsg(String path) {
        String msg = getConfig().getString(path, "");
        return LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>(List.of("optout"));
            if (sender.hasPermission("aiassistant.admin.reset")) {
                list.add("reset");
            }
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset") && sender.hasPermission("aiassistant.admin.reset")) {
            List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            players.add("all");
            return players;
        }
        return Collections.emptyList();
    }
}