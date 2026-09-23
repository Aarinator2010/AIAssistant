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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        // Dynamische Registrierung über CommandMap für volle Paper/Purpur-Kompatibilität
        CommandMap commandMap = Bukkit.getCommandMap();
        Command aiCommand = new Command("ai", "Stellt der KI eine Frage oder schaltet Opt-out um", "/ai <Frage|optout>", List.of()) {
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler können diesen Befehl nutzen.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(formatMsg("messages.usage"));
            return true;
        }

        if (args[0].equalsIgnoreCase("optout") || args[0].equalsIgnoreCase("toggle")) {
            if (optOutPlayers.contains(player.getUniqueId())) {
                optOutPlayers.remove(player.getUniqueId());
                player.sendMessage(formatMsg("messages.optout-disabled"));
            } else {
                optOutPlayers.add(player.getUniqueId());
                player.sendMessage(formatMsg("messages.optout-enabled"));
            }
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

    private CompletableFuture<String> sendAiRequest(Player player, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiUrl = getConfig().getString("ollama.url", "http://localhost:11434/api/chat");
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

        List<String> playerChat = chatHistory.getOrDefault(player.getUniqueId(), Collections.emptyList());
        List<String> playerCmds = commandHistory.getOrDefault(player.getUniqueId(), Collections.emptyList());

        return getConfig().getString("ollama.system-prompt", "")
                .replace("{player}", player.getName())
                .replace("{plugins}", plugins)
                .replace("{chat_history}", String.join(" | ", playerChat))
                .replace("{command_history}", String.join(" | ", playerCmds))
                .replace("{online_players}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{server_version}", Bukkit.getVersion());
    }

    private boolean checkDailyLimit(Player player) {
        checkDailyReset();
        int max = getConfig().getInt("daily-limit", 10);
        if (max <= 0) return true;
        return dailyUsage.getOrDefault(player.getUniqueId(), 0) < max;
    }

    private void incrementDailyLimit(Player player) {
        dailyUsage.merge(player.getUniqueId(), 1, Integer::sum);
    }

    private void checkDailyReset() {
        if (!LocalDate.now().equals(lastResetDate)) {
            dailyUsage.clear();
            lastResetDate = LocalDate.now();
        }
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
            return List.of("optout");
        }
        return Collections.emptyList();
    }
}