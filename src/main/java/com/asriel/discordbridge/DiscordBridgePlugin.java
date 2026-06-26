package com.asriel.discordbridge;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class DiscordBridgePlugin extends JavaPlugin implements Listener {
    private HttpServer server;
    private String discordBotUrl;

    @Override
    public void onEnable() {
        getLogger().info("DiscordBridge Enabled");
        saveDefaultConfig();
        discordBotUrl = getConfig().getString("discord-bot-url", "http://localhost:3000");
        Bukkit.getPluginManager().registerEvents(this, this);
        startHttpServer();
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String player = event.getPlayer().getName();
        String message = event.getMessage();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL(discordBotUrl + "/mc-chat");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(3000); // 新增
                conn.setReadTimeout(3000);    // 新增
                conn.setDoOutput(true);
                String json = "{\"player\":\"" + player + "\",\"message\":\"" + message + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("無法傳送聊天訊息到 Discord: " + e.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        String deathMessage = event.getDeathMessage();
        if (deathMessage == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL(discordBotUrl + "/server-log");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoOutput(true);
                String escaped = deathMessage.replace("\"", "\\\"");
                String json = "{\"type\":\"death\",\"message\":\"" + escaped + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("無法傳送死亡訊息: " + e.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
        // 過濾掉 Minecraft 內部用的 recipe/root 進度
        String key = event.getAdvancement().getKey().getKey();
        if (key.startsWith("recipes/") || key.equals("root")) return;

        String player = event.getPlayer().getName();
        String title = event.getAdvancement().getKey().getKey()
            .replace("_", " ");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL(discordBotUrl + "/server-log");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoOutput(true);
                String json = "{\"type\":\"advancement\",\"player\":\"" + player
                    + "\",\"advancement\":\"" + title + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("無法傳送成就訊息: " + e.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String player = event.getPlayer().getName();
        String command = event.getMessage(); // 包含 / 開頭

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL(discordBotUrl + "/server-log");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoOutput(true);
                String escaped = command.replace("\"", "\\\"");
                String json = "{\"type\":\"command\",\"player\":\"" + player
                    + "\",\"command\":\"" + escaped + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("無法傳送指令訊息: " + e.getMessage());
            }
        });
    }


    private void startHttpServer() {
        try {
            int port = getConfig().getInt("server-port", 8080);
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newCachedThreadPool()); // 新增
            server.createContext("/players", exchange -> {
                if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                    exchange.sendResponseHeaders(405, -1); // 新增
                    exchange.close();
                    return;
                }
                String players = Bukkit.getOnlinePlayers()
                        .stream()
                        .map(Player::getName)
                        .collect(Collectors.joining(","));
                sendResponse(exchange, "{\"players\":\"" + players + "\"}");
            });
            server.createContext("/player", exchange -> {
                if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                String name = path.replace("/player/", "");
                Player player = Bukkit.getPlayer(name);
                if (player == null) {
                    sendResponse(exchange, "{\"error\":\"not_found\"}");
                    return;
                }
                Location loc = player.getLocation();
                String json = "{"
                        + "\"name\":\"" + player.getName() + "\","
                        + "\"world\":\"" + loc.getWorld().getName() + "\","
                        + "\"x\":" + loc.getBlockX() + ","
                        + "\"y\":" + loc.getBlockY() + ","
                        + "\"z\":" + loc.getBlockZ()
                        + "}";
                sendResponse(exchange, json);
            });
            server.createContext("/discord-chat", exchange -> {
                if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String user = body.replaceAll(".*\"user\":\"([^\"]+)\".*", "$1");
                String msg = body.replaceAll(".*\"message\":\"([^\"]+)\".*", "$1");
                Bukkit.getScheduler().runTask(this, () -> {
                    Bukkit.broadcastMessage("§9[Discord] §f" + user + ": " + msg);
                });
                sendResponse(exchange, "{\"ok\":true}");
            });
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendResponse(HttpExchange exchange, String response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }
}
