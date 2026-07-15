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
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.util.ArrayList;
import java.util.List;

public class DiscordBridgePlugin extends JavaPlugin implements Listener {
    private HttpServer server;
    private String discordBotUrl;
    private String backendUrl;
    private List<Integer> mapIds = new ArrayList<>();

    @Override
    public void onEnable() {
        getLogger().info("DiscordBridge Enabled");
        saveDefaultConfig();
        discordBotUrl = getConfig().getString("discord-bot-url", "http://localhost:3000");
        backendUrl = getConfig().getString("backend-url", "http://localhost:5000");
        Bukkit.getPluginManager().registerEvents(this, this);
        startHttpServer();
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("getmap")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("這個指令只能由玩家執行。");
                return true;
            }

            if (mapIds.isEmpty()) {
                sender.sendMessage("§c目前還沒有可領取的地圖。");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("§e可用的地圖：");
                for (int i = 0; i < mapIds.size(); i++) {
                    sender.sendMessage("§f  /getmap map" + i);
                }
                return true;
            }

            String arg = args[0].toLowerCase();
            if (!arg.startsWith("map")) {
                sender.sendMessage("§c格式錯誤，請使用 /getmap map0、/getmap map1 等。");
                return true;
            }

            int index;
            try {
                index = Integer.parseInt(arg.substring(3));
            } catch (NumberFormatException e) {
                sender.sendMessage("§c格式錯誤，請使用 /getmap map0、/getmap map1 等。");
                return true;
            }

            if (index < 0 || index >= mapIds.size()) {
                sender.sendMessage("§c地圖不存在，目前共有 " + mapIds.size() + " 張（map0 到 map" + (mapIds.size() - 1) + "）。");
                return true;
            }

            Player player = (Player) sender;
            MapView map = Bukkit.getMap(mapIds.get(index));

            if (map == null) {
                sender.sendMessage("§c地圖不存在，請聯絡管理員。");
                return true;
            }

            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            org.bukkit.inventory.meta.MapMeta meta =
                (org.bukkit.inventory.meta.MapMeta) mapItem.getItemMeta();
            meta.setMapView(map);
            mapItem.setItemMeta(meta);

            player.getInventory().addItem(mapItem);
            player.sendMessage("§a已給予 map" + index + "！");
            return true;
        }

        if (command.getName().equalsIgnoreCase("bind")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("這個指令只能由玩家執行。");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("§c請輸入 token：/bind <token>");
                return true;
            }

            Player player = (Player) sender;
            String token = args[0];

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    URL url = new URL(backendUrl + "/api/bind/mc");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setDoOutput(true);

                    String json = "{\"token\":\"" + token + "\",\"mc_username\":\"" + player.getName() + "\"}";
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            player.sendMessage("§a綁定成功！");
                        });
                    } else {
                        Bukkit.getScheduler().runTask(this, () -> {
                            player.sendMessage("§ctoken 無效，請確認後再試。");
                        });
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    getLogger().warning("綁定失敗: " + e.getMessage());
                    Bukkit.getScheduler().runTask(this, () -> {
                        player.sendMessage("§c無法連線到伺服器，請稍後再試。");
                    });
                }
            });
            return true;
        }

        return false;
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
                conn.setRequestProperty("X-Auth-Token", getConfig().getString("bot-api-token", ""));
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
                conn.setRequestProperty("X-Auth-Token", getConfig().getString("bot-api-token", ""));
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
                conn.setRequestProperty("X-Auth-Token", getConfig().getString("bot-api-token", ""));
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
                conn.setRequestProperty("X-Auth-Token", getConfig().getString("bot-api-token", ""));
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

            server.createContext("/map-data", exchange -> {
                if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        JSONParser parser = new JSONParser();
                        JSONObject json = (JSONObject) parser.parse(body);
                        JSONArray pixelArray = (JSONArray) json.get("pixels");

                        final byte[] pixels = new byte[16384]; // 128x128
                        for (int i = 0; i < pixelArray.size(); i++) {
                            pixels[i] = ((Long) pixelArray.get(i)).byteValue();
                        }

                        MapView map = Bukkit.createMap(Bukkit.getWorlds().get(0));
                        map.getRenderers().forEach(map::removeRenderer);
                        map.addRenderer(new MapRenderer(false) {
                            private boolean rendered = false;

                            @Override
                            public void render(MapView mapView, MapCanvas canvas, Player player) {
                                if (rendered) return;
                                for (int y = 0; y < 128; y++) {
                                    for (int x = 0; x < 128; x++) {
                                        canvas.setPixel(x, y, pixels[y * 128 + x]);
                                    }
                                }
                                rendered = true;
                            }
                        });

                        mapIds.add(map.getId());
                        getLogger().info("地圖建立成功，ID: " + map.getId() + "，目前共 " + mapIds.size() + " 張");

                    } catch (Exception e) {
                        getLogger().warning("建立地圖失敗: " + e.getMessage());
                        e.printStackTrace();
                    }
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
